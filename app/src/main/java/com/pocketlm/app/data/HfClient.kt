package com.pocketlm.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class HfModel(val id: String, val downloads: Long, val likes: Long)

data class HfFile(val path: String, val sizeBytes: Long) {
    val fileName: String get() = path.substringAfterLast('/')

    /** A vision add-on ("mmproj") that must be paired with a model, not a model by itself. */
    val isProjector: Boolean get() = fileName.contains("mmproj", ignoreCase = true)

    /** Quantisation label such as Q4_K_M, taken from the file name when present. */
    val quant: String?
        get() = QUANT_REGEX.find(fileName)?.value?.uppercase()

    private companion object {
        val QUANT_REGEX = Regex("""(?i)(IQ\d_[A-Z]+|Q\d_[A-Z0-9_]+|Q\d|F16|F32|BF16)""")
    }
}

/** Minimal client for the public Hugging Face model API. */
object HfClient {
    private const val BASE = "https://huggingface.co"

    // Split models (…-00001-of-00003.gguf) can't be used on their own.
    private val SPLIT = Regex("""-\d{5}-of-\d{5}\.gguf$""")

    suspend fun search(query: String): List<HfModel> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val body = get("$BASE/api/models?search=$q&filter=gguf&sort=downloads&direction=-1&limit=25")
        val arr = JSONArray(body)
        List(arr.length()) {
            val o = arr.getJSONObject(it)
            HfModel(
                id = o.optString("id", o.optString("modelId")),
                downloads = o.optLong("downloads"),
                likes = o.optLong("likes"),
            )
        }.filter { it.id.isNotEmpty() }
    }

    /** Lists the single-file GGUF models in a repository, smallest first. */
    suspend fun files(repoId: String): List<HfFile> = withContext(Dispatchers.IO) {
        val arr = JSONArray(get("$BASE/api/models/$repoId/tree/main"))
        val files = ArrayList<HfFile>()
        for (i in 0 until arr.length()) {
            val o: JSONObject = arr.getJSONObject(i)
            val path = o.optString("path")
            if (o.optString("type") != "file" || !path.endsWith(".gguf")) continue
            if (SPLIT.containsMatchIn(path)) continue
            val size = o.optJSONObject("lfs")?.optLong("size") ?: o.optLong("size")
            files += HfFile(path, size)
        }
        files.sortedWith(compareBy({ it.isProjector }, { it.sizeBytes }))
    }

    fun downloadUrl(repoId: String, path: String) = "$BASE/$repoId/resolve/main/$path"

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("User-Agent", "PocketLM")
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> return conn.inputStream.bufferedReader().use { it.readText() }
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN ->
                    throw IOException("This model needs a Hugging Face login")
                HttpURLConnection.HTTP_NOT_FOUND -> throw IOException("Not found")
                else -> throw IOException("Hugging Face returned HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }
}
