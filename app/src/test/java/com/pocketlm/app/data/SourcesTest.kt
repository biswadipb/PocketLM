package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesTest {

    private fun chunk(doc: String, text: String, index: Int = 0) = Chunk(doc, 0, index, text)

    @Test
    fun passagesBecomeReferencesWithRepeatsDropped() {
        val refs = Sources.of(
            attached = listOf(chunk("report.pdf", "Revenue grew."), chunk("report.pdf", "Revenue grew."), chunk("report.pdf", "Costs fell.", 1)),
            notes = listOf(chunk("projects/plan.md", "Ship in May.")),
        )
        assertEquals(listOf("report.pdf", "report.pdf", "projects/plan.md"), refs.map { it.name })
        assertEquals(listOf(false, false, true), refs.map { it.fromNotes })
        assertEquals("Costs fell.", refs[1].excerpt)
    }

    @Test
    fun noPassagesMeansNoSources() {
        assertTrue(Sources.of(emptyList(), emptyList()).isEmpty())
        assertTrue(Sources.of(listOf(chunk("a.txt", "   ")), emptyList()).isEmpty())
    }

    @Test
    fun aReplyKeepsAtMostSixLongExcerptsClipped() {
        val many = (1..10).map { chunk("doc$it.txt", "x".repeat(2_000) + it) }
        val refs = Sources.of(many, emptyList())
        assertEquals(Sources.MAX_PER_REPLY, refs.size)
        assertTrue(refs.all { it.excerpt.length == Sources.MAX_EXCERPT_CHARS })
    }

    @Test
    fun chipsAreNamedAfterTheFileAndNumberedWhenTheyRepeat() {
        val refs = Sources.of(
            listOf(chunk("a/plan.md", "one"), chunk("a/plan.md", "two", 1), chunk("notes.txt", "three"), chunk("b/plan.md", "four")),
            emptyList(),
        )
        assertEquals(listOf("plan.md 1", "plan.md 2", "notes.txt", "plan.md 3"), Sources.labels(refs))
    }

    @Test
    fun theNamesLineListsEachFileOnce() {
        val refs = listOf(SourceRef("a.md", true, "x"), SourceRef("a.md", true, "y"), SourceRef("b.pdf", false, "z"))
        assertEquals("a.md, b.pdf", Sources.names(refs))
    }

    @Test
    fun referencesSurviveSavingAndReading() {
        val refs = listOf(SourceRef("a.md", true, "Alpha\nbeta"), SourceRef("b.pdf", false, "Gamma"))
        val back = Sources.fromJson(JSONArray(Sources.toJson(refs).toString()))
        assertEquals(refs, back)
        assertFalse(Sources.toJson(refs).getJSONObject(1).has("notes"))
    }

    @Test
    fun damagedEntriesAreSkippedWhenReading() {
        val array = JSONArray()
            .put(JSONObject().put("text", "no name"))
            .put("not an object")
            .put(JSONObject().put("name", "ok.txt").put("text", "fine"))
        assertEquals(listOf("ok.txt"), Sources.fromJson(array).map { it.name })
        assertTrue(Sources.fromJson(null).isEmpty())
    }
}
