package com.pocketlm.app.data

import java.time.LocalDate

/**
 * Turning a reply or a whole chat into a note for the notes folder: a Markdown file with a small header that note apps
 * such as Obsidian understand, named after the day and the subject. Pure, so the rules can be tested; the file is
 * written by [NotesWriter].
 */
object NoteExport {
    /** The folder inside the notes folder that PocketLM writes to, so its notes stay together. */
    const val FOLDER = "PocketLM"

    private const val MAX_NAME_CHARS = 60

    /** [title] made safe to use in a file name on any storage, and short. Never empty. */
    fun safeTitle(title: String): String {
        val cleaned = title.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            .replace(Regex("""[\\/:*?"<>|#^\[\]\u0000-\u001F]"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim().trim('.', ' ')
        val short = if (cleaned.length <= MAX_NAME_CHARS) cleaned else {
            val cut = cleaned.take(MAX_NAME_CHARS + 1)
            val boundary = cut.lastIndexOf(' ')
            (if (boundary >= MAX_NAME_CHARS / 2) cut.take(boundary) else cut.take(MAX_NAME_CHARS)).trim()
        }
        return short.ifEmpty { "Chat" }
    }

    /** "2026-10-17 Title.md", with " 2", " 3"… added until it is not in [taken] (compared ignoring capitals). */
    fun fileName(title: String, date: LocalDate, taken: Set<String>): String {
        val base = "$date ${safeTitle(title)}"
        val used = taken.map { it.lowercase() }.toSet()
        var candidate = "$base.md"
        var n = 2
        while (candidate.lowercase() in used) candidate = "$base ${n++}.md"
        return candidate
    }

    /** Header for note apps: where it came from, when, and tags (spaces become hyphens, as note apps require). */
    private fun header(date: LocalDate, modelFile: String?, tags: List<String>): String = buildString {
        append("---\n")
        append("source: PocketLM\n")
        append("date: ").append(date).append("\n")
        modelFile?.removeSuffix(".gguf")?.takeIf { it.isNotBlank() }?.let {
            append("model: \"").append(it.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n")
        }
        val all = (listOf("pocketlm") + tags.map { it.trim().replace(Regex("\\s+"), "-").lowercase() }).distinct()
        append("tags: [").append(all.joinToString(", ")).append("]\n")
        append("---\n\n")
    }

    /** A whole chat as a note. */
    fun chatNote(title: String, messages: List<StoredMessage>, modelFile: String?, date: LocalDate, tags: List<String>): String =
        header(date, modelFile, tags) + ChatExport.toMarkdown(title, messages, modelFile = null, exportedAt = null)

    /** One reply as a note, with the question that led to it and the passages it was based on. */
    fun replyNote(
        subject: String,
        question: String?,
        reply: StoredMessage,
        modelFile: String?,
        date: LocalDate,
        tags: List<String>,
    ): String = buildString {
        append(header(date, modelFile, tags))
        append("# ").append(safeTitle(subject)).append("\n\n")
        if (!question.isNullOrBlank()) {
            append(question.trim().lines().joinToString("\n") { "> $it" }).append("\n\n")
        }
        append(Thinking.answerOnly(reply.text).trim()).append("\n")
        if (reply.sources.isNotEmpty()) append("\n_Sources: ").append(Sources.names(reply.sources)).append("_\n")
    }
}
