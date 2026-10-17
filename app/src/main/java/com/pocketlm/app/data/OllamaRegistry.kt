package com.pocketlm.app.data

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Something the registry said, or did not, in words fit to show. */
class OllamaException(message: String) : IOException(message)

/**
 * Downloading a model by its Ollama name, such as `llama3.2:1b`. Ollama's public registry speaks the same protocol as a
 * container registry: a manifest lists the files that make up a model, and each file is fetched by its SHA-256, which also
 * lets PocketLM check what it received. Only the model file (and a vision add-on, when there is one) is taken; Ollama's
 * template and settings files are not used. PocketLM runs the file with llama.cpp, so a model that Ollama made in its own
 * newer format may not load.
 */
object OllamaRegistry {
    const val BASE = "https://registry.ollama.ai"

    private const val MODEL_LAYER = "application/vnd.ollama.image.model"
    private const val PROJECTOR_LAYER = "application/vnd.ollama.image.projector"
    private const val MAX_BYTES = 200L * 1024 * 1024 * 1024
    private const val MAX_MANIFEST_CHARS = 200_000

    /** A model in the registry. [namespace] is "library" for the models Ollama itself publishes. */
    data class Ref(val namespace: String, val name: String, val tag: String) {
        /** How a person writes it: `llama3.2:1b`, or `someone/model:tag`. */
        val display: String get() = (if (namespace == "library") name else "$namespace/$name") + ":" + tag
    }

    /** One file of a model: its digest (`sha256:…`) and size in bytes. */
    data class Layer(val digest: String, val size: Long) {
        val sha256: String get() = digest.removePrefix("sha256:")
    }

    data class Manifest(val model: Layer, val projector: Layer?)

    private val part = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val tagPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val digest = Regex("sha256:[0-9a-f]{64}")
    private val blobUrl = Regex("""^https://registry\.ollama\.ai/v2/[^/]+/[^/]+/blobs/sha256:([0-9a-f]{64})$""")

    /**
     * The model named by [input]: `name`, `name:tag`, `namespace/name:tag`, or a line copied from Ollama's pages such as
     * `ollama run llama3.2:1b` or a link to ollama.com/library/…. Null if it is not a valid name.
     */
    fun parse(input: String): Ref? {
        var text = input.trim()
        text = text.removePrefix("ollama run ").removePrefix("ollama pull ").trim()
        text = text.removePrefix("https://").removePrefix("http://")
        for (host in listOf("registry.ollama.ai/", "ollama.com/")) text = text.removePrefix(host)
        text = text.trim('/')
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null

        val tag = if (':' in text) text.substringAfterLast(':') else "latest"
        val path = text.substringBeforeLast(':', text).lowercase()
        val segments = path.split('/')
        val (namespace, name) = when (segments.size) {
            1 -> "library" to segments[0]
            2 -> segments[0] to segments[1]
            else -> return null
        }
        if (!part.matches(namespace) || !part.matches(name) || !tagPattern.matches(tag)) return null
        return Ref(namespace, name, tag)
    }

    fun manifestUrl(ref: Ref, base: String = BASE) = "$base/v2/${ref.namespace}/${ref.name}/manifests/${ref.tag}"

    fun blobUrl(ref: Ref, layer: Layer, base: String = BASE) = "$base/v2/${ref.namespace}/${ref.name}/blobs/${layer.digest}"

    /** The SHA-256 a blob link promises, so a download of it can be checked; null for any other link. */
    fun expectedSha256(url: String): String? = blobUrl.find(url)?.groupValues?.get(1)

    /** A file name for the model on the phone: `llama3.2-1b.gguf`. */
    fun fileName(ref: Ref): String {
        val safe = (listOfNotNull(ref.namespace.takeIf { it != "library" }, ref.name, ref.tag)).joinToString("-")
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
        return "$safe.gguf"
    }

    /** The name of the vision add-on file that goes with [modelFile]. */
    fun projectorFileName(modelFile: String) = "mmproj-$modelFile"

    /** Reads a manifest. Throws [OllamaException] when it does not describe a model PocketLM can use. */
    fun parseManifest(json: String): Manifest {
        if (json.length > MAX_MANIFEST_CHARS) throw OllamaException("The registry sent something unexpectedly large.")
        val layers = try {
            JSONObject(json).optJSONArray("layers")
        } catch (e: JSONException) {
            throw OllamaException("The registry's answer could not be read.")
        } ?: throw OllamaException("That is not a model: the registry listed no files for it.")

        fun layer(type: String): Layer? {
            val found = (0 until layers.length()).mapNotNull { layers.optJSONObject(it) }.filter { it.optString("mediaType") == type }
            if (found.isEmpty()) return null
            val o = found.first()
            val d = o.optString("digest")
            val size = o.optLong("size", -1)
            if (!digest.matches(d) || size <= 0 || size > MAX_BYTES) throw OllamaException("The registry described a file in a way that cannot be trusted.")
            return Layer(d, size)
        }
        val model = layer(MODEL_LAYER) ?: throw OllamaException("That entry has no model file in it.")
        return Manifest(model, layer(PROJECTOR_LAYER))
    }

    /**
     * Asks the registry what makes up [ref]. [base] is only different in tests. Throws [OllamaException] with a plain
     * explanation when the model is not there or the registry cannot be reached.
     */
    fun fetchManifest(ref: Ref, base: String = BASE): Manifest {
        val connection = URL(manifestUrl(ref, base)).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/vnd.docker.distribution.manifest.v2+json")
            connection.setRequestProperty("User-Agent", "PocketLM")
            val status = connection.responseCode
            if (status == 404) throw OllamaException("${ref.display} is not in the Ollama library. Check the name and tag on ollama.com/library.")
            if (status !in 200..299) throw OllamaException("The Ollama registry answered with an error ($status).")
            val body = connection.inputStream.use { stream ->
                val out = StringBuilder()
                val reader = stream.bufferedReader()
                val buffer = CharArray(8192)
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    out.append(buffer, 0, n)
                    if (out.length > MAX_MANIFEST_CHARS) throw OllamaException("The registry sent something unexpectedly large.")
                }
                out.toString()
            }
            return parseManifest(body)
        } catch (e: OllamaException) {
            throw e
        } catch (e: IOException) {
            throw OllamaException("Could not reach the Ollama registry. Check the connection.")
        } finally {
            connection.disconnect()
        }
    }
}
