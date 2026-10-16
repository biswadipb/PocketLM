package com.pocketlm.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class LocalModel(val fileName: String, val path: String, val sizeBytes: Long)

/** Manages GGUF files in the app's private storage. */
class ModelStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    suspend fun list(): List<LocalModel> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
            .orEmpty()
            .sortedBy { it.name.lowercase() }
            .map { LocalModel(it.name, it.absolutePath, it.length()) }
    }

    suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        File(dir, fileName).delete()
    }

    /**
     * Downloads [url] to the models folder, resuming a partial download when the server allows it.
     * [onProgress] receives (bytesDone, totalBytes); total is -1 when unknown.
     */
    suspend fun download(
        url: String,
        fileName: String,
        onProgress: (Long, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val target = File(dir, fileName)
        val partial = File(dir, "$fileName.part")
        var offset = if (partial.exists()) partial.length() else 0L

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
            conn.connect()

            when (conn.responseCode) {
                HttpURLConnection.HTTP_OK -> offset = 0L // server ignored Range; start over
                HttpURLConnection.HTTP_PARTIAL -> Unit
                else -> throw IOException("Download failed: HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.let { if (it < 0) -1L else it + offset }

            conn.inputStream.use { input ->
                java.io.FileOutputStream(partial, offset > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = offset
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) {
                            lastReport = now
                            onProgress(done, total)
                        }
                    }
                    onProgress(done, total)
                }
            }
        } finally {
            conn.disconnect()
        }
        if (!partial.renameTo(target)) throw IOException("Could not save $fileName")
    }

    /** Copies a model picked with the system file picker into private storage. */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: "imported-${System.currentTimeMillis()}.gguf"
        val safeName = File(name).name.let { if (it.endsWith(".gguf")) it else "$it.gguf" }
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open the selected file")
        input.use { src -> File(dir, safeName).outputStream().use { src.copyTo(it) } }
        safeName
    }
}
