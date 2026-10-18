package com.pocketlm.app.data

/** Finding words inside one conversation. Pure, so it can be tested. */
object ChatSearch {

    /** Positions of the messages in [texts] that contain [query], ignoring capital letters. Empty for a blank query. */
    fun find(texts: List<String>, query: String): List<Int> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return texts.indices.filter { texts[it].contains(q, ignoreCase = true) }
    }

    /** The next or previous match, wrapping round at either end. */
    fun step(current: Int, count: Int, forward: Boolean): Int {
        if (count <= 0) return 0
        val from = current.coerceIn(0, count - 1)
        return if (forward) (from + 1) % count else (from - 1 + count) % count
    }
}

/** One message the user starred, with where to find it again. */
data class StarredItem(
    val chatId: String,
    val chatTitle: String,
    /** Position of the message in its chat. */
    val index: Int,
    val isUser: Boolean,
    val snippet: String,
    val chatUpdatedAt: Long,
)

object Starred {
    private const val SNIPPET_CHARS = 160

    /** Every starred message in [chats], newest chat first, in reading order within a chat. */
    fun collect(chats: List<SavedChat>): List<StarredItem> =
        chats.sortedByDescending { it.updatedAt }.flatMap { chat ->
            chat.messages.mapIndexedNotNull { index, m ->
                if (!m.starred) null
                else StarredItem(chat.id, chat.title, index, m.isUser, snippet(m.text), chat.updatedAt)
            }
        }

    /** The start of a message on one line, with any thinking section left out. */
    fun snippet(text: String): String {
        val parts = Thinking.split(text)
        val answer = parts.answer.ifBlank { parts.thinking.orEmpty() }.ifBlank { text }
        val flat = answer.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= SNIPPET_CHARS) flat else flat.take(SNIPPET_CHARS).trimEnd() + "…"
    }
}
