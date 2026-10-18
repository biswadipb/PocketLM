package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryNotesTest {

    @Test
    fun notesSurviveSavingAndLoading() {
        val notes = listOf(Note("1", "I'm vegetarian"), Note("2", "I write Kotlin", enabled = false))
        assertEquals(notes, MemoryNotes.fromJson(MemoryNotes.toJson(notes)))
    }

    @Test
    fun brokenOrMissingDataGivesNoNotes() {
        assertTrue(MemoryNotes.fromJson(null).isEmpty())
        assertTrue(MemoryNotes.fromJson("not json").isEmpty())
    }

    @Test
    fun theSystemPromptIsUnchangedWithoutNotes() {
        assertEquals("You help.", MemoryNotes.withNotes("You help.", emptyList()))
        assertEquals("You help.", MemoryNotes.withNotes("You help.", listOf(Note("1", "x", enabled = false))))
    }

    @Test
    fun enabledNotesAreListedAfterThePrompt() {
        val result = MemoryNotes.withNotes("You help.\n", listOf(Note("1", "I'm vegetarian"), Note("2", "off", false), Note("3", "I write Kotlin")))
        assertEquals("You help.\n\nThings to remember about the user:\n- I'm vegetarian\n- I write Kotlin", result)
    }

    @Test
    fun blankNotesAreSkippedAndSpacingIsTidied() {
        assertEquals(listOf("a b"), MemoryNotes.active(listOf(Note("1", "   "), Note("2", " a \n  b "))))
    }

    @Test
    fun aSingleNoteIsCut() {
        val note = Note("1", "x".repeat(MemoryNotes.MAX_NOTE_CHARS + 100))
        assertEquals(MemoryNotes.MAX_NOTE_CHARS, MemoryNotes.active(listOf(note)).single().length)
    }

    @Test
    fun theTotalSizeIsLimited() {
        val notes = (1..20).map { Note("$it", "n".repeat(280)) }
        val used = MemoryNotes.active(notes)
        assertTrue(used.sumOf { it.length } <= MemoryNotes.MAX_TOTAL_CHARS)
        assertFalse(used.size == 20)
    }
}
