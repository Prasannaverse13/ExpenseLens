package com.expenselens.data.storage

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object BillStorage {

    private const val BILLS_DIR = "bills"

    fun billsDir(context: Context): File {
        val dir = File(context.filesDir, BILLS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun exportsDir(context: Context): File {
        val dir = File(context.filesDir, "exports")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Copies the picked content into app-private storage so we keep a stable copy
     * even after the user revokes the source URI permission.
     */
    suspend fun persistCopy(context: Context, src: Uri, suggestedName: String?): File =
        withContext(Dispatchers.IO) {
            val ext = when (context.contentResolver.getType(src)) {
                "application/pdf" -> "pdf"
                "application/msword" -> "doc"
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
                "image/heic", "image/heif" -> "heic"
                else -> "img"
            }
            val file = File(billsDir(context), "${UUID.randomUUID()}.${suggestedName?.substringAfterLast('.', ext) ?: ext}")
            context.contentResolver.openInputStream(src)?.use { input ->
                FileOutputStream(file).use { out -> input.copyTo(out) }
            } ?: error("Could not open URI: $src")
            file
        }

    fun fileFor(context: Context, uriString: String): File = File(uriString)
}
