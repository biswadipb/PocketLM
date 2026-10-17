package com.pocketlm.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** Where the web search tool gets its results. */
enum class SearchProvider(val id: String, val label: String, val blurb: String) {
    DuckDuckGo(
        "duckduckgo", "DuckDuckGo",
        "Nothing to set up. Reads DuckDuckGo's plain web results page, which DuckDuckGo can change or block at any time.",
    ),
    Brave(
        "brave", "Brave Search",
        "Steadier. Needs a free API key from brave.com/search/api, which allows a couple of thousand searches a month.",
    ),
    SearXNG(
        "searxng", "SearXNG server",
        "A SearXNG server you run or trust, with JSON results turned on. Most public ones turn that off.",
    );

    companion object {
        fun fromId(id: String?): SearchProvider? = entries.firstOrNull { it.id == id }

        /** The saved choice, or, for someone who set up a SearXNG server before there was a choice, SearXNG. */
        fun initial(savedId: String?, savedServer: String): SearchProvider =
            fromId(savedId) ?: if (savedServer.isNotBlank()) SearXNG else DuckDuckGo
    }
}

/** Everything the search tool needs to run a search. */
data class SearchConfig(
    val provider: SearchProvider = SearchProvider.DuckDuckGo,
    val searxServer: String = "",
    val braveKey: String = "",
) {
    /** Why a search cannot run yet, or null when it can. */
    fun problem(): String? = when (provider) {
        SearchProvider.DuckDuckGo -> null
        SearchProvider.Brave -> if (braveKey.isBlank()) "no Brave Search key is saved" else null
        SearchProvider.SearXNG -> if (ModelHub.normaliseAddress(searxServer) == null) "no search server address is saved" else null
    }
}

/** One search result. */
data class SearchHit(val title: String, val url: String, val snippet: String)

object WebSearch {

    suspend fun query(config: SearchConfig, query: String): String? = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        when (config.provider) {
            SearchProvider.DuckDuckGo ->
                format(parseDuckDuckGo(get("https://html.duckduckgo.com/html/?q=$q", emptyMap(), "DuckDuckGo")))
            SearchProvider.Brave -> format(
                parseBrave(
                    get(
                        "https://api.search.brave.com/res/v1/web/search?q=$q&count=8&safesearch=moderate",
                        mapOf("X-Subscription-Token" to config.braveKey.trim(), "Accept" to "application/json"),
                        "Brave Search",
                    ),
                ),
            )
            SearchProvider.SearXNG -> {
                val server = ModelHub.normaliseAddress(config.searxServer) ?: throw IOException("No search server address")
                val json = try {
                    Http.request("${server.trimEnd('/')}/search?q=$q&format=json&safesearch=1")
                } catch (e: IOException) {
                    if (e.message?.contains("login") == true) {
                        throw IOException("That search server does not allow JSON results. Use one that does, such as your own.")
                    }
                    throw e
                }
                parse(json)
            }
        }
    }

    private const val MAX_BYTES = 1_500_000

    private fun get(address: String, headers: Map<String, String>, name: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) PocketLM")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            when (val code = connection.responseCode) {
                200 -> Unit
                202 -> throw IOException("$name asked for a human check, so it can't be used right now. Try again later, or pick another search provider.")
                401, 403, 422 -> throw IOException("$name did not accept the request. Check the key in Settings.")
                429 -> throw IOException("$name says there have been too many searches. Try again later.")
                else -> throw IOException("$name answered with HTTP $code.")
            }
            return connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (out.size() < MAX_BYTES) {
                    val n = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - out.size()))
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    // ---- Reading each provider's answer ---------------------------------------------------

    private val anchor = Regex("""<a\b([^>]*\bclass="result__a"[^>]*)>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val snippetAnchor = Regex("""<a\b[^>]*\bclass="result__snippet"[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val href = Regex("""href="([^"]*)"""")
    private val tag = Regex("<[^>]+>")

    /** The results on DuckDuckGo's plain results page; adverts are left out. */
    fun parseDuckDuckGo(html: String): List<SearchHit> {
        val links = anchor.findAll(html).toList()
        val hits = ArrayList<SearchHit>()
        links.forEachIndexed { i, link ->
            val rawUrl = href.find(link.groupValues[1])?.groupValues?.get(1) ?: return@forEachIndexed
            val url = realAddress(HtmlText.decode(rawUrl)) ?: return@forEachIndexed
            val title = text(link.groupValues[2])
            if (title.isEmpty()) return@forEachIndexed
            val until = links.getOrNull(i + 1)?.range?.first ?: html.length
            val snippet = snippetAnchor.find(html.substring(link.range.last + 1, until))?.groupValues?.get(1)?.let(::text).orEmpty()
            hits += SearchHit(title, url, snippet)
        }
        return hits
    }

    /** DuckDuckGo wraps links in a redirect ("//duckduckgo.com/l/?uddg=…"); this gives the real address, or null for an advert. */
    fun realAddress(link: String): String? {
        val withScheme = if (link.startsWith("//")) "https:$link" else link
        if (withScheme.contains("duckduckgo.com/y.js")) return null
        val wrapped = Regex("""[?&]uddg=([^&]+)""").find(withScheme)?.groupValues?.get(1)
        val real = if (wrapped != null) runCatching { URLDecoder.decode(wrapped, "UTF-8") }.getOrNull() else withScheme
        return real?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    fun parseBrave(json: String): List<SearchHit> {
        val results = JSONObject(json).optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val r = results.optJSONObject(i) ?: return@mapNotNull null
            val title = text(r.optString("title"))
            if (title.isEmpty()) null else SearchHit(title, r.optString("url").trim(), text(r.optString("description")))
        }
    }

    /** The top results of a SearXNG answer as a numbered list, or null when there are none. */
    fun parse(json: String, maxResults: Int = 4, maxChars: Int = 1600): String? {
        val results = JSONObject(json).optJSONArray("results") ?: return null
        val hits = (0 until results.length()).mapNotNull { i ->
            val r = results.optJSONObject(i) ?: return@mapNotNull null
            val title = r.optString("title").trim()
            if (title.isEmpty()) null else SearchHit(title, r.optString("url").trim(), r.optString("content"))
        }
        return format(hits, maxResults, maxChars)
    }

    /** "1. title (address): snippet" lines for the model, or null when there are no hits. */
    fun format(hits: List<SearchHit>, maxResults: Int = 4, maxChars: Int = 1600): String? {
        val lines = hits.take(maxResults).mapIndexed { i, hit ->
            val snippet = hit.snippet.trim().replace(Regex("""\s+"""), " ").take(300)
            "${i + 1}. ${hit.title} (${hit.url})" + if (snippet.isNotEmpty()) ": $snippet" else ""
        }
        if (lines.isEmpty()) return null
        val text = lines.joinToString("\n")
        return if (text.length <= maxChars) text else text.take(maxChars).trimEnd() + "…"
    }

    private fun text(markup: String) = HtmlText.decode(tag.replace(markup, "")).replace(Regex("""\s+"""), " ").trim()
}
