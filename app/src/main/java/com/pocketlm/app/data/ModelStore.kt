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

    suspend fun list(): List<LocalModel> = ggufFiles { !it.contains("mmproj", ignoreCase = true) }

    /** Vision add-ons ("mmproj" files), kept apart from loadable models. */
    suspend fun listProjectors(): List<LocalModel> = ggufFiles { it.contains("mmproj", ignoreCase = true) }

    private suspend fun ggufFiles(accept: (String) -> Boolean): List<LocalModel> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") && accept(f.name) }
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
    suspend fun download(url: String, fileName: String, onProgress: (Long, Long) -> Unit) =
        Downloader.download(url, File(dir, fileName), File(dir, "$fileName.part"), onProgress)

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
