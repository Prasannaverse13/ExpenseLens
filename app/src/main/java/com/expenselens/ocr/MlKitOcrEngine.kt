package com.expenselens.ocr

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MlKitOcrEngine(@Suppress("UNUSED_PARAMETER") context: Context) : OcrEngine {

    override val name: String = "mlkit"

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(bitmap: Bitmap): OcrResult =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val text = result.text
                    // ML Kit doesn't expose a clean per-document confidence;
                    // approximate from average block confidence when present,
                    // else fall back to text-length heuristic.
                    val confidences = result.textBlocks.mapNotNull { block ->
                        val lines = block.lines
                        if (lines.isEmpty()) null
                        else lines.map { it.confidence ?: 0.5f }.average().toFloat()
                    }
                    val confidence = if (confidences.isNotEmpty())
                        confidences.average().toFloat()
                    else if (text.length < 20) 0.3f else 0.8f
                    cont.resume(OcrResult(text, confidence))
                }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
}
