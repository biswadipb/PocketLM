package com.pocketlm.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Extra models for the catalog, read from a file in PocketLM's repository, so new models can appear without a new
 * version of the app. The file is untrusted: only plain, well-formed entries with Hugging Face or ModelScope
 * download links are accepted, and anything else is ignored.
 */
object RemoteCatalog {
    const val URL = "https://raw.githubusercontent.com/biswadipb/PocketLM/main/catalog/models.json"

    private const val SUPPORTED_VERSION = 1
    private const val MAX_ENTRIES = 60
    private val ALLOWED_HOSTS = setOf("huggingface.co", "modelscope.cn", "www.modelscope.cn")
    private val FILE_NAME = Regex("""[A-Za-z0-9._-]{1,120}\.gguf""")

    class Parsed(val models: List<CatalogModel>, val embeddings: List<CatalogModel>, val skipped: Int)

    /** The entries of a catalog file, or null if [json] is not a catalog this version understands. */
    fun parse(json: String): Parsed? {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        if (root.optInt("version", 0) !in 1..SUPPORTED_VERSION) return null
        var skipped = 0
        fun read(key: String): List<CatalogModel> {
            val array = root.optJSONArray(key) ?: return emptyList()
            val out = ArrayList<CatalogModel>()
            for (i in 0 until minOf(array.length(), MAX_ENTRIES)) {
                val entry = array.optJSONObject(i)?.let(::entry)
                if (entry == null) skipped++ else out += entry
            }
            return out
        }
        return Parsed(read("models"), read("embeddings"), skipped)
    }

    private fun entry(o: JSONObject): CatalogModel? {
        val name = o.optString("name").trim()
        val fileName = o.optString("fileName")
        val url = o.optString("url")
        val size = o.optInt("sizeMb", -1)
        if (name.isEmpty() || name.length > 80 || !FILE_NAME.matches(fileName) || !allowedUrl(url) || size !in 1..200_000) return null
        val projectorFile = o.optString("projectorFileName").ifEmpty { null }
        val projectorUrl = o.optString("projectorUrl").ifEmpty { null }
        // A vision add-on needs both its name and its link, held to the same rules.
        if ((projectorFile == null) != (projectorUrl == null)) return null
        if (projectorFile != null && (!FILE_NAME.matches(projectorFile) || !allowedUrl(projectorUrl!!))) return null
        return CatalogModel(name, o.optString("description").trim().take(200), fileName, url, size, projectorFile, projectorUrl)
    }

    /** True for an https link to a model file on a site this catalog trusts. */
    fun allowedUrl(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return false
        }
        return uri.scheme == "https" && uri.host in ALLOWED_HOSTS && uri.userInfo == null && uri.port == -1 &&
            uri.path.orEmpty().contains("/resolve/") && uri.path.orEmpty().endsWith(".gguf")
    }

    /** [builtIn] followed by the [extra] models it doesn't already have; an extra entry with the same file name replaces the built-in one. */
    fun merge(builtIn: List<CatalogModel>, extra: List<CatalogModel>): List<CatalogModel> {
        val replaced = extra.associateBy { it.fileName }
        val kept = builtIn.map { replaced[it.fileName] ?: it }
        val added = extra.filter { e -> builtIn.none { it.fileName == e.fileName } }.distinctBy { it.fileName }
        return kept + added
    }
}

/** Keeps the last catalog file that was fetched, so it is there offline, and asks for a fresh one now and then. */
class CatalogStore(context: Context) {
    private val file = File(context.filesDir, "catalog.json")
    private val etagFile = File(context.filesDir, "catalog.etag")

    /** The saved catalog, or null if none was fetched yet. */
    fun cached(): RemoteCatalog.Parsed? = runCatching { RemoteCatalog.parse(file.readText()) }.getOrNull()

    /** True if it has been a while since the file was fetched. */
    fun due(nowMs: Long = System.currentTimeMillis()): Boolean = !file.exists() || nowMs - file.lastModified() > REFRESH_AFTER_MS

    /** Fetches the catalog. Returns the fresh one, or null if nothing changed or it could not be fetched. */
    suspend fun refresh(address: String = RemoteCatalog.URL): RemoteCatalog.Parsed? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(address).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("User-Agent", "PocketLM")
                runCatching { etagFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() && file.exists() }
                    ?.let { connection.setRequestProperty("If-None-Match", it) }
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> {
                        file.setLastModified(System.currentTimeMillis())
                        null
                    }
                    HttpURLConnection.HTTP_OK -> {
                        val out = ByteArrayOutputStream()
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(8 * 1024)
                            while (out.size() <= MAX_BYTES) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                            }
                        }
                        if (out.size() > MAX_BYTES) return@withContext null
                        val text = out.toString(Charsets.UTF_8.name())
                        val parsed = RemoteCatalog.parse(text) ?: return@withContext null
                        file.writeText(text)
                        etagFile.writeText(connection.getHeaderField("ETag").orEmpty())
                        parsed
                    }
                    else -> null
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val MAX_BYTES = 256 * 1024
        const val REFRESH_AFTER_MS = 12L * 60 * 60 * 1000
    }
}
