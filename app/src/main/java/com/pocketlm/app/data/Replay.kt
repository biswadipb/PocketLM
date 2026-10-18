package com.pocketlm.app.data

import com.arm.aichat.ContextUsage

/** One message as the model should see it again. A [summary] stands in for everything said before it. */
data class Turn(val isUser: Boolean, val text: String, val summary: Boolean = false)

/**
 * Rebuilds a conversation as text for a model whose memory of it is gone (after a reload, or when a chat
 * is reopened without a saved state).
 */
object Replay {
    const val MAX_CHARS = 4000
    private const val MAX_SUMMARY_CHARS = 1500

    fun build(turns: List<Turn>, maxChars: Int = MAX_CHARS): String? {
        if (turns.isEmpty()) return null
        val lastSummary = turns.indexOfLast { it.summary }
        val rest = turns.drop(lastSummary + 1)
        val body = rest.joinToString("\n") { line(it) }
        if (lastSummary < 0) return body.takeLast(maxChars)

        // The summary comes first and is kept whole; the recent messages after it fill what room is left.
        val head = "Summary of the earlier conversation: " + turns[lastSummary].text.trim().take(MAX_SUMMARY_CHARS)
        val room = (maxChars - head.length - 1).coerceAtLeast(0)
        val tail = body.takeLast(room)
        return if (tail.isEmpty()) head else head + "\n" + tail
    }

    private fun line(turn: Turn): String =
        if (turn.isUser) "User: ${turn.text}" else "Assistant: ${Thinking.answerOnly(turn.text)}"
}

/** Condensing a long chat into a short summary so it can carry on with room to spare. */
object Summaries {
    /** Longest the summary may be, in tokens. */
    const val REPLY_TOKENS = 384

    /** Free memory the model needs to write a summary inside the conversation it already has. */
    private const val ROOM_TOKENS = REPLY_TOKENS + 160

    private const val CHARS_PER_TOKEN = 3

    const val SYSTEM_PROMPT = "You write short, faithful summaries of conversations. Never add anything that was not said."

    const val REQUEST =
        "Summarise our conversation so far in under 200 words so we can continue it later. Keep names, numbers, " +
            "decisions, facts about me, and anything still unanswered. Write only the summary."

    /** True when there is room to ask for the summary inside the conversation the model already remembers. */
    fun canSummariseInPlace(usage: ContextUsage): Boolean =
        usage.total > 0 && usage.total - usage.used >= ROOM_TOKENS

    /** How much of the written conversation can be handed to a fresh model memory and still leave room to answer. */
    fun transcriptChars(contextTotal: Int): Int =
        ((contextTotal - ROOM_TOKENS) * CHARS_PER_TOKEN).coerceAtLeast(1000)

    fun requestFor(transcript: String): String =
        "Summarise this conversation in under 200 words. Keep names, numbers, decisions, facts about the user, " +
            "and anything still unanswered. Write only the summary.\n\n$transcript"

    /** The summary without any reasoning or stray tool request; empty if there is nothing usable. */
    fun clean(raw: String): String = Thinking.answerOnly(raw).substringBefore(Tools.OPEN_TAG).trim()

    /** Offered once the conversation is filling up, or at any time there is something to condense. */
    fun worthOffering(usage: ContextUsage, messageCount: Int): Boolean =
        messageCount >= 4 && ContextMeter.fraction(usage) >= 0.5f
}
