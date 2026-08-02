package com.expenselens.ui.screen

import android.content.Context
import com.expenselens.domain.model.ExtractionResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persists in-progress extraction results to a JSON file in app-private
 * storage so the Review screen can pick them up after navigation.
 */
object DraftStore {

    private fun draftsDir(context: Context): File {
        val dir = File(context.filesDir, "drafts")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun save(
        context: Context,
        result: ExtractionResult,
        sourceFile: String?,
        sourceMime: String?
    ): String {
        val id = UUID.randomUUID().toString()
        val obj = JSONObject().apply {
            put("id", id)
            put("sourceFile", sourceFile ?: "")
            put("sourceMime", sourceMime ?: "")
            put("vendor", result.vendor)
            put("billNumber", result.billNumber ?: JSONObject.NULL)
            put("billDate", result.billDate.toString())
            put("totalAmount", result.totalAmount)
            put("taxAmount", result.taxAmount ?: JSONObject.NULL)
            put("currency", result.currency)
            put("rawText", result.rawText)
            val conf = JSONObject()
            result.fieldConfidences.forEach { (k, v) -> conf.put(k, v) }
            put("confidences", conf)
            val arr = JSONArray()
            result.lineItems.forEach { li ->
                arr.put(JSONObject().apply {
                    put("description", li.description)
                    put("quantity", li.quantity)
                    put("unitPrice", li.unitPrice)
                    put("lineTotal", li.lineTotal)
                    put("category", li.category.displayName)
                    put("categoryConfidence", li.categoryConfidence.toDouble())
                })
            }
            put("lineItems", arr)
            result.metadata?.let { md ->
                put("metadata", JSONObject().apply {
                    val phones = JSONArray()
                    md.merchantPhone.forEach { phones.put(it) }
                    put("merchantPhones", phones)
                    md.merchantEmail?.let { put("merchantEmail", it) } ?: put("merchantEmail", JSONObject.NULL)
                    md.fssaiNumber?.let { put("fssaiNumber", it) } ?: put("fssaiNumber", JSONObject.NULL)
                    md.visitTime?.let { put("visitTime", it) } ?: put("visitTime", JSONObject.NULL)
                    if (md.itemCount != null) put("itemCount", md.itemCount) else put("itemCount", JSONObject.NULL)
                    md.source?.let { put("source", it) } ?: put("source", JSONObject.NULL)
                })
            }
        }
        File(draftsDir(context), "$id.json").writeText(obj.toString())
        return id
    }

    fun load(context: Context, draftId: String): ExtractionResult? {
        val file = File(draftsDir(context), "$draftId.json")
        if (!file.exists()) return null
        val obj = JSONObject(file.readText())
        val items = obj.getJSONArray("lineItems")
        val lines = (0 until items.length()).map { i ->
            val li = items.getJSONObject(i)
            com.expenselens.domain.model.LineItem(
                description = li.optString("description"),
                quantity = li.optDouble("quantity", 1.0),
                unitPrice = li.optDouble("unitPrice", 0.0),
                lineTotal = li.optDouble("lineTotal", 0.0),
                category = com.expenselens.domain.model.CategoryType.fromName(li.optString("category")),
                categoryConfidence = li.optDouble("categoryConfidence", 0.0).toFloat()
            )
        }
        val conf = obj.optJSONObject("confidences")
        val confMap = if (conf != null) {
            conf.keys().asSequence().associateWith { conf.optDouble(it, 0.0).toFloat() }
        } else emptyMap()
        return ExtractionResult(
            vendor = obj.optString("vendor"),
            billNumber = if (obj.isNull("billNumber")) null else obj.optString("billNumber"),
            billDate = java.time.LocalDate.parse(obj.optString("billDate")),
            totalAmount = obj.optDouble("totalAmount", 0.0),
            taxAmount = if (obj.isNull("taxAmount")) null else obj.optDouble("taxAmount", 0.0),
            currency = obj.optString("currency", "INR"),
            rawText = obj.optString("rawText"),
            lineItems = lines,
            fieldConfidences = confMap,
            metadata = obj.optJSONObject("metadata")?.let { mo ->
                val phones = mo.optJSONArray("merchantPhones")
                val phoneList = if (phones != null) (0 until phones.length())
                    .mapNotNull { phones.optString(it).ifBlank { null } }
                else {
                    // Backwards-compat: older drafts stored a single string.
                    val single = mo.optString("merchantPhone")
                    if (mo.isNull("merchantPhone") || single.isBlank()) emptyList()
                    else listOf(single)
                }
                com.expenselens.domain.model.ExpenseMetadata(
                    merchantPhone = phoneList,
                    merchantEmail = if (mo.isNull("merchantEmail")) null else mo.optString("merchantEmail").ifBlank { null },
                    fssaiNumber = if (mo.isNull("fssaiNumber")) null else mo.optString("fssaiNumber").ifBlank { null },
                    visitTime = if (mo.isNull("visitTime")) null else mo.optString("visitTime").ifBlank { null },
                    itemCount = if (mo.isNull("itemCount")) null else mo.optInt("itemCount").takeIf { it > 0 },
                    source = if (mo.isNull("source")) null else mo.optString("source").ifBlank { null }
                )
            }
        )
    }

    fun delete(context: Context, draftId: String) {
        File(draftsDir(context), "$draftId.json").delete()
    }

    fun sourceFile(context: Context, draftId: String): File? {
        val f = File(draftsDir(context), "$draftId.json")
        if (!f.exists()) return null
        val path = JSONObject(f.readText()).optString("sourceFile")
        return if (path.isNotBlank()) File(path) else null
    }

    fun sourceMime(context: Context, draftId: String): String? {
        val f = File(draftsDir(context), "$draftId.json")
        if (!f.exists()) return null
        return JSONObject(f.readText()).optString("sourceMime").ifBlank { null }
    }
}
