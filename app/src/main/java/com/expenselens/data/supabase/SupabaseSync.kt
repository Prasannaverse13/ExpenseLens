package com.expenselens.data.supabase

import android.content.Context
import android.util.Log
import com.expenselens.data.auth.SupabaseAuthStore
import com.expenselens.data.db.CategoryEntity
import com.expenselens.data.db.ExpenseEntity
import com.expenselens.data.db.ExpenseMetadataEntity
import com.expenselens.data.db.LineItemEntity
import com.expenselens.data.db.VendorCorrectionEntity
import com.expenselens.data.prefs.AppPreferences
import com.expenselens.data.repo.ExpenseRepository
import com.expenselens.data.storage.BillStorage
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Replaces the JSON-blob-to-Drive model of [com.expenselens.data.backup.BackupManager]
 * with row-level Supabase writes.
 *
 * Push flow (called by SyncCoordinator 5s after the last local change):
 *   1. Read every local expense + line item + category + correction
 *   2. For each bill with a local image, upload to `bills/{user_id}/{bill_id}.jpg`
 *   3. Upsert the row into `bills` (image_path populated from step 2)
 *   4. Delete + reinsert the bill's line items
 *   5. Upsert categories (by name)
 *   6. Upsert vendor corrections
 *   7. Upsert `premium` row from AppPreferences
 *   8. Update usage_counters (monthly AI cap)
 *
 * Pull flow (called by SyncCoordinator on first launch after sign-in):
 *   1. SELECT all rows for the user
 *   2. Replace local categories, vendor corrections
 *   3. For each bill, download the image from Storage to BillStorage
 *   4. Replace local expenses + line items
 *   5. Restore premium flag
 *
 * The local Room DB is still the live working copy — Supabase is the
 * durable mirror. Auto-push happens 5s after the last save.
 */
class SupabaseSync(
    private val context: Context,
    private val client: SupabaseClientProvider,
    private val auth: SupabaseAuthStore,
    private val repo: ExpenseRepository,
    private val prefs: AppPreferences
) {

    /** Public result wrapper so callers (SyncCoordinator) can react uniformly. */
    sealed class SyncResult {
        data class Success(val rowsWritten: Int) : SyncResult()
        data class Failure(val reason: String) : SyncResult()
        data object NotConfigured : SyncResult()
        data object NotSignedIn : SyncResult()
    }

    // ─── Push (local → Supabase) ────────────────────────────────────

    suspend fun pushNow(): SyncResult = withContext(Dispatchers.IO) {
        val sb = client.client
            ?: return@withContext if (client.isConfigured()) SyncResult.Failure("Supabase client failed to init")
            else SyncResult.NotConfigured
        val userId = auth.userId()
            ?: return@withContext SyncResult.NotSignedIn

        try {
            val expensesWithItems = repo.observeAllWithItems().first()
            val cats = repo.categories()
            val corrections = repo.vendorCorrections()

            val catIdByName = cats.associate { it.name to it.id }
            val catRowIdByName = HashMap<String, String>()
            var rows = 0

            // 1) Categories — upsert by (user_id, name).
            for (c in cats) {
                val catId = UUID.randomUUID().toString()
                catRowIdByName[c.name] = catId
                sb.postgrest.from("categories").upsert(
                    CategoryRow(
                        id = catId,
                        userId = userId,
                        name = c.name,
                        color = null,
                        icon = null,
                        sortOrder = 0
                    )
                )
                rows++
            }

            // 2) Vendor corrections — upsert by (user_id, vendor_key).
            for (vc in corrections) {
                val catName = cats.firstOrNull { it.id == vc.categoryId }?.name
                    ?: continue
                sb.postgrest.from("vendor_corrections").upsert(
                    VendorCorrectionRow(
                        id = UUID.randomUUID().toString(),
                        userId = userId,
                        vendorKey = vc.vendorKey,
                        category = catName,
                        hitCount = vc.hitCount
                    )
                )
                rows++
            }

            // 3) Bills + line items.
            for (row in expensesWithItems) {
                val e = row.expense
                val billId = if (e.id == 0L) UUID.randomUUID().toString()
                else stableUuidFromLong(e.id)
                val imagePath = e.billFileUri?.let { localPath ->
                    val f = File(localPath)
                    if (f.exists() && f.length() > 0) {
                        val remotePath = "$userId/$billId.jpg"
                        try {
                            val bucket = sb.storage.from(SupabaseClientProvider.BILLS_BUCKET)
                            bucket.upload(remotePath, f.readBytes(), upsert = true)
                            remotePath
                        } catch (t: Throwable) {
                            Log.w(TAG, "Bill image upload failed for ${e.id}: ${t.message}")
                            null
                        }
                    } else null
                }

                sb.postgrest.from("bills").upsert(
                    BillRow(
                        id = billId,
                        userId = userId,
                        vendor = e.vendor,
                        billNumber = e.billNumber,
                        billDate = e.billDate.toString(),
                        totalCents = (e.totalAmount * 100).toLong(),
                        taxCents = e.taxAmount?.let { (it * 100).toLong() },
                        currency = e.currency,
                        paymentMethod = e.paymentMethod,
                        notes = e.notes,
                        imagePath = imagePath,
                        aiExtracted = e.confidence > 0.6f,
                        confidence = e.confidence,
                        needsReview = e.needsReview,
                        ocrText = e.ocrText
                    )
                )
                rows++

                // Line items — delete old, insert fresh.
                sb.postgrest.from("line_items").delete {
                    filter { eq("bill_id", billId) }
                }
                for (li in row.lineItems) {
                    sb.postgrest.from("line_items").insert(
                        LineItemRow(
                            id = UUID.randomUUID().toString(),
                            billId = billId,
                            description = li.description,
                            quantity = li.quantity.toFloat(),
                            unitCents = (li.unitPrice * 100).toLong(),
                            totalCents = (li.lineTotal * 100).toLong(),
                            category = cats.firstOrNull { it.id == li.categoryId }?.name,
                            categoryConfidence = li.categoryConfidence
                        )
                    )
                    rows++
                }
            }

            // 4) Premium — upsert one row per user.
            val isPremium = prefs.isPremium.first()
            sb.postgrest.from("premium").upsert(
                PremiumRow(
                    userId = userId,
                    isPremium = isPremium,
                    updatedAt = Instant.now().toString()
                )
            )
            rows++

            // 5) Monthly AI usage — upsert (user_id, month) = ai_calls.
            val month = java.time.LocalDate.now().toString().take(7) // "2026-08"
            val aiCalls = prefs.smartCalls.first()
            sb.postgrest.from("usage_counters").upsert(
                UsageCounterRow(userId = userId, month = month, aiCalls = aiCalls)
            )
            rows++

            Log.i(TAG, "Push complete: $rows rows written")
            SyncResult.Success(rows)
        } catch (t: Throwable) {
            Log.e(TAG, "Push failed", t)
            SyncResult.Failure(t.message ?: "Push failed")
        }
    }

    // ─── Pull (Supabase → local) ────────────────────────────────────

    suspend fun pullOnStart(): SyncResult = withContext(Dispatchers.IO) {
        val sb = client.client
            ?: return@withContext if (client.isConfigured()) SyncResult.Failure("Supabase client failed to init")
            else SyncResult.NotConfigured
        val userId = auth.userId()
            ?: return@withContext SyncResult.NotSignedIn

        try {
            // 1) Categories
            val catRows = sb.postgrest.from("categories")
                .select()
                .decodeList<CategoryRow>()
                .filter { it.userId == userId }
            val catEntities = catRows.map {
                CategoryEntity(
                    id = 0L, // let Room auto-assign
                    name = it.name
                )
            }
            repo.replaceAllCategories(catEntities)
            val catNameByRowId = catRows.associate { it.id to it.name }
            val localCatByName = repo.categories().associate { it.name to it.id }

            // 2) Vendor corrections
            val corrRows = sb.postgrest.from("vendor_corrections")
                .select()
                .decodeList<VendorCorrectionRow>()
                .filter { it.userId == userId }
            val corrEntities = corrRows.mapNotNull { row ->
                val localCatId = localCatByName[row.category] ?: return@mapNotNull null
                VendorCorrectionEntity(
                    id = 0L,
                    vendorKey = row.vendorKey,
                    categoryId = localCatId,
                    hitCount = row.hitCount
                )
            }
            repo.replaceAllVendorCorrections(corrEntities)

            // 3) Bill images — wipe local copy, then download each.
            val billsDir = BillStorage.billsDir(context)
            billsDir.listFiles()?.forEach { it.delete() }
            val bucket = sb.storage.from(SupabaseClientProvider.BILLS_BUCKET)

            // 4) Bills + line items
            val billRows = sb.postgrest.from("bills")
                .select()
                .decodeList<BillRow>()
                .filter { it.userId == userId }
            val lineRows = sb.postgrest.from("line_items")
                .select()
                .decodeList<LineItemRow>()

            val expenseEntities = mutableListOf<ExpenseEntity>()
            val lineItemBundles = mutableListOf<Pair<ExpenseEntity, List<LineItemEntity>>>()
            for (b in billRows) {
                val localImagePath: String? = b.imagePath?.let { remotePath ->
                    try {
                        val bytes = bucket.downloadAuthenticated(remotePath)
                        val localName = "${b.id}.jpg"
                        val target = File(billsDir, localName)
                        target.writeBytes(bytes)
                        target.absolutePath
                    } catch (t: Throwable) {
                        Log.w(TAG, "Bill image download failed for ${b.id}: ${t.message}")
                        null
                    }
                }
                val e = ExpenseEntity(
                    id = 0L, // Room auto-assigns
                    vendor = b.vendor,
                    billNumber = b.billNumber,
                    billDate = java.time.LocalDate.parse(b.billDate),
                    totalAmount = b.totalCents / 100.0,
                    taxAmount = b.taxCents?.let { it / 100.0 },
                    currency = b.currency,
                    paymentMethod = b.paymentMethod,
                    notes = b.notes,
                    createdAt = b.createdAt?.let { java.time.LocalDateTime.parse(it) }
                        ?: java.time.LocalDateTime.now(),
                    updatedAt = b.updatedAt?.let { java.time.LocalDateTime.parse(it) }
                        ?: java.time.LocalDateTime.now(),
                    confidence = b.confidence,
                    needsReview = b.needsReview,
                    categoryId = localCatByName[catNameByRowId[b.id]] ?: 1L,
                    billFileUri = localImagePath,
                    billMime = "image/jpeg",
                    ocrText = b.ocrText
                )
                expenseEntities.add(e)
                val lineItems = lineRows
                    .filter { it.billId == b.id }
                    .map { li ->
                        LineItemEntity(
                            id = 0L,
                            expenseId = 0L, // filled in by repo
                            description = li.description,
                            quantity = li.quantity.toDouble(),
                            unitPrice = li.unitCents / 100.0,
                            lineTotal = li.totalCents / 100.0,
                            categoryId = localCatByName[li.category] ?: 1L,
                            categoryConfidence = li.categoryConfidence
                        )
                    }
                lineItemBundles.add(e to lineItems)
            }
            repo.replaceAllExpensesWithItems(expenseEntities, lineItemBundles)

            // 5) Premium
            val premiumRows = sb.postgrest.from("premium")
                .select()
                .decodeList<PremiumRow>()
                .filter { it.userId == userId }
            premiumRows.firstOrNull()?.let { row ->
                if (row.isPremium) prefs.setPremium(true)
            }

            Log.i(TAG, "Pull complete: ${billRows.size} bills, ${catRows.size} categories, ${corrRows.size} corrections")
            SyncResult.Success(billRows.size + catRows.size + corrRows.size)
        } catch (t: Throwable) {
            Log.e(TAG, "Pull failed", t)
            SyncResult.Failure(t.message ?: "Pull failed")
        }
    }

    /**
     * Stable UUID derived from a Long expense id. Same id on every
     * push so upserts in Supabase correctly replace existing rows
     * instead of creating duplicates.
     */
    private fun stableUuidFromLong(id: Long): String {
        // Deterministic namespace UUID (UUIDv5) — not cryptographic,
        // just stable across the local→cloud boundary.
        val ns = UUID.fromString("00000000-0000-0000-0000-000000000001")
        return UUID.nameUUIDFromBytes("expenselens.bill.$id".toByteArray()).toString()
    }

    companion object {
        private const val TAG = "SupabaseSync"
    }
}
