package com.expenselens.data.backup

import android.content.Context
import android.util.Base64
import android.util.Log
import com.expenselens.data.db.CategoryEntity
import com.expenselens.data.db.ExpenseEntity
import com.expenselens.data.db.ExpenseMetadataEntity
import com.expenselens.data.db.LineItemEntity
import com.expenselens.data.db.VendorCorrectionEntity
import com.expenselens.data.drive.GoogleDriveManager
import com.expenselens.data.prefs.AppPreferences
import com.expenselens.data.repo.ExpenseRepository
import com.expenselens.data.storage.BillStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Serialises the local database + bill image folder to a single JSON
 * document and uploads it to the user's Google Drive. Restore pulls
 * the same JSON shape back and re-inserts the rows.
 *
 * Backup schema is versioned (see [SCHEMA_VERSION]). Restore refuses to
 * import from a newer schema so the user doesn't end up with a partial
 * state after a downgrade.
 */
class BackupManager(
    private val context: Context,
    private val repo: ExpenseRepository,
    private val drive: GoogleDriveManager,
    private val prefs: AppPreferences
) {

    /** What the UI surfaces after a sync. */
    sealed class SyncResult {
        data class Success(val driveFileId: String, val bytes: Long) : SyncResult()
        data class Failure(val reason: String) : SyncResult()
    }

    /**
     * Build a JSON blob of every expense + its line items + every
     * receipt image in app-private storage. Suspends; the caller should
     * already be on a background dispatcher.
     */
    private suspend fun buildBackupJson(): JSONObject {
        val expensesWithItems = repo.observeAllWithItems().first()
        val cats = repo.categories()
        val corrections = repo.vendorCorrections()

        val expensesJson = JSONArray()
        for (row in expensesWithItems) {
            val e = row.expense
            val categoryName = cats.firstOrNull { it.id == e.categoryId }?.name ?: ""
            val lineItems = JSONArray()
            for (li in row.lineItems) {
                val liObj = JSONObject().apply {
                    put("description", li.description)
                    put("quantity", li.quantity)
                    put("unitPrice", li.unitPrice)
                    put("lineTotal", li.lineTotal)
                    put("category", cats.firstOrNull { it.id == li.categoryId }?.name ?: "")
                    put("categoryConfidence", li.categoryConfidence.toDouble())
                }
                lineItems.put(liObj)
            }
            val o = JSONObject().apply {
                put("id", e.id)
                put("vendor", e.vendor)
                put("billNumber", e.billNumber ?: JSONObject.NULL)
                put("billDate", e.billDate.toString())
                put("totalAmount", e.totalAmount)
                put("taxAmount", e.taxAmount ?: JSONObject.NULL)
                put("currency", e.currency)
                put("paymentMethod", e.paymentMethod)
                put("notes", e.notes ?: JSONObject.NULL)
                put("createdAt", e.createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                put("updatedAt", e.updatedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                put("confidence", e.confidence)
                put("needsReview", e.needsReview)
                put("category", categoryName)
                put("billFileName", e.billFileUri?.let { File(it).name } ?: JSONObject.NULL)
                put("billMime", e.billMime ?: JSONObject.NULL)
                put("ocrText", e.ocrText ?: JSONObject.NULL)
                put("lineItems", lineItems)
            }
            expensesJson.put(o)
        }

        // Bill images — base64-encode each file. For users with thousands
        // of bills this would be a lot, but the per-file sizes are small
        // and the backup runs over Wi-Fi, so it's fine for a v1.
        val billsDir = BillStorage.billsDir(context)
        val billsJson = JSONObject()
        billsDir.listFiles()?.forEach { f ->
            try {
                val bytes = f.readBytes()
                billsJson.put(
                    f.name,
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to read bill ${f.name}: ${t.message}")
            }
        }

        val categoriesJson = JSONArray()
        cats.forEach { c -> categoriesJson.put(c.name) }

        val correctionsJson = JSONArray()
        corrections.forEach { vc ->
            correctionsJson.put(JSONObject().apply {
                put("vendorKey", vc.vendorKey)
                put("category", cats.firstOrNull { it.id == vc.categoryId }?.name ?: "")
                put("hitCount", vc.hitCount)
            })
        }

        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("appVersion", appVersion())
            put("exportedAt", Instant.now().toString())
            put("isPremium", prefs.isPremium.first())
            put("expenseCount", expensesWithItems.size)
            put("categories", categoriesJson)
            put("vendorCorrections", correctionsJson)
            put("expenses", expensesJson)
            put("bills", billsJson)
        }
    }

    /** Sync now: build JSON, upload, return result. */
    suspend fun syncNow(): SyncResult = withContext(Dispatchers.IO) {
        val json = try {
            buildBackupJson()
        } catch (t: Throwable) {
            Log.e(TAG, "Backup build failed", t)
            return@withContext SyncResult.Failure("Couldn't build backup: ${t.message}")
        }
        val bytes = json.toString(2).toByteArray(Charsets.UTF_8)
        val filename = "expenselens-backup-${LocalDate.now()}-${System.currentTimeMillis()}.json"
        val id = drive.uploadBackup(filename, "application/json", bytes)
        if (id == null) {
            SyncResult.Failure("Drive upload failed. Check connection or reconnect Google Drive.")
        } else {
            SyncResult.Success(id, bytes.size.toLong())
        }
    }

    /**
     * Pull a backup from Drive, validate it, and re-insert the rows into
     * the local DB. Existing rows with the same id are replaced (so the
     * restore is idempotent — running it twice doesn't double your data).
     */
    suspend fun restoreFromDrive(driveFileId: String): SyncResult = withContext(Dispatchers.IO) {
        val bytes = drive.downloadFile(driveFileId)
            ?: return@withContext SyncResult.Failure("Couldn't download backup from Drive.")
        val json = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (t: Throwable) {
            return@withContext SyncResult.Failure("Backup is not valid JSON.")
        }
        val version = json.optInt("schemaVersion", 0)
        if (version > SCHEMA_VERSION) {
            return@withContext SyncResult.Failure(
                "Backup is from a newer app version (schema $version). Update the app first."
            )
        }
        val expenses = json.optJSONArray("expenses") ?: JSONArray()
        val bills = json.optJSONObject("bills") ?: JSONObject()
        val categories = json.optJSONArray("categories") ?: JSONArray()
        val corrections = json.optJSONArray("vendorCorrections") ?: JSONArray()

        // Restore Premium status from the backup so the user's subscription
        // follows them across devices and reinstalls (after re-signing-in
        // with the same Google account).
        val backupIsPremium = json.optBoolean("isPremium", false)
        prefs.setPremium(backupIsPremium)

        try {
            // 1) categories — replace by name
            val catEntities = (0 until categories.length()).map { i ->
                CategoryEntity(name = categories.optString(i))
            }
            repo.replaceAllCategories(catEntities)

            // 2) bill files — restore to app-private storage
            val billsDir = BillStorage.billsDir(context)
            billsDir.listFiles()?.forEach { it.delete() }
            bills.keys().forEach { name ->
                val b64 = bills.optString(name)
                if (b64.isNotEmpty()) {
                    val data = Base64.decode(b64, Base64.DEFAULT)
                    File(billsDir, name).writeBytes(data)
                }
            }

            // 3) expenses + line items — delegate to repo so FK + ids stay sane
            val catIdByName = repo.categories().associate { it.name to it.id }
            val expenseEntities = mutableListOf<ExpenseEntity>()
            val lineItemBundles = mutableListOf<Pair<ExpenseEntity, List<LineItemEntity>>>()
            for (i in 0 until expenses.length()) {
                val o = expenses.getJSONObject(i)
                val billFileName = o.optString("billFileName", "").takeIf { it.isNotEmpty() }
                val billFileUri = billFileName?.let { File(billsDir, it).absolutePath }
                val e = ExpenseEntity(
                    id = o.optLong("id", 0L),
                    vendor = o.optString("vendor"),
                    billNumber = o.optString("billNumber", "").takeIf { it.isNotEmpty() },
                    billDate = LocalDate.parse(o.optString("billDate")),
                    totalAmount = o.optDouble("totalAmount", 0.0),
                    taxAmount = o.optDouble("taxAmount", 0.0).takeIf { !o.isNull("taxAmount") },
                    currency = o.optString("currency", "INR"),
                    paymentMethod = o.optString("paymentMethod", "Cash"),
                    notes = o.optString("notes", "").takeIf { it.isNotEmpty() },
                    createdAt = LocalDateTime.parse(o.optString("createdAt")),
                    updatedAt = LocalDateTime.parse(o.optString("updatedAt")),
                    confidence = o.optDouble("confidence", 0.0).toFloat(),
                    needsReview = o.optBoolean("needsReview", false),
                    categoryId = catIdByName[o.optString("category")] ?: 1L,
                    billFileUri = billFileUri,
                    billMime = o.optString("billMime", "").takeIf { it.isNotEmpty() },
                    ocrText = o.optString("ocrText", "").takeIf { it.isNotEmpty() }
                )
                expenseEntities.add(e)
                val liArray = o.optJSONArray("lineItems") ?: JSONArray()
                val lineItems = (0 until liArray.length()).map { j ->
                    val li = liArray.getJSONObject(j)
                    LineItemEntity(
                        id = 0L,
                        expenseId = 0L, // filled in by repo
                        description = li.optString("description"),
                        quantity = li.optDouble("quantity", 1.0),
                        unitPrice = li.optDouble("unitPrice", 0.0),
                        lineTotal = li.optDouble("lineTotal", 0.0),
                        categoryId = catIdByName[li.optString("category")] ?: 1L,
                        categoryConfidence = li.optDouble("categoryConfidence", 0.0).toFloat()
                    )
                }
                lineItemBundles.add(e to lineItems)
            }
            repo.replaceAllExpensesWithItems(expenseEntities, lineItemBundles)

            // 4) vendor corrections
            val corrEntities = (0 until corrections.length()).mapNotNull { i ->
                val c = corrections.getJSONObject(i)
                val name = c.optString("category")
                val catId = catIdByName[name] ?: return@mapNotNull null
                VendorCorrectionEntity(
                    vendorKey = c.optString("vendorKey"),
                    categoryId = catId,
                    hitCount = c.optInt("hitCount", 1)
                )
            }
            repo.replaceAllVendorCorrections(corrEntities)

            SyncResult.Success(driveFileId, bytes.size.toLong())
        } catch (t: Throwable) {
            Log.e(TAG, "Restore failed", t)
            SyncResult.Failure("Restore failed: ${t.message}")
        }
    }

    /** Soft helper: most recent backup, or null if none on Drive. */
    suspend fun latestBackup(): GoogleDriveManager.DriveFile? =
        drive.listBackups().firstOrNull()

    private fun appVersion(): String = try {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        pkg.versionName ?: "unknown"
    } catch (_: Throwable) { "unknown" }

    companion object {
        private const val TAG = "BackupManager"
        // v1: initial schema
        // v2: added `isPremium` field (optional, defaults to false)
        private const val SCHEMA_VERSION = 2
    }
}
