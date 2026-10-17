package com.pocketlm.app.data

/** Labels on chats. Pure, so the rules can be tested. */
object ChatTags {
    const val MAX_PER_CHAT = 8
    const val MAX_LENGTH = 24

    /** One tag as typed: trimmed, a leading # dropped, inner spaces collapsed, cut to [MAX_LENGTH]. Null if nothing is left. */
    fun normalize(raw: String): String? =
        raw.trimStart { it == '#' || it.isWhitespace() }.replace(Regex("\\s+"), " ").take(MAX_LENGTH).trim().ifEmpty { null }

    /** Normalised tags without repeats (capital letters ignored; the first spelling stays), at most [MAX_PER_CHAT]. */
    fun clean(tags: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (raw in tags) {
            val tag = normalize(raw) ?: continue
            if (seen.add(tag.lowercase())) out += tag
            if (out.size == MAX_PER_CHAT) break
        }
        return out
    }

    fun has(chat: SavedChat, tag: String): Boolean = chat.tags.any { it.equals(tag, ignoreCase = true) }

    /** Every tag in use with how many chats carry it, the most used first. */
    fun counts(chats: List<SavedChat>): List<Pair<String, Int>> {
        val shown = LinkedHashMap<String, String>()
        val count = HashMap<String, Int>()
        for (chat in chats) for (tag in chat.tags) {
            val key = tag.lowercase()
            shown.putIfAbsent(key, tag)
            count[key] = (count[key] ?: 0) + 1
        }
        return shown.map { (key, name) -> name to count.getValue(key) }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
    }

    /** Adds [tag] to [tags] if it is valid and there is room. */
    fun add(tags: List<String>, tag: String): List<String> = clean(tags + tag)

    fun remove(tags: List<String>, tag: String): List<String> = tags.filterNot { it.equals(tag, ignoreCase = true) }

    /** Tags from chats elsewhere that this chat does not have yet, for suggestions. */
    fun suggestions(all: List<Pair<String, Int>>, current: List<String>, typed: String): List<String> {
        val q = typed.trim().trimStart('#').lowercase()
        return all.map { it.first }
            .filter { tag -> current.none { it.equals(tag, ignoreCase = true) } && (q.isEmpty() || q in tag.lowercase()) }
            .take(8)
    }
}
