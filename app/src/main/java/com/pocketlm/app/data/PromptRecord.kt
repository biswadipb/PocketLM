package com.pocketlm.app.data

/** One piece of text handed to the model: [sent] is what PocketLM built, [wrapped] is it after the chat template. */
data class PromptStep(val title: String, val sent: String, val wrapped: String)

/** What the model was given for the most recent reply, for the prompt inspector. Kept in memory only. */
data class PromptRecord(val system: String, val steps: List<PromptStep>, val settings: String) {

    fun asText(): String = buildString {
        append("SETTINGS\n").append(settings).append("\n\n")
        append("SYSTEM PROMPT\n").append(system).append("\n")
        steps.forEachIndexed { i, step ->
            append("\n--- ").append(i + 1).append(". ").append(step.title).append(" ---\n")
            append(step.sent).append("\n")
            if (step.wrapped.isNotBlank() && step.wrapped != step.sent) {
                append("\n[as the model received it, after the chat template]\n").append(step.wrapped).append("\n")
            }
        }
    }.trimEnd() + "\n"

    companion object {
        /** Long texts are shortened in the middle so the inspector stays fast. */
        const val MAX_CHARS = 30_000

        fun clip(text: String): String =
            if (text.length <= MAX_CHARS) text
            else text.take(MAX_CHARS / 2) + "\n… (${text.length - MAX_CHARS} characters left out) …\n" + text.takeLast(MAX_CHARS / 2)
    }
}
