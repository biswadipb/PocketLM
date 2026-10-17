package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSuggestionsTest {

    @Test
    fun theUsersWordsAreListedAndRepliesAreLeftOut() {
        val turns = listOf(
            Turn(true, "I'm vegetarian and I write Kotlin."),
            Turn(false, "Great! Here is a recipe..."),
            Turn(true, "Thanks,\n  that\tworks."),
            Turn(true, "(summary text)", summary = true),
        )
        assertEquals("- I'm vegetarian and I write Kotlin.\n- Thanks, that works.", NoteSuggestions.transcript(turns, 10_000))
    }

    @Test
    fun whenTooLongTheOldestWordsGoFirst() {
        val turns = (1..10).map { Turn(true, "message number $it with some padding text") }
        val text = NoteSuggestions.transcript(turns, 120)
        assertTrue(text.contains("number 10"))
        assertFalse(text.contains("number 1 "))
        assertTrue(text.length <= 160)
    }

    @Test
    fun theRequestMentionsWhatIsAlreadyRemembered() {
        val request = NoteSuggestions.requestFor("- I like tea", listOf("Prefers metric units."))
        assertTrue(request.contains("- I like tea"))
        assertTrue(request.contains("Prefers metric units."))
        assertTrue(request.contains("NONE"))
        assertFalse(NoteSuggestions.requestFor("- x", emptyList()).contains("already remembered"))
    }

    @Test
    fun linesBecomeNotesWhateverBulletsTheModelUsed() {
        val raw = """
            Here are the facts:
            1. Vegetarian.
            - Writes Kotlin for Android.
            * "Prefers short answers."
            • Lives in Pune
        """.trimIndent()
        assertEquals(
            listOf("Vegetarian.", "Writes Kotlin for Android.", "Prefers short answers.", "Lives in Pune"),
            NoteSuggestions.parse(raw, emptyList()),
        )
    }

    @Test
    fun noneMeansNothingToRemember() {
        for (raw in listOf("NONE", "none.", "None", "  NONE\n", "- None", "")) {
            assertTrue(raw, NoteSuggestions.parse(raw, emptyList()).isEmpty())
        }
    }

    @Test
    fun whatIsKnownAlreadyOrRepeatedIsLeftOut() {
        val raw = "Vegetarian.\nvegetarian\nWrites Kotlin for Android apps.\nLikes tea."
        val known = listOf("Is vegetarian", "writes kotlin for android")
        // A repeat, a note that says the same as a known one in other words, and an extended wording are all dropped.
        assertEquals(listOf("Likes tea."), NoteSuggestions.parse(raw, known))
    }

    @Test
    fun atMostFiveAreOfferedAndEachFitsANote() {
        val raw = (1..9).joinToString("\n") { "Fact number $it about the person." } + "\n" + "x".repeat(900)
        val result = NoteSuggestions.parse(raw, emptyList())
        assertEquals(5, result.size)
        assertTrue(result.all { it.length <= MemoryNotes.MAX_NOTE_CHARS })
    }

    @Test
    fun theThinkingSectionIsNotMistakenForFacts() {
        val raw = "<think>\nThe user said they are vegetarian.\n</think>\n\nVegetarian."
        assertEquals(listOf("Vegetarian."), NoteSuggestions.parse(raw, emptyList()))
    }

    @Test
    fun shortScrapsAreIgnored() {
        assertEquals(listOf("Likes tea."), NoteSuggestions.parse("ok\n-\n.\nLikes tea.", emptyList()))
    }
}
