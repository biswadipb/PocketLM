package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesContextTest {

    private val manifest = mapOf(
        "old.md" to NoteStamp("old.md", 10, 1_000),
        "new.md" to NoteStamp("new.md", 10, 5_000),
        "newest.md" to NoteStamp("newest.md", 10, 9_000),
        "unknown.md" to NoteStamp("unknown.md", 10, 0),
    )
    private val texts = mapOf("old.md" to "Old text", "new.md" to "New text", "newest.md" to "Newest text", "unknown.md" to "Unknown time")

    private fun recent(since: Long, budget: Int = 10_000) =
        NotesContext.recent(manifest, texts::get, since, "yesterday", budget, "Summarise them.")

    @Test
    fun onlyNotesChangedSinceThenAreUsedNewestFirst() {
        val built = recent(2_000)!!
        assertEquals(2, built.notes)
        val prompt = built.prompt
        assertTrue(prompt, prompt.startsWith("These are the notes that changed since yesterday (2 notes):"))
        assertTrue(prompt.indexOf("[notes: newest.md]") < prompt.indexOf("[notes: new.md]"))
        assertTrue(prompt.endsWith("Summarise them."))
        assertTrue("old.md" !in prompt && "unknown.md" !in prompt)
        assertEquals(listOf("newest.md", "new.md"), built.sources.map { it.name })
        assertTrue(built.sources.all { it.fromNotes })
    }

    @Test
    fun nothingChangedMeansNoPromptAtAll() {
        assertNull(recent(9_000))
        assertNull(NotesContext.recent(emptyMap(), { null }, 0, "x", 1_000, "t"))
    }

    @Test
    fun aSingleNoteIsDescribedInTheSingular() {
        assertTrue(recent(8_000)!!.prompt.contains("(1 note)"))
    }

    @Test
    fun aLongNoteIsClippedAndEmptyOnesSkipped() {
        val long = mapOf("a.md" to "x".repeat(10_000), "b.md" to "   ")
        val stamps = mapOf("a.md" to NoteStamp("a.md", 1, 10), "b.md" to NoteStamp("b.md", 1, 20))
        val built = NotesContext.recent(stamps, long::get, 0, "then", 100_000, "Task")!!
        assertEquals(1, built.notes)
        assertTrue(built.prompt.length < NotesContext.PER_NOTE_CHARS + 300)
    }

    @Test
    fun theBudgetStopsTheListButAlwaysKeepsTheFirstNote() {
        val big = (1..5).associate { "n$it.md" to NoteStamp("n$it.md", 1, it * 100L) }
        val body = big.keys.associateWith { "y".repeat(2_000) }
        val tight = NotesContext.recent(big, body::get, 0, "then", 2_500, "Task")!!
        assertEquals(1, tight.notes)
        val roomy = NotesContext.recent(big, body::get, 0, "then", 100_000, "Task")!!
        assertEquals(5, roomy.notes)
    }

    @Test
    fun searchingGivesTheBestPassagesWithTheirSources() {
        val index = Bm25Index.build(
            listOf(
                "bread.md" to "The sourdough starter needs feeding daily with flour and water.",
                "taxes.md" to "File the quarterly tax return before the fifteenth.",
            ),
        )
        val built = NotesContext.search(index, "how often to feed the sourdough starter", 2_000)!!
        assertTrue(built.prompt, built.prompt.contains("[notes: bread.md]"))
        assertEquals("bread.md", built.sources.first().name)
        assertNotNull(built.sources.firstOrNull { it.excerpt.contains("sourdough") })
    }

    @Test
    fun searchingWithoutNotesGivesNothing() {
        assertNull(NotesContext.search(null, "anything", 1_000))
    }
}
