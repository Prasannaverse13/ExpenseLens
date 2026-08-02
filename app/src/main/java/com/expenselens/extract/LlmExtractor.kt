package com.expenselens.extract

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.expenselens.domain.model.ExtractionResult
import com.expenselens.domain.model.LineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends the actual receipt IMAGE to an OpenAI-compatible Vision chat
 * completions endpoint and parses the model's strict-JSON reply into an
 * [ExtractionResult].
 *
 * Pipeline:
 *   1. Resolve the image (file or uri) → downscale to <=1024px JPEG → base64.
 *   2. POST to the vision endpoint with a multimodal message
 *      (text prompt + image_url).
 *   3. Parse the JSON reply.
 *
 * The class never logs the key, never exposes the provider to UI code, and
 * gracefully returns null on any failure so the caller can fall back to the
 * on-device parser.
 */
class LlmExtractor(
    private val context: Context,
    private val endpoint: String,
    private val apiKey: String,
    private val model: String = "gpt-4o",
    private val call: (
        endpoint: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        base64Image: String
    ) -> String = ::defaultVisionCall
) {

    /**
     * Extract structured data from a receipt image on disk. Returns null
     * if the endpoint is unconfigured, the call fails, or the response
     * cannot be parsed.
     */
    suspend fun extractFromImage(
        file: File,
        mime: String?,
        ocrFallbackText: String = ""
    ): ExtractionResult? = withContext(Dispatchers.IO) {
        if (endpoint.isBlank() || apiKey.isBlank()) {
            Log.w(TAG, "extractFromImage: endpoint or api key is blank — skipping LLM")
            return@withContext null
        }
        val b64 = runCatching { encodeImage(file, mime) }
            .onFailure { Log.e(TAG, "encodeImage failed: ${it.message}") }
            .getOrNull() ?: return@withContext null
        val system = buildSystemPrompt()
        val user = buildUserPrompt(ocrFallbackText)
        Log.d(TAG, "POST $endpoint model=$model bytes=${b64.length}")
        val raw = runCatching { call(endpoint, apiKey, model, system, user, b64) }
            .onFailure { Log.e(TAG, "LLM call threw: ${it.message}") }
            .getOrElse { return@withContext null }
        Log.d(TAG, "LLM response length=${raw.length} head=${raw.take(120)}")
        runCatching { parse(raw, ocrFallbackText) }
            .onFailure { Log.e(TAG, "parse failed: ${it.message}", it) }
            .getOrNull()
    }

    /** Backwards-compat: text-only path for the parser. */
    suspend fun extract(rawText: String): ExtractionResult? = withContext(Dispatchers.IO) {
        if (endpoint.isBlank() || apiKey.isBlank()) return@withContext null
        if (rawText.isBlank()) return@withContext null
        val system = buildSystemPrompt()
        val user = buildUserPrompt(rawText)
        val raw = runCatching { call(endpoint, apiKey, model, system, user, "") }
            .onFailure { Log.e(TAG, "LLM text call threw: ${it.message}") }
            .getOrElse { return@withContext null }
        runCatching { parse(raw, rawText) }.getOrNull()
    }

    private fun buildSystemPrompt(): String {
        val today = java.time.LocalDate.now()
        val twoDigitYear = today.year % 100
        return """
        You are an expert receipt and invoice parser.
        You will be given an image of a receipt (and optionally some pre-extracted OCR text).
        Read every detail visible in the image and return ONLY a JSON object
        matching this exact schema — no commentary, no markdown fences:

        {
          "vendor": "store or merchant name",
          "phone": ["phone1", "phone2"],
          "email": "",
          "fssaiNumber": "",
          "billNumber": "bill/invoice number",
          "billDate": "YYYY-MM-DD",
          "visitTime": "HH:MM",
          "currency": "INR",
          "paymentMethod": "CASH | UPI | CARD | NET_BANKING | WALLET | OTHER",
          "items": [
            { "name": "", "qty": 1, "price": 0.0, "amount": 0.0 }
          ],
          "subtotal": 0.0,
          "taxAmount": 0.0,
          "total": 0.0,
          "category": "Groceries | Fuel | Restaurant | Medicine | Travel | Shopping | Education | Utilities | Entertainment | Others"
        }

        Rules:
        - vendor: the store / merchant name printed on the receipt header.
        - phone: list every phone number you can find.
        - billNumber: invoice / bill / receipt number.
        - billDate: ALWAYS output a 4-digit year in YYYY-MM-DD format. The
          current year is ${today.year} and the current month is ${"%02d".format(today.monthValue)}.
          If the receipt only shows dd/mm/yy, interpret yy=${twoDigitYear} as
          ${today.year}. If the receipt date is in the future by more than 1 day
          or in the past by more than 3 years, fall back to today.
        - visitTime: HH:MM in 24-hour format, or "" if not shown.
        - currency: 3-letter code (default INR if rupee symbol visible).
        - paymentMethod: best guess from text on the receipt.
        - items: every line item with name, qty (number), price (per unit), amount (line total).
        - subtotal / taxAmount / total: numbers. Use 0.0 if you cannot read them.
        - category: pick the single closest match from the list.
        - Use null (not empty string) for fields you cannot find.
    """.trimIndent()
    }

    private fun buildUserPrompt(ocr: String): String = if (ocr.isBlank()) {
        "Parse this receipt image and return the JSON."
    } else {
        "Here is OCR text already extracted from the receipt (may have errors):\n" +
            "```\n$ocr\n```\n" +
            "Use the IMAGE as the source of truth — only fall back to this text " +
            "for fields you genuinely cannot see in the image. Return the JSON."
    }

    @Suppress("UNUSED_PARAMETER")
    private fun encodeImage(file: File, mime: String?): String {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val (w, h) = opts.outWidth to opts.outHeight
        var sample = 1
        val maxDim = 1536
        while (w / sample > maxDim || h / sample > maxDim) sample *= 2
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            ?: error("Could not decode image: ${file.absolutePath}")
        val scaled = if (bmp.width > maxDim || bmp.height > maxDim) {
            val ratio = maxDim.toFloat() / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * ratio).toInt(),
                (bmp.height * ratio).toInt(),
                true
            )
        } else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (scaled !== bmp) bmp.recycle()
        scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun parse(raw: String, fallbackText: String): ExtractionResult {
        val jsonText = stripCodeFence(raw)
        val obj = JSONObject(jsonText)

        val itemsJson = obj.optJSONArray("items")
        val items = if (itemsJson != null) (0 until itemsJson.length()).map { i ->
            val li = itemsJson.getJSONObject(i)
            LineItem(
                description = li.optString("name", "").ifBlank { "Item" },
                quantity = li.optDouble("qty", 1.0),
                unitPrice = li.optDouble("price", 0.0),
                lineTotal = li.optDouble("amount", 0.0)
            )
        } else emptyList()

        val rawDate = runCatching { java.time.LocalDate.parse(obj.optString("billDate")) }
            .getOrElse { java.time.LocalDate.now() }
        // Guard: if the LLM returned a date more than 3 years in the past
        // (e.g. misinterpreted "26" as 2026 but the LLM ran on a mis-configured
        // device) or more than 1 day in the future, snap to today so the row
        // actually lands in "This month" on the dashboard.
        val date = normalizeBillDate(rawDate)

        val total = obj.optDouble("total", 0.0)
        val tax = obj.optDouble("taxAmount", 0.0).takeIf { it > 0.0 }

        // phones — accept either an array or a single string
        val phoneList: List<String> = when (val ph = obj.opt("phone")) {
            is JSONArray -> (0 until ph.length()).mapNotNull { ph.optString(it).ifBlank { null } }
            is String -> ph.split(',', ' ').map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }

        val visitTime = obj.optString("visitTime").ifBlank { null }
        val fssai = obj.optString("fssaiNumber").ifBlank { null }
        val email = obj.optString("email").ifBlank { null }
        val itemCount = if (obj.isNull("itemCount")) items.size
        else obj.optInt("itemCount").takeIf { it > 0 } ?: items.size

        val metadata = com.expenselens.domain.model.ExpenseMetadata(
            merchantPhone = phoneList,
            merchantEmail = email,
            fssaiNumber = fssai,
            visitTime = visitTime,
            itemCount = itemCount,
            source = "llm-vision"
        )

        return ExtractionResult(
            vendor = obj.optString("vendor", "").trim(),
            billNumber = obj.optString("billNumber").ifBlank { null },
            billDate = date,
            totalAmount = total,
            taxAmount = tax,
            currency = obj.optString("currency", "INR"),
            rawText = fallbackText,
            lineItems = items,
            fieldConfidences = mapOf("llm" to 0.9f, "vision" to 0.95f),
            metadata = metadata
        )
    }

    /**
     * If the parsed date is more than 3 years in the past or 1 day in the
     * future, replace the year with the current year (keeping day/month).
     * This protects against the LLM (or the device clock) misinterpreting
     * 2-digit years like "26" → 2026 and a bad clock defaulting to 2022.
     */
    private fun normalizeBillDate(d: java.time.LocalDate): java.time.LocalDate {
        val today = java.time.LocalDate.now()
        return when {
            d.isAfter(today.plusDays(1)) -> {
                runCatching { d.withYear(today.year) }.getOrDefault(today)
            }
            d.isBefore(today.minusYears(3)) -> {
                runCatching { d.withYear(today.year) }.getOrDefault(today)
            }
            else -> d
        }
    }

    private fun stripCodeFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstNewline = trimmed.indexOf('\n')
        val withoutOpen = if (firstNewline > 0) trimmed.substring(firstNewline + 1) else trimmed
        return withoutOpen.trimEnd().removeSuffix("```").trimEnd()
    }

    companion object {

        private const val TAG = "LlmExtractor"

        /**
         * Default HTTP call: POST a multimodal chat-completions request with
         * a system prompt, a user prompt, and a base64 image_url. Reads the
         * error stream on non-2xx responses so we never silently swallow a
         * 401 / 400 / 429 from the provider.
         */
        fun defaultVisionCall(
            endpoint: String,
            apiKey: String,
            model: String,
            systemPrompt: String,
            userPrompt: String,
            base64Image: String
        ): String {
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = 90_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true

            val userContent = JSONArray().apply {
                // Text part
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", userPrompt)
                })
                // Image part (if provided)
                if (base64Image.isNotEmpty()) {
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$base64Image")
                            put("detail", "high")
                        })
                    })
                }
            }

            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", userContent))
                })
                put("temperature", 0.0)
                put("max_tokens", 2000)
                put("response_format", JSONObject().put("type", "json_object"))
            }

            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                Log.e(TAG, "HTTP $code from $endpoint: ${text.take(400)}")
                throw RuntimeException("LLM HTTP $code: ${text.take(200)}")
            }
            val resp = runCatching { JSONObject(text) }.getOrElse {
                Log.e(TAG, "Non-JSON response: ${text.take(200)}")
                throw RuntimeException("LLM returned non-JSON: ${text.take(120)}")
            }
            val content = resp.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content", "")
            if (content.isBlank()) {
                Log.w(TAG, "Empty content in LLM response: ${text.take(400)}")
            }
            return content
        }
    }
}
