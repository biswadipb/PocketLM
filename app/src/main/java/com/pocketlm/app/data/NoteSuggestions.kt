package com.pocketlm.app.data

/** Asks the model which lasting facts about the user a conversation reveals, and reads its answer. Pure, so it can be tested. */
object NoteSuggestions {
    const val MAX_SUGGESTIONS = 5
    const val REPLY_TOKENS = 220
    private const val MIN_CHARS = 4

    const val SYSTEM_PROMPT = "You pick out lasting facts about a person from what they said. You never invent anything."

    /** What the user said, newest last, cut from the old end to fit [maxChars]. Replies and summaries are left out. */
    fun transcript(turns: List<Turn>, maxChars: Int): String {
        val said = turns.filter { it.isUser && !it.summary && it.text.isNotBlank() }.map { it.text.trim() }
        val kept = ArrayList<String>()
        var used = 0
        for (text in said.asReversed()) {
            val piece = if (text.length > MAX_LINE) text.take(MAX_LINE) + "…" else text
            if (kept.isNotEmpty() && used + piece.length > maxChars) break
            kept += piece
            used += piece.length
        }
        return kept.asReversed().joinToString("\n") { "- " + it.replace(Regex("\\s+"), " ") }
    }

    private const val MAX_LINE = 600

    fun requestFor(transcript: String, known: List<String>): String = buildString {
        append("Here is what a person said in a conversation:\n").append(transcript).append("\n\n")
        append("List up to $MAX_SUGGESTIONS lasting facts about this person that would help in future conversations: ")
        append("preferences, background, work, goals, limits, and how they like answers. ")
        append("Write one short plain statement per line, for example \"Vegetarian.\" or \"Writes Kotlin for Android.\" ")
        append("Only include what the person said about themselves; no guesses, and nothing about the task they asked for. ")
        if (known.isNotEmpty()) {
            append("Leave out anything already remembered:\n")
            known.take(30).forEach { append("- ").append(it).append('\n') }
        }
        append("If there is nothing worth remembering, write exactly NONE.")
    }

    /** The facts in the model's [raw] answer: one per line, tidied, without repeats or what is [known] already. */
    fun parse(raw: String, known: List<String>): List<String> {
        val knownKeys = known.map(::key).filter { it.isNotEmpty() }
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (line in Thinking.answerOnly(raw).lines()) {
            val text = tidy(line)
            if (text.length < MIN_CHARS || text.endsWith(":")) continue
            if (key(text) == "none") continue
            val k = key(text)
            if (k.isEmpty() || !seen.add(k)) continue
            // The same fact worded a little differently counts as known: one contains the other.
            if (knownKeys.any { it == k || (minOf(it.length, k.length) >= 8 && (it in k || k in it)) }) continue
            out += text.take(MemoryNotes.MAX_NOTE_CHARS)
            if (out.size == MAX_SUGGESTIONS) break
        }
        return out
    }

    private fun tidy(line: String): String =
        line.trim()
            .replace(Regex("""^\s*(?:[-*•–]|\d+[.)])\s*"""), "")
            .trim().trim('"', '“', '”')
            .replace(Regex("\\s+"), " ")
            .trim()

    /** A comparison form: lower case letters and digits only. */
    private fun key(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }
}
