package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class PromptFieldsTest {

    private val now = ZonedDateTime.of(2026, 10, 17, 9, 5, 0, 0, ZoneId.of("UTC"))

    private fun fill(text: String, answers: Map<String, String> = emptyMap(), clipboard: String = "") =
        PromptFields.fill(text, answers, clipboard, now, Locale.UK)

    @Test
    fun questionsAreListedOnceEachInTheOrderTheyAppear() {
        val asks = PromptFields.asks("Write a {{ask: tone}} note to {{ask: Name}} about {{ask: topic = rain}}. Keep the {{ask: TONE}}.")
        assertEquals(listOf("tone", "Name", "topic"), asks.map { it.label })
        assertEquals("rain", asks.last().suggestion)
    }

    @Test
    fun anEmptyQuestionIsNotAQuestion() {
        assertTrue(PromptFields.asks("{{ask:}} {{ask}} {{ask: }}").isEmpty())
        assertEquals("{{ask:}} {{ask}}", fill("{{ask:}} {{ask}}"))
    }

    @Test
    fun aQuestionTakesTheAnswerGivenIgnoringCapitals() {
        assertEquals("A formal note", fill("A {{ask: Tone}} note", mapOf("tone" to " formal ")))
    }

    @Test
    fun anUnansweredQuestionFallsBackToItsSuggestionOrNothing() {
        assertEquals("Ideas for rain", fill("Ideas for {{ask: topic = rain}}"))
        assertEquals("Ideas for rain", fill("Ideas for {{ask: topic = rain}}", mapOf("topic" to "  ")))
        assertEquals("Ideas for ", fill("Ideas for {{ask: topic}}"))
    }

    @Test
    fun theDateAndTimeUseTheGivenMoment() {
        assertEquals("Today is 17 October 2026.", fill("Today is {{date}}."))
        assertEquals("It is Saturday.", fill("It is {{weekday}}."))
        assertEquals("09:05", fill("{{time}}"))
    }

    @Test
    fun fieldNamesIgnoreCapitalsAndSpaces() {
        assertEquals("Saturday Saturday", fill("{{WEEKDAY}} {{ weekday }}"))
    }

    @Test
    fun theClipboardIsPutInAsItIs() {
        assertEquals("Reply to: Hi \$1 {{date}}", fill("Reply to: {{clipboard}}", clipboard = "Hi \$1 {{date}}"))
        assertEquals("Reply to: ", fill("Reply to: {{clipboard}}"))
    }

    @Test
    fun otherDoubleBracesAreLeftAlone() {
        val code = "const x = {{ y }}; {{#each items}} {{name}} {{/each}} {{ask: a}}"
        assertEquals("const x = {{ y }}; {{#each items}} {{name}} {{/each}} b", fill(code, mapOf("a" to "b")))
    }

    @Test
    fun itKnowsWhatNeedsDoing() {
        assertTrue(PromptFields.hasFields("Today is {{date}}"))
        assertTrue(PromptFields.hasFields("{{ask: why}}"))
        assertFalse(PromptFields.hasFields("{{ask:}} and {{other}}"))
        assertFalse(PromptFields.hasFields("plain text"))
        assertTrue(PromptFields.usesClipboard("x {{clipboard}} y"))
        assertFalse(PromptFields.usesClipboard("x {{date}} y"))
    }
}
