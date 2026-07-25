package com.expenselens.extract

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File

sealed class ExtractedDocument {
    data class TextOnly(val text: String, val pageCount: Int) : ExtractedDocument()
    data class ImageSequence(val bitmaps: List<Bitmap>) : ExtractedDocument()
}

object DocumentTextExtractor {

    /**
     * Detects mime type from extension and routes to the right text extractor.
     */
    suspend fun extract(context: Context, file: File, mime: String?): ExtractedDocument =
        withContext(Dispatchers.IO) {
            val resolvedMime = mime ?: guessMime(file)
            when {
                resolvedMime == "application/pdf" -> extractPdf(file)
                resolvedMime in setOf(
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                ) -> extractDocx(file)
                resolvedMime.startsWith("image/") -> {
                    val bmp = decodeBitmap(file) ?: return@withContext ExtractedDocument.TextOnly("", 0)
                    ExtractedDocument.ImageSequence(listOf(bmp))
                }
                else -> ExtractedDocument.TextOnly(file.readText().orEmpty(), 1)
            }
        }

    private fun guessMime(file: File): String = when (file.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "heic", "heif" -> "image/heic"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    private fun extractPdf(file: File): ExtractedDocument {
        // First try the text-stripping path. If the PDF is image-only, fall back
        // to rendering every page to a bitmap and letting OCR handle it.
        val text = try {
            PDDocument.load(file).use { doc ->
                PDFTextStripper().getText(doc)
            }
        } catch (t: Throwable) {
            ""
        }
        if (text.isNotBlank() && text.length > 30) {
            val pageCount = try {
                PDDocument.load(file).use { it.numberOfPages }
            } catch (t: Throwable) { 1 }
            return ExtractedDocument.TextOnly(text, pageCount)
        }
        // Image-only PDF: render pages to bitmaps for OCR.
        return renderPdfPages(file)
    }

    private fun renderPdfPages(file: File): ExtractedDocument {
        val bitmaps = mutableListOf<Bitmap>()
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(pfd).use { renderer ->
            val pages = renderer.pageCount.coerceAtMost(10)
            for (i in 0 until pages) {
                renderer.openPage(i).use { page ->
                    val width = (page.width * 1.5f).toInt().coerceAtLeast(800)
                    val height = (page.height * 1.5f).toInt().coerceAtLeast(800)
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmaps.add(bmp)
                }
            }
        }
        return ExtractedDocument.ImageSequence(bitmaps)
    }

    private fun extractDocx(file: File): ExtractedDocument {
        return try {
            XWPFDocument(file.inputStream()).use { doc ->
                XWPFWordExtractor(doc).use { ex ->
                    ExtractedDocument.TextOnly(ex.text.orEmpty(), 1)
                }
            }
        } catch (t: Throwable) {
            ExtractedDocument.TextOnly("", 0)
        }
    }

    private fun decodeBitmap(file: File): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    } catch (t: Throwable) {
        null
    }
}
