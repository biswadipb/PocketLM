package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesPromptTest {

    private fun chunk(name: String, text: String) = Chunk(name, 0, 0, text)

    @Test
    fun withNoExcerptsTheQuestionIsUnchanged() {
        assertEquals("What now?", NotesRules.excerptsPrompt("What now?", emptyList(), emptyList()))
    }

    @Test
    fun attachedDocumentsAreLabelledByName() {
        val prompt = NotesRules.excerptsPrompt("Q?", listOf(chunk("report.pdf", "Revenue grew.")), emptyList())
        assertTrue(prompt.contains("attached documents"))
        assertFalse(prompt.contains("and notes"))
        assertTrue(prompt.contains("[report.pdf]\nRevenue grew."))
        assertTrue(prompt.endsWith("Question: Q?"))
    }

    @Test
    fun notesAreLabelledByTheirPathInTheFolder() {
        val prompt = NotesRules.excerptsPrompt("Q?", emptyList(), listOf(chunk("Projects/boat.md", "Use teak.")))
        assertTrue(prompt.contains("user's notes."))
        assertTrue(prompt.contains("[notes: Projects/boat.md]\nUse teak."))
    }

    @Test
    fun bothKindsCanSitSideBySide() {
        val prompt = NotesRules.excerptsPrompt("Q?", listOf(chunk("a.txt", "A")), listOf(chunk("n.md", "N")))
        assertTrue(prompt.contains("attached documents and notes"))
        assertTrue(prompt.indexOf("[a.txt]") < prompt.indexOf("[notes: n.md]"))
        assertTrue(prompt.contains("If they don't contain the answer, say so."))
    }

    @Test
    fun theChoiceToSearchNotesIsKeptWithTheChatOnlyWhenItDiffersFromTheDefault() {
        val follows = SavedChat("x", "t", 1, null, listOf(StoredMessage("hi", true)))
        assertFalse(ChatCodec.encode(follows).has("useNotes"))
        assertNull(ChatCodec.decode(ChatCodec.encode(follows).toString()).useNotes)

        val off = follows.copy(useNotes = false)
        assertEquals(false, ChatCodec.encode(off).getBoolean("useNotes"))
        assertEquals(off, ChatCodec.decode(ChatCodec.encode(off).toString()))
        val on = follows.copy(useNotes = true)
        assertEquals(on, ChatCodec.decode(ChatCodec.encode(on).toString()))
    }
}
