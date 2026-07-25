package com.expenselens.ocr

import android.content.Context
import android.graphics.Bitmap
import cz.adaptech.tesseract4android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tesseract4Android fallback engine. Used when ML Kit is unavailable or returns
 * low confidence. The actual tessdata file must be bundled under
 * app/src/main/assets/tessdata/eng.traineddata — the [FallbackOcrEngine]
 * simply forwards to the wrapped primary if Tesseract is not initialized.
 */
class TesseractOcrEngine private constructor(
    private val api: TessBaseAPI?,
    private val wrapped: OcrEngine?
) : OcrEngine {

    override val name: String = "tesseract"

    override suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.IO) {
        val tess = api
        if (tess == null) {
            // Not initialized — fall back to wrapped primary.
            return@withContext wrapped?.recognize(bitmap) ?: OcrResult("", 0f)
        }
        tess.setImage(bitmap)
        val text = tess.utF8Text ?: ""
        // Tesseract mean confidence is 0..100
        val conf = (tess.meanConfidence().toFloat() / 100f).coerceIn(0f, 1f)
        OcrResult(text, conf)
    }

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun fallbackFrom(primary: OcrEngine, context: Context): OcrEngine {
            // Attempt to initialize Tesseract. If assets/tessdata/eng.traineddata
            // is not present, skip and just expose the primary directly.
            return try {
                val api = TessBaseAPI()
                // The app should ship eng.traineddata under assets/tessdata.
                val ok = api.init(context.filesDir.absolutePath, "eng")
                if (ok) TesseractOcrEngine(api, primary)
                else TesseractOcrEngine(null, primary)
            } catch (t: Throwable) {
                TesseractOcrEngine(null, primary)
            }
        }
    }
}
