package com.pocketlm.app.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class WebText(val title: String, val text: String)

/** Turns the HTML of a web page into the plain text a model can read. */
object HtmlText {
    private val options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    private val titleRegex = Regex("<title[^>]*>(.*?)</title>", options)
    private val removedBlocks = listOf("script", "style", "noscript", "svg", "template", "head", "nav", "footer", "aside", "form", "iframe")
        .map { Regex("<$it\\b[^>]*>.*?</$it\\s*>", options) }
    private val comments = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val mainRegex = Regex("<main\\b[^>]*>(.*?)</main\\s*>", options)
    private val articleRegex = Regex("<article\\b[^>]*>(.*?)</article\\s*>", options)
    private val lineBreaks = Regex("<\\s*(br|/p|/div|/h[1-6]|/tr|/section|/ul|/ol|/table|/blockquote|/pre)\\b[^>]*>", options)
    private val bullets = Regex("<\\s*li\\b[^>]*>", options)
    private val cells = Regex("<\\s*/t[dh]\\s*>", options)
    private val tags = Regex("<[^>]+>")
    private val numeric = Regex("&#(x?)([0-9a-fA-F]+);")

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’",
        "ldquo" to "“", "rdquo" to "”", "copy" to "©", "reg" to "®", "trade" to "™", "bull" to "•",
        "middot" to "·", "euro" to "€", "pound" to "£", "laquo" to "«", "raquo" to "»",
    )

    fun extract(html: String): WebText {
        val title = titleRegex.find(html)?.groupValues?.get(1)?.let(::cleanInline).orEmpty()

        var body = comments.replace(html, "")
        // The page's own main content, when it marks one, is better than the whole page.
        val focus = listOf(mainRegex, articleRegex)
            .mapNotNull { r -> r.find(body)?.groupValues?.get(1) }
            .firstOrNull { it.length > 400 }
        if (focus != null) body = focus
        for (block in removedBlocks) body = block.replace(body, " ")

        body = lineBreaks.replace(body, "\n")
        body = bullets.replace(body, "\n- ")
        body = cells.replace(body, " ")
        body = tags.replace(body, "")
        body = decode(body)

        val lines = body.lines().map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }
        val text = lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        return WebText(title, text)
    }

    private fun cleanInline(raw: String) = decode(tags.replace(raw, "")).replace(Regex("\\s+"), " ").trim()

    fun decode(text: String): String {
        var out = numeric.replace(text) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            if (code != null && code in 1..0x10FFFF && code !in 0xD800..0xDFFF) String(Character.toChars(code)) else ""
        }
        out = Regex("&([a-zA-Z]+);").replace(out) { m -> named[m.groupValues[1].lowercase()] ?: m.value }
        return out
    }
}

/** Fetching a web page so it can be attached to a chat like a document. */
object WebPage {
    const val MAX_BYTES = 3_000_000

    /** A clean http(s) address for what the user typed, or null if it can't be one. */
    fun normalise(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (!host.contains('.') && host != "localhost" && !host.contains(':')) return null
        return uri.toString()
    }

    /** Downloads [address] and returns its readable text. Blocking: call from a background thread. */
    fun fetch(address: String): WebText {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) PocketLM")
            connection.setRequestProperty("Accept", "text/html,text/plain;q=0.9,*/*;q=0.1")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("The site answered with HTTP $code")

            val type = connection.contentType.orEmpty().lowercase()
            if ("pdf" in type) throw IOException("That link is a PDF. Download it and attach the file instead.")
            val readable = type.isEmpty() || "html" in type || "xml" in type || type.startsWith("text/") ||
                "json" in type
            if (!readable) throw IOException("That link is not a web page or text (it is $type).")

            val bytes = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (out.size() < MAX_BYTES) {
                    val n = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - out.size()))
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            val raw = String(bytes, charsetFor(type, bytes))
            val isHtml = "html" in type || "xml" in type || (type.isEmpty() && raw.contains("<html", ignoreCase = true))
            val page = if (isHtml) HtmlText.extract(raw) else WebText("", raw.trim())
            if (page.text.length < 20) throw IOException("No readable text was found on that page.")
            return page.copy(title = page.title.ifBlank { URI(address).host.orEmpty() })
        } finally {
            connection.disconnect()
        }
    }

    private fun charsetFor(contentType: String, bytes: ByteArray): java.nio.charset.Charset {
        val fromHeader = Regex("charset=([\\w-]+)").find(contentType)?.groupValues?.get(1)
        val fromMeta = Regex("<meta[^>]+charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
            .find(String(bytes.copyOf(minOf(bytes.size, 2048)), Charsets.ISO_8859_1))?.groupValues?.get(1)
        return (fromHeader ?: fromMeta)?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
    }
}
