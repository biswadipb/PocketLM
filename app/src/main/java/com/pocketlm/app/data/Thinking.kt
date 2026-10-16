package com.pocketlm.app.data

/**
 * A reply split into the model's reasoning and its answer. [thinkingOpen] is true while the reasoning
 * is still being written (no closing tag yet), when [answer] is empty.
 */
data class ThinkParts(val thinking: String?, val answer: String, val thinkingOpen: Boolean)

/** Reasoning models (DeepSeek-R1, Qwen3 and others) write their working inside `<think>…</think>` first. */
object Thinking {
    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"

    fun split(text: String): ThinkParts {
        val start = text.trimStart()
        if (start.startsWith(OPEN)) {
            val rest = start.removePrefix(OPEN)
            val end = rest.indexOf(CLOSE)
            return if (end < 0) ThinkParts(rest.trim(), "", true)
            else ThinkParts(rest.substring(0, end).trim(), rest.substring(end + CLOSE.length).trimStart(), false)
        }
        // Some chat templates open the tag for the model, so its reply starts mid-thought and only closes it.
        val end = text.indexOf(CLOSE)
        if (end > 0 && OPEN !in text) {
            return ThinkParts(text.substring(0, end).trim(), text.substring(end + CLOSE.length).trimStart(), false)
        }
        return ThinkParts(null, text, false)
    }

    /** The reply without its reasoning: what to copy, read aloud, notify about, or replay to the model. */
    fun answerOnly(text: String): String = split(text).answer
}
