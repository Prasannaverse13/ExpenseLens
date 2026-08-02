package com.expenselens.ui.common

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shared helper for sharing a generated export file (CSV / XLSX / PDF) via
 * the system share sheet. Uses FileProvider so the receiving app can read
 * the file from app-private storage.
 */
fun shareFile(context: Context, file: File, mime: String, chooserTitle: String = "Share export") {
    val uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, chooserTitle))
}
