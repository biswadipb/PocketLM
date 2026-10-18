package com.pocketlm.app.data

/** Decides what to do with something another app shares into PocketLM. Pure, so it can be tested. */
object ShareIntake {

    sealed interface TextPlan {
        /** Plain text for the message box. */
        data class Message(val text: String) : TextPlan

        /** A link to a web page: it is attached like a page added from the paperclip menu. */
        data class Page(val url: String) : TextPlan
    }

    private val url = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** How much text may sit around a link before it is treated as a message that merely mentions one. */
    private const val MAX_TITLE_CHARS = 120

    /**
     * Shared text that is just a link (many apps add the page title or a short line next to it) is a page to read;
     * anything else is text for the message box.
     */
    fun classifyText(text: String): TextPlan {
        val trimmed = text.trim()
        val links = url.findAll(trimmed).toList()
        if (links.size == 1) {
            val link = links.single().value.trimEnd('.', ',', ')', ';', '!')
            val rest = trimmed.replace(links.single().value, " ").trim()
            if (rest.length <= MAX_TITLE_CHARS && rest.lines().size <= 2) return TextPlan.Page(link)
        }
        return TextPlan.Message(trimmed)
    }

    /** What to put in the message box after something was attached, so one tap sends a useful question. */
    fun suggestedQuestion(kind: Kind): String = when (kind) {
        Kind.Page -> "Summarise this page."
        Kind.Pdf -> "Summarise this document."
        Kind.Image -> "Describe this image."
        Kind.Document -> "Summarise this document."
    }

    enum class Kind { Page, Pdf, Image, Document }

    fun kindOf(mime: String?): Kind = when {
        mime == null -> Kind.Document
        mime.startsWith("image/") -> Kind.Image
        mime == "application/pdf" -> Kind.Pdf
        else -> Kind.Document
    }

    /** At most this many shared files are taken at once. */
    const val MAX_FILES = 5
}
