package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NoteExportTest {

    private val day = LocalDate.of(2026, 10, 17)

    @Test
    fun aTitleBecomesASafeFileName() {
        assertEquals("What is 2 3 4", NoteExport.safeTitle("What is 2/3: 4?"))
        assertEquals("Chat", NoteExport.safeTitle("???"))
        assertEquals("Chat", NoteExport.safeTitle(""))
        assertEquals("First line", NoteExport.safeTitle("First line\nsecond line"))
        assertEquals("Notes about C", NoteExport.safeTitle("Notes about C#"))
        assertFalse(NoteExport.safeTitle("a/b\\c").contains('/'))
    }

    @Test
    fun aLongTitleIsCutAtAWord() {
        val title = NoteExport.safeTitle("word ".repeat(40))
        assertTrue(title.length <= 60)
        assertFalse(title.endsWith(" "))
    }

    @Test
    fun fileNamesStartWithTheDayAndAreNeverReused() {
        assertEquals("2026-10-17 Plan.md", NoteExport.fileName("Plan", day, emptySet()))
        assertEquals("2026-10-17 Plan 2.md", NoteExport.fileName("Plan", day, setOf("2026-10-17 Plan.md")))
        assertEquals("2026-10-17 Plan 3.md", NoteExport.fileName("Plan", day, setOf("2026-10-17 plan.md", "2026-10-17 Plan 2.md")))
    }

    @Test
    fun aChatNoteHasAHeaderAndTheConversation() {
        val note = NoteExport.chatNote(
            "Trip", listOf(StoredMessage("Where?", true), StoredMessage("Lisbon", false)), "qwen-3b.gguf", day, listOf("big trip", "Fun"),
        )
        assertTrue(note, note.startsWith("---\nsource: PocketLM\ndate: 2026-10-17\nmodel: \"qwen-3b\"\ntags: [pocketlm, big-trip, fun]\n---\n\n# Trip"))
        assertTrue(note.contains("## You\n\nWhere?"))
        assertTrue(note.contains("## Piko\n\nLisbon"))
    }

    @Test
    fun aReplyNoteQuotesTheQuestionAndNamesTheSources() {
        val reply = StoredMessage("<think>hmm</think>The answer is 42.", false, sources = listOf(SourceRef("notes/a.md", true, "x"), SourceRef("b.pdf", false, "y")))
        val note = NoteExport.replyNote("Life", "What is\nthe answer?", reply, null, day, emptyList())
        assertTrue(note, note.startsWith("---\nsource: PocketLM\ndate: 2026-10-17\ntags: [pocketlm]\n---\n\n# Life\n\n> What is\n> the answer?\n\nThe answer is 42.\n"))
        assertTrue(note.endsWith("_Sources: notes/a.md, b.pdf_\n"))
        assertFalse(note.contains("hmm"))
    }

    @Test
    fun aReplyNoteWithoutAQuestionOrSourcesIsJustTheAnswer() {
        val note = NoteExport.replyNote("Idea", null, StoredMessage("Plant beans.", false), null, day, emptyList())
        assertTrue(note.endsWith("# Idea\n\nPlant beans.\n"))
    }

    @Test
    fun exportsListTheSourcesUnderEachReply() {
        val md = ChatExport.toMarkdown("T", listOf(StoredMessage("q", true), StoredMessage("a", false, sources = listOf(SourceRef("x.md", true, "t")))), null, null)
        assertTrue(md, md.contains("a\n\n_Sources: x.md_\n"))
    }
}
