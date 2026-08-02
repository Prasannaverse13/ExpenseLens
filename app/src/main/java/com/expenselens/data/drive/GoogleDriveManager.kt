package com.expenselens.data.drive

import android.util.Log
import com.expenselens.data.auth.GoogleAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Direct Google Drive REST API v3 client. We don't pull in the full
 * google-api-services-drive artifact — the surface we need is small
 * (upload, list, download, delete) and HttpURLConnection is enough.
 *
 * All files live in the user's **appDataFolder** (the hidden, app-specific
 * folder Google exposes via the [com.google.api.services.drive.DriveScopes.DRIVE_FILE]
 * scope). The user's main Drive is never touched.
 */
class GoogleDriveManager(
    private val auth: GoogleAuthManager
) {

    /**
     * Upload a backup file. Returns the Drive file ID of the uploaded
     * backup, or null on failure.
     *
     * We use a multipart upload so we can set a filename + MIME type in
     * the same request.
     */
    suspend fun uploadBackup(
        filename: String,
        mimeType: String,
        content: ByteArray
    ): String? = withContext(Dispatchers.IO) {
        val token = auth.ensureFreshToken() ?: return@withContext null
        val boundary = "----ExpenseLensBoundary${UUID.randomUUID().toString().replace("-", "")}"

        val url = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            setFixedLengthStreamingMode(buildMultipartSize(boundary, filename, mimeType, content.size))
        }

        try {
            DataOutputStream(BufferedOutputStream(conn.outputStream)).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                out.writeBytes(buildMetadataJson(filename, mimeType).toString())
                out.writeBytes("\r\n--$boundary\r\n")
                out.writeBytes("Content-Type: $mimeType\r\n\r\n")
                out.write(content)
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }
            val code = conn.responseCode
            if (code in 200..299) {
                val body = conn.inputStream.readText()
                val id = JSONObject(body).optString("id")
                Log.i(TAG, "Uploaded $filename as Drive file $id")
                id.ifBlank { null }
            } else {
                val err = conn.errorStream?.readText().orEmpty()
                Log.e(TAG, "Upload failed ($code): $err")
                null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Upload threw: ${t.message}", t)
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * List the backup files in appDataFolder, newest first.
     * Returns Drive file metadata as [DriveFile] records.
     */
    suspend fun listBackups(): List<DriveFile> = withContext(Dispatchers.IO) {
        val token = auth.ensureFreshToken() ?: return@withContext emptyList()
        val q = "'appDataFolder' in parents and trashed = false"
        val fields = "files(id,name,createdTime,modifiedTime,size,mimeType)"
        val url = URL(
            "https://www.googleapis.com/drive/v3/files" +
                "?spaces=appDataFolder&q=${java.net.URLEncoder.encode(q, "UTF-8")}" +
                "&fields=${java.net.URLEncoder.encode(fields, "UTF-8")}" +
                "&orderBy=modifiedTime%20desc&pageSize=20"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "listBackups failed (${conn.responseCode}): ${conn.errorStream?.readText()}")
                return@withContext emptyList()
            }
            val body = conn.inputStream.readText()
            val arr = JSONObject(body).optJSONArray("files") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DriveFile(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    createdTime = o.optString("createdTime"),
                    modifiedTime = o.optString("modifiedTime"),
                    size = o.optLong("size", 0L),
                    mimeType = o.optString("mimeType")
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "listBackups threw: ${t.message}", t)
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    /** Download a file's content. */
    suspend fun downloadFile(fileId: String): ByteArray? = withContext(Dispatchers.IO) {
        val token = auth.ensureFreshToken() ?: return@withContext null
        val url = URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "downloadFile failed (${conn.responseCode}): ${conn.errorStream?.readText()}")
                return@withContext null
            }
            conn.inputStream.readBytes()
        } catch (t: Throwable) {
            Log.e(TAG, "downloadFile threw: ${t.message}", t)
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Delete a file. Returns true on success. */
    suspend fun deleteFile(fileId: String): Boolean = withContext(Dispatchers.IO) {
        val token = auth.ensureFreshToken() ?: return@withContext false
        val url = URL("https://www.googleapis.com/drive/v3/files/$fileId")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val ok = conn.responseCode in 200..299
            if (!ok) Log.w(TAG, "deleteFile failed (${conn.responseCode}): ${conn.errorStream?.readText()}")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "deleteFile threw: ${t.message}", t)
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun buildMetadataJson(filename: String, mimeType: String): JSONObject =
        JSONObject().apply {
            put("name", filename)
            put("mimeType", mimeType)
            put("parents", JSONArray().put("appDataFolder"))
        }

    private fun buildMultipartSize(
        boundary: String, filename: String, mimeType: String, contentLen: Int
    ): Int {
        val meta = buildMetadataJson(filename, mimeType).toString()
        val crlf = "\r\n".length
        val headers = ("--$boundary$crlf" +
            "Content-Type: application/json; charset=UTF-8$crlf$crlf" +
            meta +
            "$crlf--$boundary$crlf" +
            "Content-Type: $mimeType$crlf$crlf").length
        val footer = ("$crlf--$boundary--$crlf").length
        return headers + contentLen + footer
    }

    private fun InputStream.readText(): String =
        bufferedReader(Charsets.UTF_8).use { it.readText() }

    data class DriveFile(
        val id: String,
        val name: String,
        val createdTime: String,
        val modifiedTime: String,
        val size: Long,
        val mimeType: String
    )

    companion object {
        private const val TAG = "GoogleDriveManager"
    }
}
