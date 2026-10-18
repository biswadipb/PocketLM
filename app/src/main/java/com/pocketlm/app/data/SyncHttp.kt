package com.pocketlm.app.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URI
import java.net.URL

class HttpResponse(val code: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
    val ok get() = code in 200..299
    fun text(): String = String(body, Charsets.UTF_8)
}

/** The few HTTP calls the sync backends need. A small interface so the backends can be tested without a network. */
interface SyncHttp {
    fun request(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): HttpResponse
}

/**
 * [SyncHttp] over HttpURLConnection. Redirects are followed by hand (up to [MAX_REDIRECTS], never to another host)
 * so that the method and body are kept and the sign-in details are not sent somewhere else.
 *
 * HttpURLConnection only knows the usual verbs. WebDAV's PROPFIND and MKCOL are tried by setting the method
 * directly, and as a last resort sent as POST with an X-HTTP-Method-Override header. Neither is guaranteed
 * to work on every Android version, so the sync itself only relies on GET, PUT and DELETE.
 */
class UrlConnectionHttp(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
    private val maxBodyBytes: Int = 64 * 1024 * 1024,
) : SyncHttp {

    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        var target = url
        repeat(MAX_REDIRECTS + 1) {
            val response = once(method, target, headers, body)
            if (response.code in REDIRECTS) {
                val location = response.headers["location"] ?: return response
                val next = runCatching { URI(target).resolve(location).toString() }.getOrNull() ?: return response
                if (URI(next).host != URI(target).host) throw SyncException("The server tried to send the request to another address.")
                target = next
            } else {
                return response
            }
        }
        throw SyncException("The server redirected too many times.")
    }

    private fun once(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        val parsed = URL(url)
        if (parsed.protocol != "https" && parsed.protocol != "http") throw SyncException("Only http and https addresses work.")
        val conn = parsed.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            val overridden = !setMethod(conn, method)
            for ((name, value) in headers) conn.setRequestProperty(name, value)
            if (overridden) conn.setRequestProperty("X-HTTP-Method-Override", method)
            if (body != null || overridden) {
                val bytes = body ?: ByteArray(0)
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val data = stream?.use { readLimited(it, if (code >= 400) MAX_ERROR_BYTES else maxBodyBytes) } ?: ByteArray(0)
            val responseHeaders = HashMap<String, String>()
            for ((name, values) in conn.headerFields) if (name != null && values.isNotEmpty()) responseHeaders[name.lowercase()] = values.last()
            return HttpResponse(code, data, responseHeaders)
        } finally {
            conn.disconnect()
        }
    }

    /** Sets the request method; false if it had to fall back to POST with an override header. */
    private fun setMethod(conn: HttpURLConnection, method: String): Boolean {
        try {
            conn.requestMethod = method
            return true
        } catch (e: ProtocolException) {
            // Not one of the standard verbs. Try the field behind the setter.
        }
        var type: Class<*>? = conn.javaClass
        while (type != null) {
            try {
                val field = type.getDeclaredField("method")
                field.isAccessible = true
                field.set(conn, method)
                return true
            } catch (e: Exception) {
                type = type.superclass
            }
        }
        conn.requestMethod = "POST"
        return false
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > limit) throw IOException("The server's answer is larger than expected.")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_ERROR_BYTES = 256 * 1024
        val REDIRECTS = setOf(301, 302, 307, 308)
    }
}
