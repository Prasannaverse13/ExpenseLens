package com.expenselens.ocr

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wraps a primary OCR engine with a secondary fallback. If the primary
 * returns a result below [threshold], we re-run the bitmap through the
 * secondary engine and merge results.
 */
class FallbackOcrEngine(
    private val primary: OcrEngine,
    private val secondary: OcrEngine,
    private val threshold: Float = 0.55f
) : OcrEngine {

    override val name: String = "${primary.name}+${secondary.name}"

    override suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        val first = primary.recognize(bitmap)
        if (first.confidence >= threshold || first.text.length > 80) {
            first
        } else {
            val second = secondary.recognize(bitmap)
            if (second.text.length > first.text.length) second else first
        }
    }
}
