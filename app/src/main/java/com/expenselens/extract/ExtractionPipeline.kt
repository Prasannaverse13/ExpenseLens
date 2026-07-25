package com.expenselens.extract

import android.content.Context
import android.graphics.Bitmap
import com.expenselens.categorize.CategoryClassifier
import com.expenselens.categorize.KeywordCategoryClassifier
import com.expenselens.domain.model.ExtractionResult
import com.expenselens.ocr.OcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Glues together: raw document -> text -> structured extraction.
 *
 * Flow:
 * 1. [DocumentTextExtractor] produces either plain text or a sequence of bitmaps.
 * 2. For text, hand it straight to the parser.
 * 3. For bitmaps, run them through OCR first, then parser.
 * 4. The result is an [ExtractionResult] the user reviews before saving.
 */
class ExtractionPipeline(
    private val context: Context,
    private val ocr: OcrEngine,
    private val classifier: CategoryClassifier = KeywordCategoryClassifier(),
    private val llm: LlmExtractor? = null
) {

    private val parser = ReceiptParser(classifier)

    suspend fun run(file: File, mime: String?): ExtractionResult = withContext(Dispatchers.IO) {
        val doc = DocumentTextExtractor.extract(context, file, mime)
        when (doc) {
            is ExtractedDocument.TextOnly -> {
                // If we have a configured LLM, ask it to structure the text.
                llm?.extract(doc.text)?.let { return@withContext it }
                parser.parse(doc.text)
            }
            is ExtractedDocument.ImageSequence -> {
                val text = doc.bitmaps.joinToString("\n") { ocrText(it) }
                parser.parse(text)
            }
        }
    }

    private suspend fun ocrText(bitmap: Bitmap): String {
        val res = ocr.recognize(bitmap)
        return res.text
    }
}
