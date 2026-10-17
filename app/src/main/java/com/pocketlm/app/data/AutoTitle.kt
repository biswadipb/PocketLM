package com.pocketlm.app.data

/**
 * Naming a chat after its first exchange. The model is asked for a title (and, if the person already uses tags,
 * which of them fit); what it writes is tidied, and refused if it does not look like a title, since small models
 * sometimes answer the question instead of naming the chat.
 */
object AutoTitle {
    /** Longest title kept, the same cut that is used when the title comes from the first message. */
    const val MAX_CHARS = 48
    const val MAX_WORDS = 10

    /** Room the answer needs: a short title and a short list of tags. */
    const val REPLY_TOKENS = 32

    private val WRAPPERS = charArrayOf('"', '\'', '`', '*', '_', '#', '“', '”', '‘', '’', ' ')
    private val ENDINGS = charArrayOf('.', ',', ';', ':', '!', ' ', '-')

    /** Most tags filed under at once. */
    const val MAX_TAGS = 2

    /** The message that asks for a title and, when the person already uses tags, for the ones that fit. */
    fun request(knownTags: List<String>): String = buildString {
        append("Give this conversation a short title of at most six words. No quotes, no full stop at the end.")
        if (knownTags.isNotEmpty()) {
            append(" Then choose up to $MAX_TAGS of these tags if they clearly fit, or none: ")
            append(knownTags.joinToString(", "))
            append(".")
        }
        append("\nReply in exactly this form and nothing else:\nTitle: <the title>")
        if (knownTags.isNotEmpty()) append("\nTags: <tags separated by commas, or none>")
    }

    data class Named(val title: String?, val tags: List<String>)

    private val refusal = Regex(
        """^(i'm sorry|i am sorry|sorry|i cannot|i can't|i can not|as an ai|here is|here's|sure|certainly|of course)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** The title and tags in [reply]; [knownTags] limits which tags are accepted (their own spelling is kept). */
    fun parse(reply: String, knownTags: List<String>): Named {
        val lines = Thinking.answerOnly(reply).lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return Named(null, emptyList())

        fun valueOf(prefix: String) = lines.firstOrNull { it.startsWith(prefix, ignoreCase = true) }?.substring(prefix.length)?.trim()

        val titleText = valueOf("title:") ?: lines.firstOrNull { !it.startsWith("tags:", ignoreCase = true) }
        val tagText = valueOf("tags:")
        return Named(titleText?.let(::tidy), tagText?.let { pickTags(it, knownTags) }.orEmpty())
    }

    private fun pickTags(text: String, known: List<String>): List<String> {
        val wanted = text.split(',', ';', '\n').mapNotNull { ChatTags.normalize(it.trim().trim('"', '\'', '.', '[', ']')) }
        val picked = ArrayList<String>()
        for (raw in wanted) {
            val match = known.firstOrNull { it.equals(raw, ignoreCase = true) } ?: continue
            if (picked.none { it.equals(match, ignoreCase = true) }) picked += match
            if (picked.size == MAX_TAGS) break
        }
        return picked
    }

    /** A title cleaned of quotes, markdown and closing punctuation; null if it is empty or looks like something else. */
    fun tidy(raw: String): String? {
        var text = raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        text = text.removePrefix("Title:").removePrefix("title:").trim()
        // Quotes, markdown and closing punctuation can be stacked ("`Title`!"), so strip until nothing changes.
        do {
            val before = text
            text = text.trim(*WRAPPERS).trimEnd(*ENDINGS)
        } while (text != before)
        text = text.replace(Regex("\\s+"), " ")
        if (text.length < 3 || refusal.containsMatchIn(text)) return null
        if (text.split(' ').size > MAX_WORDS || text.length > 3 * MAX_CHARS) return null
        if (text.length <= MAX_CHARS) return text
        // Cut at a word boundary instead of in the middle of a word.
        val cut = text.take(MAX_CHARS + 1)
        val boundary = cut.lastIndexOf(' ')
        return (if (boundary >= MAX_CHARS / 2) cut.take(boundary) else text.take(MAX_CHARS)).trimEnd(',', ';', ':', ' ')
    }

    /**
     * Whether it is worth naming this chat now: the first exchange is done, the person has not named it, and it has
     * not been tried already. [exchanges] counts the replies in the chat.
     */
    fun due(customTitle: Boolean, exchanges: Int, alreadyTried: Boolean, enabled: Boolean, incognito: Boolean): Boolean =
        enabled && !incognito && !customTitle && !alreadyTried && exchanges == 1
}
