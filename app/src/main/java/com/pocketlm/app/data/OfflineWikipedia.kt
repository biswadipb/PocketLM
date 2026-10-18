package com.pocketlm.app.data

/** Turning what a person or a model typed into the titles of Wikipedia articles worth trying. Pure, so it can be tested. */
object WikiQuery {
    private val lead = Regex(
        """^(?:(?:who|what|where|when|why|how)\s+(?:is|are|was|were|does|do|did)\s+|tell\s+me\s+about\s+|define\s+|explain\s+|look\s+up\s+|search\s+for\s+|wikipedia\s+(?:article\s+)?(?:on|for|about)?\s*)(?:the\s+|a\s+|an\s+)?""",
        RegexOption.IGNORE_CASE,
    )

    private val stop = setOf(
        "the", "of", "and", "who", "what", "when", "where", "why", "how", "is", "was", "are", "were", "did", "does", "do", "a", "an", "in", "on",
        "at", "to", "for", "from", "with", "about", "tell", "me", "which", "that", "this", "it", "its", "his", "her", "their", "by", "as", "be",
    )

    /** [query] without the quotes, the question words in front and the closing punctuation. */
    fun clean(query: String): String {
        var text = query.trim().trim('"', '\'', '“', '”', '‘', '’', '`', '.', '?', '!', ',', ';', ':', ' ')
        text = text.replace(Regex("\\s+"), " ")
        val without = lead.replace(text, "").trim()
        return without.ifEmpty { text }
    }

    private fun titleCase(text: String) =
        text.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }

    private fun sentenceCase(text: String) = text.replaceFirstChar { it.uppercaseChar() }

    /** The titles to try for the whole query, most likely first, without repeats. */
    fun titles(query: String): List<String> {
        val text = clean(query)
        if (text.isEmpty()) return emptyList()
        return listOf(text, sentenceCase(text), titleCase(text), sentenceCase(text.lowercase())).distinct()
    }

    private val connectors = setOf("of", "the", "de", "la", "le", "van", "von", "der", "du", "del", "da", "di", "and", "for", "in")

    /** Runs of capitalised words such as "Alan Turing" or "Bank of England", the likeliest titles inside a longer question. */
    fun phrases(query: String): List<String> {
        val words = clean(query).split(' ').map { it.trim('.', ',', ';', ':', '?', '!', '"', '(', ')') }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var run = ArrayList<String>()
        fun flush() {
            while (run.isNotEmpty() && run.last().lowercase() in connectors) run.removeAt(run.lastIndex)
            if (run.size >= 2) out += run.joinToString(" ")
            run = ArrayList()
        }
        for ((i, word) in words.withIndex()) {
            val capital = word.first().isUpperCase() && !(i == 0 && words.size > 1 && word.lowercase() in stop)
            when {
                capital -> run += word
                run.isNotEmpty() && word.lowercase() in connectors -> run += word
                else -> flush()
            }
        }
        flush()
        return out.distinct()
    }

    /** Names and words worth trying when the whole query is not a title: phrases first, then single names, then longer words. */
    fun keywords(query: String): List<String> {
        val words = clean(query).split(' ').map { it.trim('.', ',', ';', ':', '?', '!', '"', '\'', '(', ')') }
            .filter { it.length >= 3 && it.lowercase() !in stop }
        val named = words.filter { it.first().isUpperCase() }.sortedByDescending { it.length }
        val rest = words.filterNot { it.first().isUpperCase() }.sortedByDescending { it.length }
        return (phrases(query) + named + rest.map { sentenceCase(it) }).distinct().take(8)
    }
}

/**
 * Looks things up in a Wikipedia ZIM file with no connection. Articles are found by title (a title, or the main word of a
 * question, since a ZIM file has no full-text search that can be read here), and the start of the article is returned.
 */
class OfflineWikipedia(private val zim: ZimFile) {

    class Article(val title: String, val text: String)

    fun lookup(query: String, maxChars: Int = 1500): Article? {
        val namespace = zim.contentNamespace
        for (title in WikiQuery.titles(query)) {
            article(zim.find(namespace, title.replace(' ', '_')), maxChars)?.let { return it }
        }
        // A title that merely begins with the words, such as "Ada Lovelace" for "Ada".
        for (title in WikiQuery.titles(query).take(2)) {
            article(best(title, zim.searchTitles(title)), maxChars)?.let { return it }
        }
        for (word in WikiQuery.keywords(query)) {
            article(zim.find(namespace, word.replace(' ', '_')), maxChars)?.let { return it }
        }
        return null
    }

    /** The most likely of the pages that begin with [title]: an exact match, then the shortest title, never a "disambiguation" page. */
    private fun best(title: String, found: List<ZimFile.Entry>): ZimFile.Entry? {
        val usable = found.filter { "(disambiguation)" !in it.title }
        return usable.firstOrNull { it.title.equals(title, ignoreCase = true) } ?: usable.minByOrNull { it.title.length }
    }

    private fun article(entry: ZimFile.Entry?, maxChars: Int): Article? {
        entry ?: return null
        val target = zim.resolve(entry) ?: return null
        if (target.mimeType?.startsWith("text/html") != true) return null
        val html = String(zim.content(target), Charsets.UTF_8)
        val text = ArticleText.intro(html, maxChars)
        if (text.isBlank()) return null
        return Article(target.title, text)
    }
}

/** The readable start of a Wikipedia page's HTML. */
object ArticleText {
    private val citation = Regex("""\[(?:\d+|[a-z]|citation needed|note \d+)]""", RegexOption.IGNORE_CASE)
    private val paragraph = Regex("<p\\b[^>]*>(.*?)</p\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val chrome = Regex(
        """^(?:jump to (?:content|navigation|search)|from wikipedia, the free encyclopedia|toggle .*|main menu|search|contents|\(top\)|move to sidebar|hide|edit|this article .*|coordinates:.*)$""",
        RegexOption.IGNORE_CASE,
    )

    /** The most paragraphs taken for an intro. */
    private const val MAX_PARAGRAPHS = 3

    /** A paragraph shorter than this is a caption or a label, not the start of the article. */
    private const val MIN_PARAGRAPH = 60

    /**
     * The first paragraphs of the article, without the box of facts at its side, pictures, notes or reference numbers.
     * Falls back to all the page's text when the page has no paragraphs (a list, say).
     */
    fun intro(html: String, maxChars: Int): String {
        var body = html
        for (tag in listOf("table", "figure", "style", "script")) body = removeBlocks(body, tag)
        val paragraphs = paragraph.findAll(body).map { clean(HtmlText.extract(it.value).text) }.filter { it.length >= 2 }.toList()
        val firstReal = paragraphs.indexOfFirst { it.length >= MIN_PARAGRAPH }
        val text = if (firstReal >= 0) {
            val chosen = ArrayList<String>()
            var used = 0
            for (p in paragraphs.drop(firstReal)) {
                if (chosen.size >= MAX_PARAGRAPHS || (chosen.isNotEmpty() && used + p.length > maxChars)) break
                if (p.length < MIN_PARAGRAPH && chosen.isNotEmpty()) continue
                chosen += p
                used += p.length
            }
            chosen.joinToString("\n\n")
        } else {
            HtmlText.extract(body).text.lines().map(::clean).filter { it.length > 2 && !chrome.matches(it) }.joinToString("\n")
        }
        return if (text.length <= maxChars) text else text.take(maxChars).trimEnd() + "…"
    }

    private fun clean(text: String) = citation.replace(text, "").replace(Regex("[ \\t\\u00a0]+"), " ").trim()

    /** [html] without every <[tag]> element, nested ones included. */
    internal fun removeBlocks(html: String, tag: String): String {
        val open = Regex("<$tag\\b", RegexOption.IGNORE_CASE)
        val close = Regex("</$tag\\s*>", RegexOption.IGNORE_CASE)
        val out = StringBuilder()
        var from = 0
        while (true) {
            val start = open.find(html, from) ?: break
            out.append(html, from, start.range.first)
            var depth = 0
            var at = start.range.first
            while (true) {
                val o = open.find(html, at)
                val c = close.find(html, at)
                if (c == null) {
                    at = html.length
                    break
                }
                if (o != null && o.range.first < c.range.first) {
                    depth++
                    at = o.range.last + 1
                } else {
                    depth--
                    at = c.range.last + 1
                    if (depth == 0) break
                }
            }
            from = at
        }
        out.append(html, from.coerceAtMost(html.length), html.length)
        return out.toString()
    }
}
