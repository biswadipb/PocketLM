package com.pocketlm.app.data

import java.text.DateFormat
import java.util.Date

/** Turns a conversation into a Markdown document for sharing or saving. */
object ChatExport {

    fun toMarkdown(title: String, messages: List<StoredMessage>, modelFile: String?, exportedAt: Long?): String =
        buildString {
            append("# ").append(title.ifBlank { "PocketLM chat" }).append("\n\n")
            val details = listOfNotNull(
                modelFile?.removeSuffix(".gguf")?.let { "Model: $it" },
                exportedAt?.let { "Exported ${DateFormat.getDateTimeInstance().format(Date(it))}" },
            )
            if (details.isNotEmpty()) append("_").append(details.joinToString(" · ")).append("_\n\n")

            messages.filter { it.isUser || it.text.isNotBlank() }.forEach { m ->
                append(if (m.isUser) "## You\n\n" else "## PocketLM\n\n")
                if (m.imagePath != null) append("_[Photo attached]_\n\n")
                val parts = Thinking.split(m.text)
                if (!m.isUser && parts.thinking != null) {
                    append("<details>\n<summary>Thought process</summary>\n\n").append(parts.thinking).append("\n\n</details>\n\n")
                    if (parts.answer.isNotBlank()) append(parts.answer.trim()).append("\n\n")
                } else {
                    append(m.text.trim()).append("\n\n")
                }
            }
        }.trimEnd() + "\n"

    /** A safe file name such as "my-chat.md" for [title]. */
    fun fileName(title: String): String {
        val base = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
        return (base.ifEmpty { "pocketlm-chat" }) + ".md"
    }
}
