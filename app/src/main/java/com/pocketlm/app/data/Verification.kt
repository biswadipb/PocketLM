package com.pocketlm.app.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Checking that a downloaded model is the file the model site published. Hugging Face (and servers that copy it)
 * answer a request for a model file with a redirect that carries the file's SHA-256 in an `X-Linked-Etag` header.
 */
object Verification {

    private val SHA256 = Regex("^[0-9a-f]{64}$")

    /** The SHA-256 in an `X-Linked-Etag` value such as `"3f2a…"` or `W/"3f2a…"`, in lower case, or null if it is not one. */
    fun parseLinkedEtag(header: String?): String? {
        val value = header?.trim()?.removePrefix("W/")?.trim('"')?.lowercase() ?: return null
        return value.takeIf { SHA256.matches(it) }
    }

    /** Asks the server which SHA-256 the file at [url] should have. Null when it does not say, or cannot be asked. */
    fun expectedFor(url: String): String? {
        // A link to an Ollama registry file names the checksum in the link itself.
        OllamaRegistry.expectedSha256(url)?.let { return it }
        // Kiwix lists the checksum of each file next to it.
        if (url.startsWith(Kiwix.FOLDER)) return Kiwix.expectedSha256(url)
        // Only download links of the form .../resolve/<revision>/<file> carry the header.
        if ("/resolve/" !in url) return null
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "HEAD"
                connection.instanceFollowRedirects = false  // the checksum is on the redirect itself
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("User-Agent", "PocketLM")
                parseLinkedEtag(connection.getHeaderField("X-Linked-Etag"))
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** The SHA-256 of [file], in lower case hex. Reads it in pieces, so a multi-gigabyte model needs little memory. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** True if [actual] is [expected], ignoring case. */
    fun matches(expected: String, actual: String): Boolean = expected.equals(actual, ignoreCase = true)
}
