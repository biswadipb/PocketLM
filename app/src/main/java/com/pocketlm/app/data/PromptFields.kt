package com.pocketlm.app.data

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Fields in a saved prompt that are filled in when it is inserted: `{{date}}`, `{{time}}`, `{{weekday}}`,
 * `{{clipboard}}`, and `{{ask: label}}` (or `{{ask: label = suggested answer}}`), which the app asks for. Anything else
 * in double braces is left exactly as written, so a prompt about code or templates is not damaged.
 */
object PromptFields {

    /** Something the person is asked for, with the answer offered in advance. */
    data class Ask(val label: String, val suggestion: String = "")

    private val field = Regex("""\{\{\s*([A-Za-z]+)\s*(?::([^{}]*))?\}\}""")

    private val plain = setOf("date", "time", "weekday", "clipboard")

    const val HELP = "Fields: {{date}}, {{time}}, {{weekday}}, {{clipboard}} and {{ask: question}} are filled in when you insert the prompt."

    private fun askOf(argument: String?): Ask? {
        val text = argument.orEmpty()
        val label = text.substringBefore('=').trim()
        if (label.isEmpty()) return null
        return Ask(label, if ('=' in text) text.substringAfter('=').trim() else "")
    }

    /** The questions to put to the person, in the order they first appear, one per label (capital letters ignored). */
    fun asks(text: String): List<Ask> {
        val seen = HashSet<String>()
        return field.findAll(text).mapNotNull { m ->
            if (m.groupValues[1].lowercase() != "ask") null else askOf(m.groups[2]?.value)
        }.filter { seen.add(it.label.lowercase()) }.toList()
    }

    fun usesClipboard(text: String): Boolean =
        field.findAll(text).any { it.groupValues[1].lowercase() == "clipboard" }

    /** True if [text] has anything to fill in. */
    fun hasFields(text: String): Boolean = field.findAll(text).any {
        val name = it.groupValues[1].lowercase()
        name in plain || (name == "ask" && askOf(it.groups[2]?.value) != null)
    }

    /**
     * [text] with its fields replaced. [answers] are the replies to [asks], by label (capital letters ignored); a missing
     * or empty answer falls back to the suggestion.
     */
    fun fill(
        text: String,
        answers: Map<String, String>,
        clipboard: String,
        now: ZonedDateTime,
        locale: Locale = Locale.getDefault(),
    ): String {
        val byLabel = answers.mapKeys { it.key.lowercase() }
        return field.replace(text) { m ->
            when (m.groupValues[1].lowercase()) {
                "date" -> DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(now)
                "time" -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(now)
                "weekday" -> DateTimeFormatter.ofPattern("EEEE", locale).format(now)
                "clipboard" -> clipboard
                "ask" -> askOf(m.groups[2]?.value)?.let { ask ->
                    byLabel[ask.label.lowercase()]?.trim()?.takeIf { it.isNotEmpty() } ?: ask.suggestion
                } ?: m.value
                else -> m.value
            }
        }
    }
}
