package com.pocketlm.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A repository (or, on Kaggle, one model variation) that can be searched for and expanded into files.
 * [downloads] is -1 when the source does not report it. [title] is shown instead of [id] when set.
 */
data class HfModel(val id: String, val downloads: Long, val likes: Long, val title: String? = null)

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

/** Where models can be searched for and downloaded from. */
enum class HubSource(val label: String) {
    HuggingFace("Hugging Face"),
    ModelScope("ModelScope"),
    Kaggle("Kaggle"),
    Mirror("Custom address"),
}

/** One page of search results. [next] is what to pass to ask for the following page, or null when this was the last. */
data class SearchPage(val results: List<HfModel>, val next: String? = null)

/** A model repository with search, a file listing, and direct download links. */
interface ModelHub {
    suspend fun search(query: String): List<HfModel>

    /** One page of results. Hubs that return everything in one go ignore [cursor] and have no next page. */
    suspend fun searchPage(query: String, cursor: String? = null): SearchPage = SearchPage(search(query))

    /** The single-file GGUF models (and vision add-ons) in a repository, smallest first. */
    suspend fun files(repoId: String): List<HfFile>

    fun downloadUrl(repoId: String, path: String): String

    companion object {
        /**
         * The hub for [source]. [mirrorUrl] is the address of a Hugging Face compatible server, such as a
         * regional mirror or one on your own network.
         */
        fun create(source: HubSource, mirrorUrl: String): ModelHub = when (source) {
            HubSource.HuggingFace -> HfHub("https://huggingface.co")
            HubSource.ModelScope -> ModelScopeHub()
            HubSource.Kaggle -> KaggleHub()
            HubSource.Mirror -> HfHub(normaliseAddress(mirrorUrl) ?: throw IOException("Enter the mirror's address first"))
        }

        /** "hf-mirror.com/" becomes "https://hf-mirror.com"; null when [input] is blank or not a web address. */
        fun normaliseAddress(input: String): String? {
            val raw = input.trim()
            if (raw.isEmpty()) return null
            val withScheme = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
            val host = runCatching { URL(withScheme).host }.getOrNull().orEmpty()
            // A real host has a dot (example.com, 192.168.1.5) or is "localhost"; this rejects "https://" and "foo".
            if (!host.contains('.') && host != "localhost") return null
            return withScheme.trimEnd('/')
        }

        // Split models (…-00001-of-00003.gguf) can't be used on their own.
        private val SPLIT = Regex("""-\d{5}-of-\d{5}\.gguf$""")

        /** Keeps usable GGUF entries and orders them: models first, then add-ons, each smallest first. */
        fun usableFiles(entries: List<HfFile>): List<HfFile> =
            entries.filter { it.path.endsWith(".gguf") && !SPLIT.containsMatchIn(it.path) }
                .sortedWith(compareBy({ it.isProjector }, { it.sizeBytes }))
    }
}

internal object Http {
    fun request(url: String, method: String = "GET", body: String? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.setRequestProperty("User-Agent", "PocketLM")
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> return conn.inputStream.bufferedReader().use { it.readText() }
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN ->
                    throw IOException("This model needs a login")
                HttpURLConnection.HTTP_NOT_FOUND -> throw IOException("Not found")
                else -> throw IOException("The server returned HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }
}

/** Hugging Face, or any server that speaks its API (mirrors, self-hosted copies). */
class HfHub(private val base: String) : ModelHub {

    override suspend fun search(query: String): List<HfModel> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        parseSearch(Http.request("$base/api/models?search=$q&filter=gguf&sort=downloads&direction=-1&limit=25"))
    }

    override suspend fun files(repoId: String): List<HfFile> = withContext(Dispatchers.IO) {
        parseFiles(Http.request("$base/api/models/$repoId/tree/main"))
    }

    override fun downloadUrl(repoId: String, path: String) = "$base/$repoId/resolve/main/$path"

    companion object {
        fun parseSearch(json: String): List<HfModel> {
            val arr = JSONArray(json)
            return List(arr.length()) {
                val o = arr.getJSONObject(it)
                HfModel(o.optString("id", o.optString("modelId")), o.optLong("downloads"), o.optLong("likes"))
            }.filter { it.id.isNotEmpty() }
        }

        fun parseFiles(json: String): List<HfFile> {
            val arr = JSONArray(json)
            val entries = ArrayList<HfFile>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("type") != "file") continue
                val size = o.optJSONObject("lfs")?.optLong("size") ?: o.optLong("size")
                entries += HfFile(o.optString("path"), size)
            }
            return ModelHub.usableFiles(entries)
        }
    }
}

/** ModelScope (modelscope.cn), a large model hub that is easy to reach from mainland China. */
class ModelScopeHub : ModelHub {

    override suspend fun search(query: String): List<HfModel> = withContext(Dispatchers.IO) {
        val name = query.trim().let { if (it.contains("gguf", ignoreCase = true)) it else "$it gguf" }
        val body = JSONObject().put("PageSize", 20).put("PageNumber", 1).put("SortBy", "Default")
            .put("Target", "").put("Name", name).toString()
        parseSearch(Http.request("$BASE/api/v1/dolphin/models", "PUT", body))
    }

    override suspend fun files(repoId: String): List<HfFile> = withContext(Dispatchers.IO) {
        parseFiles(Http.request("$BASE/api/v1/models/$repoId/repo/files?Recursive=true&Revision=master"))
    }

    override fun downloadUrl(repoId: String, path: String) = "$BASE/models/$repoId/resolve/master/$path"

    companion object {
        private const val BASE = "https://www.modelscope.cn"

        fun parseSearch(json: String): List<HfModel> {
            val models = JSONObject(json).optJSONObject("Data")?.optJSONObject("Model")?.optJSONArray("Models")
                ?: return emptyList()
            return List(models.length()) {
                val o = models.getJSONObject(it)
                HfModel("${o.optString("Path")}/${o.optString("Name")}", o.optLong("Downloads"), o.optLong("Stars"))
            }.filter { it.id.length > 1 && !it.id.startsWith("/") }
        }

        fun parseFiles(json: String): List<HfFile> {
            val files = JSONObject(json).optJSONObject("Data")?.optJSONArray("Files") ?: return emptyList()
            val entries = ArrayList<HfFile>()
            for (i in 0 until files.length()) {
                val o = files.getJSONObject(i)
                if (o.optString("Type") != "blob") continue
                entries += HfFile(o.optString("Path"), o.optLong("Size"))
            }
            return ModelHub.usableFiles(entries)
        }
    }
}

/**
 * Kaggle Models (kaggle.com/models). Public models need no account. Each model has several variations
 * (often one per quantisation), and a download is a single file from one variation's current version, so
 * every GGUF variation is listed as its own result with an id of `owner/model/gguf/variation/version`.
 */
class KaggleHub : ModelHub {

    override suspend fun search(query: String): List<HfModel> = searchPage(query).results

    override suspend fun searchPage(query: String, cursor: String?): SearchPage = withContext(Dispatchers.IO) {
        val q = query.trim().let { if (it.contains("gguf", ignoreCase = true)) it else "$it gguf" }
        val page = cursor?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
        val json = Http.request("$BASE/api/v1/models/list?search=${URLEncoder.encode(q, "UTF-8")}&pageSize=40$page")
        SearchPage(parseSearch(json), parseNextToken(json))
    }

    override suspend fun files(repoId: String): List<HfFile> = withContext(Dispatchers.IO) {
        val p = parseId(repoId)
        parseFiles(Http.request("$BASE/api/v1/models/${p.owner}/${p.model}/${p.framework}/${p.variation}/${p.version}/files"))
    }

    override fun downloadUrl(repoId: String, path: String): String {
        val p = parseId(repoId)
        val file = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "$BASE/api/v1/models/${p.owner}/${p.model}/${p.framework}/${p.variation}/${p.version}/download/$file"
    }

    class Id(val owner: String, val model: String, val framework: String, val variation: String, val version: String)

    companion object {
        private const val BASE = "https://www.kaggle.com"
        private const val MAX_RESULTS = 160

        /** The token that asks for the next page, or null on the last one. */
        fun parseNextToken(json: String): String? =
            runCatching { JSONObject(json).optString("nextPageToken") }.getOrNull()?.takeIf { it.isNotEmpty() }

        fun parseId(id: String): Id {
            val parts = id.split('/')
            if (parts.size != 5 || parts.any { it.isEmpty() }) throw IOException("Unexpected Kaggle model id: $id")
            return Id(parts[0], parts[1], parts[2], parts[3], parts[4])
        }

        /** One result per GGUF variation; models with other frameworks only are left out. */
        fun parseSearch(json: String): List<HfModel> {
            val models = JSONObject(json).optJSONArray("models") ?: return emptyList()
            val out = ArrayList<HfModel>()
            for (i in 0 until models.length()) {
                val m = models.getJSONObject(i)
                val ref = m.optString("ref")
                val instances = m.optJSONArray("instances") ?: continue
                for (j in 0 until instances.length()) {
                    val inst = instances.getJSONObject(j)
                    if (!inst.optString("framework").equals("gguf", ignoreCase = true)) continue
                    val variation = inst.optString("slug")
                    val version = inst.optInt("versionNumber", 1)
                    if (ref.isEmpty() || variation.isEmpty()) continue
                    out += HfModel(
                        id = "$ref/gguf/$variation/$version",
                        downloads = -1,
                        likes = m.optLong("voteCountNullable", m.optLong("voteCount")),
                        title = "${m.optString("title", ref)} · $variation",
                    )
                    if (out.size >= MAX_RESULTS) return out
                }
            }
            return out
        }

        fun parseFiles(json: String): List<HfFile> {
            val files = JSONObject(json).optJSONArray("files") ?: return emptyList()
            return ModelHub.usableFiles(List(files.length()) {
                val f = files.getJSONObject(it)
                HfFile(f.optString("name"), f.optLong("size"))
            })
        }
    }
}
