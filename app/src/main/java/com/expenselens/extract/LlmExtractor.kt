package com.expenselens.extract

import com.expenselens.domain.model.ExtractionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pluggable LLM extractor. Sends the raw OCR text to an OpenAI-compatible
 * chat completions endpoint and asks the model to return a strict JSON
 * structure. Kept as a stub so the default build doesn't talk to the network.
 *
 * To enable: set the endpoint + key in DataStore, then bind this class
 * instead of the [ReceiptParser] in [com.expenselens.di.AppModule].
 */
class LlmExtractor(
    private val endpoint: String,
    private val apiKey: String,
    private val call: (String, String, String) -> String = ::defaultHttpCall
) {

    suspend fun extract(rawText: String): ExtractionResult? = withContext(Dispatchers.IO) {
        if (endpoint.isBlank() || apiKey.isBlank()) return@withContext null
        val system = """
            You are a receipt parser. Read the OCR text and return ONLY this JSON schema:
            {"vendor":"","billNumber":"","billDate":"YYYY-MM-DD",
             "totalAmount":0.0,"taxAmount":0.0,"currency":"INR",
             "lineItems":[{"description":"","quantity":1.0,"unitPrice":0.0,"lineTotal":0.0}]}
        """.trimIndent()
        val user = "OCR text:\n```\n$rawText\n```"
        val raw = runCatching { call(endpoint, apiKey, "$system\n\n$user") }
            .getOrElse { return@withContext null }
        runCatching { parse(raw, rawText) }.getOrNull()
    }

    private fun parse(raw: String, fallbackText: String): ExtractionResult {
        val obj = JSONObject(raw)
        val items = obj.optJSONArray("lineItems")
        val list = if (items != null) (0 until items.length()).map { i ->
            val li = items.getJSONObject(i)
            com.expenselens.domain.model.LineItem(
                description = li.optString("description", ""),
                quantity = li.optDouble("quantity", 1.0),
                unitPrice = li.optDouble("unitPrice", 0.0),
                lineTotal = li.optDouble("lineTotal", 0.0)
            )
        } else emptyList()
        val date = runCatching { java.time.LocalDate.parse(obj.optString("billDate")) }
            .getOrElse { java.time.LocalDate.now() }
        return ExtractionResult(
            vendor = obj.optString("vendor", ""),
            billNumber = obj.optString("billNumber").ifBlank { null },
            billDate = date,
            totalAmount = obj.optDouble("totalAmount", 0.0),
            taxAmount = obj.optDouble("taxAmount", 0.0).takeIf { it > 0.0 },
            currency = obj.optString("currency", "INR"),
            rawText = fallbackText,
            lineItems = list,
            fieldConfidences = mapOf("llm" to 0.9f)
        )
    }

    companion object {
        fun defaultHttpCall(endpoint: String, apiKey: String, prompt: String): String {
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            val body = JSONObject().apply {
                put("model", "gpt-4o-mini")
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().put("role", "user").put("content", prompt))
                })
                put("temperature", 0.0)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val resp = JSONObject(text)
            return resp.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content", "")
        }
    }
}
