package com.pocketlm.app.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/** One message from a past chat that matched, with enough around it to recognise it. */
data class HistoryHit(
    val chatId: String,
    val chatTitle: String,
    val updatedAt: Long,
    val fromUser: Boolean,
    val snippet: String,
    val score: Int,
)

/** Looking through the person's saved chats for something they said before. Pure, so it can be tested. */
object ChatHistorySearch {
    const val MAX_HITS = 5
    const val MAX_PER_CHAT = 2
    private const val SNIPPET_CHARS = 260

    private val filler = setOf(
        "the", "and", "for", "with", "what", "did", "that", "this", "about", "was", "were", "are", "you", "how", "from",
        "have", "had", "has", "our", "your", "when", "where", "which", "who", "why", "can", "could", "would", "should",
        "tell", "say", "said", "told", "talk", "talked", "chat", "chats", "conversation", "earlier", "before", "ago", "last",
        "remember", "find", "search", "look", "any", "all", "not", "but", "there", "then", "than", "into", "out", "its",
        "we", "me", "my", "is", "it", "of", "in", "on", "to", "at", "do", "be", "an", "as", "or", "if", "so", "us", "they", "them",
    )

    /** The words of [query] worth matching: lower case, two letters or more, without everyday filler. */
    fun terms(query: String): List<String> {
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.distinct()
        val useful = words.filter { it !in filler }
        return useful.ifEmpty { words }
    }

    /**
     * The best matches in [chats], best first. A message counts when it contains at least half of the query's words.
     * The chat named by [skipChatId] (the one being written now) is left out because the model can already see it.
     */
    fun search(chats: List<SavedChat>, query: String, skipChatId: String? = null, limit: Int = MAX_HITS): List<HistoryHit> {
        val words = terms(query)
        if (words.isEmpty()) return emptyList()
        val needed = ceil(words.size / 2.0).toInt().coerceAtLeast(1)
        val phrase = query.trim().lowercase()

        val hits = ArrayList<HistoryHit>()
        for (chat in chats) {
            if (chat.id == skipChatId) continue
            for (message in chat.messages) {
                val text = visibleText(message.text)
                if (text.isBlank()) continue
                val lower = text.lowercase()
                val matched = words.count { lower.contains(it) }
                if (matched < needed) continue
                val score = matched * 10 + (if (phrase.length >= 4 && lower.contains(phrase)) 5 else 0)
                hits += HistoryHit(chat.id, chat.title, chat.updatedAt, message.isUser, snippet(text, words), score)
            }
        }
        val perChat = HashMap<String, Int>()
        return hits.sortedWith(compareByDescending<HistoryHit> { it.score }.thenByDescending { it.updatedAt })
            .filter { perChat.merge(it.chatId, 1, Int::plus)!! <= MAX_PER_CHAT }
            .take(limit)
    }

    /** What the person sees of a message: no thinking section, one line. */
    private fun visibleText(text: String): String {
        val parts = Thinking.split(text)
        return parts.answer.ifBlank { text }.replace(Regex("\\s+"), " ").trim()
    }

    /** About [SNIPPET_CHARS] characters of [text] around the first word that matched. */
    fun snippet(text: String, words: List<String>): String {
        if (text.length <= SNIPPET_CHARS) return text
        val lower = text.lowercase()
        val at = words.mapNotNull { w -> lower.indexOf(w).takeIf { it >= 0 } }.minOrNull() ?: 0
        val start = (at - SNIPPET_CHARS / 3).coerceAtLeast(0)
        val end = (start + SNIPPET_CHARS).coerceAtMost(text.length)
        return (if (start > 0) "…" else "") + text.substring(start, end).trim() + if (end < text.length) "…" else ""
    }

    /** The hits written out for the model. */
    fun format(hits: List<HistoryHit>, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        return hits.mapIndexed { i, h ->
            val day = Instant.ofEpochMilli(h.updatedAt).atZone(zone).format(date)
            val who = if (h.fromUser) "you wrote" else "Piko replied"
            "${i + 1}. In \"${h.chatTitle}\" ($day), $who: ${h.snippet}"
        }.joinToString("\n")
    }
}
