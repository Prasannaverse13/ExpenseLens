package com.expenselens.ocr

import android.graphics.Bitmap

/**
 * Abstraction over an OCR engine. The pipeline tries the primary engine first
 * and falls back to the wrapped secondary if confidence is low.
 */
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): OcrResult
    val name: String
}

data class OcrResult(
    val text: String,
    val confidence: Float
)
