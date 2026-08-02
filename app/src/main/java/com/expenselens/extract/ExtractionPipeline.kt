package com.expenselens.extract

import android.content.Context
import com.expenselens.categorize.CategoryClassifier
import com.expenselens.categorize.KeywordCategoryClassifier
import com.expenselens.data.prefs.AppPreferences
import com.expenselens.domain.model.ExtractionResult
import com.expenselens.ocr.OcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Glues together: raw document -> text/image -> structured extraction.
 *
 * Strategy:
 *  1. Run [DocumentTextExtractor] to get text or bitmaps.
 *  2. If smart extraction is enabled, consented, and the user hasn't hit
 *     the monthly cap, ask the [LlmExtractor] to process the IMAGE (vision)
 *     — this is the primary path that recovers accurate vendor / total /
 *     line items / phone / FSSAI. For PDFs and DOCX where there is no
 *     image of the receipt, we fall back to the text-only path.
 *  3. The LLM result is preferred when it returns a non-zero total.
 *  4. Otherwise (or as a fallback) hand the OCR text to [ReceiptParser].
 *  5. The result is an [ExtractionResult] the user reviews before saving.
 */
class ExtractionPipeline(
    private val context: Context,
    private val ocr: OcrEngine,
    private val classifier: CategoryClassifier = KeywordCategoryClassifier(),
    private val llm: LlmExtractor? = null,
    private val preferences: AppPreferences? = null,
    /**
     * Lazy premium lookup. We don't snapshot at construction time because
     * the user can upgrade mid-session and we want subsequent extractions
     * to see the new cap. The function reads from DataStore on every call.
     */
    private val isPremium: () -> Boolean = { false }
) {

    private val parser = ReceiptParser(classifier)

    private suspend fun effectiveCap(): Int {
        val premium = runCatching { isPremium() }.getOrDefault(false)
        return if (premium) PREMIUM_MONTHLY_CAP else DEFAULT_FREE_CAP
    }

    suspend fun run(file: File, mime: String?): ExtractionResult = withContext(Dispatchers.IO) {
        val doc = DocumentTextExtractor.extract(context, file, mime)
        val text = when (doc) {
            is ExtractedDocument.TextOnly -> doc.text
            is ExtractedDocument.ImageSequence -> {
                val parts = mutableListOf<String>()
                for (bmp in doc.bitmaps) {
                    parts.add(ocr.recognize(bmp).text)
                }
                parts.joinToString("\n")
            }
        }

        if (!file.exists()) return@withContext parser.parse(text)

        val smart = shouldUseSmart()
        // Always run the on-device parser as a safety net — if anything in the
        // LLM path returns null or partial garbage, the parser still has the
        // best regex-based guess to fall back to.
        val parserResult = runCatching { parser.parse(text) }.getOrNull()

        if (smart && llm != null) {
            val llmResult = runCatching { llm.extractFromImage(file, mime, text) }.getOrNull()
            if (llmResult != null) {
                bumpCallCount()
                // If the LLM produced at least a vendor or a non-zero total, use it.
                if (llmResult.totalAmount > 0.0 || llmResult.vendor.isNotBlank()) {
                    return@withContext llmResult
                }
            }
            // Vision path produced nothing useful — try text-only LLM as a
            // second attempt using the OCR text.
            if (text.isNotBlank()) {
                val textResult = runCatching { llm.extract(text) }.getOrNull()
                if (textResult != null && (textResult.totalAmount > 0.0 || textResult.vendor.isNotBlank())) {
                    bumpCallCount()
                    return@withContext textResult
                }
            }
        }

        // Fall back to the on-device parser. If even that returned null, give
        // the user an empty result with the OCR text so the Review screen has
        // *something* to show.
        parserResult
            ?: ExtractionResult(
                rawText = text,
                billDate = java.time.LocalDate.now(),
                lineItems = emptyList()
            )
    }

    private suspend fun shouldUseSmart(): Boolean {
        val prefs = preferences ?: return false
        if (llm == null) return false
        val enabled = runCatching { prefs.smartEnabled.first() }.getOrDefault(false)
        if (!enabled) return false
        val consented = runCatching { prefs.smartConsent.first() }.getOrDefault(false)
        if (!consented) return false
        val (count, _) = currentUsage()
        return count < effectiveCap()
    }

    private suspend fun bumpCallCount() {
        val prefs = preferences ?: return
        val key = currentMonthKey()
        runCatching { prefs.incrementSmartCalls(key) }
    }

    private suspend fun currentUsage(): Pair<Int, String> {
        val prefs = preferences ?: return 0 to ""
        val calls = runCatching { prefs.smartCalls.first() }.getOrDefault(0)
        val month = runCatching { prefs.smartCallsMonth.first() }.getOrDefault("")
        val current = if (month == currentMonthKey()) calls else 0
        return current to currentMonthKey()
    }

    private fun currentMonthKey(): String =
        LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))

    companion object {
        /** Free tier: 10 smart-extraction calls per month. */
        const val DEFAULT_FREE_CAP = 10

        /** Premium tier: effectively unlimited (we still bump the counter for telemetry). */
        const val PREMIUM_MONTHLY_CAP = Int.MAX_VALUE
    }
}
