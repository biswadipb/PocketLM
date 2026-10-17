package com.pocketlm.app.data

import com.pocketlm.app.data.NotesRules.Kind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NotesFolderTest {

    @Test
    fun onlyNotesAreRead() {
        assertEquals(Kind.Text, NotesRules.kind("ideas.md"))
        assertEquals(Kind.Text, NotesRules.kind("Journal 2024.TXT"))
        assertEquals(Kind.Text, NotesRules.kind("data.csv"))
        assertEquals(Kind.Html, NotesRules.kind("saved page.html"))
        assertEquals(Kind.Pdf, NotesRules.kind("paper.PDF"))
        assertNull(NotesRules.kind("photo.jpg"))
        assertNull(NotesRules.kind("archive.zip"))
        assertNull(NotesRules.kind("README"))
        assertNull(NotesRules.kind("script.sh"))
    }

    @Test
    fun hiddenThingsAndAppFoldersAreSkipped() {
        assertTrue(NotesRules.skipName(".hidden.md", isFolder = false))
        assertTrue(NotesRules.skipName(".git", isFolder = true))
        assertTrue(NotesRules.skipName("node_modules", isFolder = true))
        assertTrue(NotesRules.skipName(".obsidian", isFolder = true))
        assertFalse(NotesRules.skipName("Projects", isFolder = true))
        assertFalse(NotesRules.skipName("node_modules.md", isFolder = false))
    }

    @Test
    fun sizeLimitsDifferForPdfs() {
        assertTrue(NotesRules.maxBytes(Kind.Pdf) > NotesRules.maxBytes(Kind.Text))
    }

    private fun stamp(path: String, size: Long = 10, modified: Long = 1) = NoteStamp(path, size, modified)

    @Test
    fun aRescanReadsOnlyWhatChanged() {
        val known = mapOf("a.md" to stamp("a.md"), "b.md" to stamp("b.md"), "gone.md" to stamp("gone.md"))
        val scanned = listOf(stamp("a.md"), stamp("b.md", modified = 2), stamp("new.md"))
        val plan = NotesRules.plan(known, scanned)
        assertEquals(listOf(stamp("b.md", modified = 2), stamp("new.md")), plan.read)
        assertEquals(listOf("gone.md"), plan.drop)
        assertEquals(listOf("a.md"), plan.keep)
    }

    @Test
    fun aFirstScanReadsEverythingAndAnUnchangedOneNothing() {
        val files = listOf(stamp("a.md"), stamp("b.md"))
        assertEquals(files, NotesRules.plan(emptyMap(), files).read)
        val again = NotesRules.plan(files.associateBy { it.path }, files)
        assertTrue(again.read.isEmpty() && again.drop.isEmpty())
        assertEquals(2, again.keep.size)
    }

    @Test
    fun savedFileNamesAreSafeAndStable() {
        val id = NotesRules.fileId("../../etc/passwd")
        assertTrue(Regex("[0-9a-f]{24}").matches(id))
        assertEquals(id, NotesRules.fileId("../../etc/passwd"))
        assertTrue(id != NotesRules.fileId("../../etc/passwd2"))
    }

    @Test
    fun theStatusLineIsPlain() {
        assertEquals("1 file, 1 passage", NotesRules.statusLine(1, 1, false))
        assertEquals("142 files, 3980 passages, searchable by meaning", NotesRules.statusLine(142, 3980, true))
    }

    // ---- The saved copy ------------------------------------------------------------------

    private lateinit var root: File
    private lateinit var store: KnowledgeStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("knowledge").toFile()
        store = KnowledgeStore(File(root, "knowledge"))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun whatIsSavedComesBack() {
        store.writeText("Projects/idea.md", "Build a boat.")
        store.writeText("todo.txt", "Buy sails.")
        store.saveManifest(listOf(stamp("todo.txt"), stamp("Projects/idea.md")))
        assertEquals(setOf("todo.txt", "Projects/idea.md"), store.manifest().keys)
        assertEquals("Build a boat.", store.readText("Projects/idea.md"))
        assertEquals(listOf("Projects/idea.md" to "Build a boat.", "todo.txt" to "Buy sails."), store.documents())
        assertEquals("Build a boat.Buy sails.".length.toLong(), store.totalChars())
    }

    @Test
    fun changingATextForgetsItsOldVectors() {
        store.writeText("a.md", "old")
        store.writeVectors("a.md", byteArrayOf(1, 2, 3))
        assertEquals(3, store.readVectors("a.md")!!.size)
        store.writeText("a.md", "new")
        assertNull(store.readVectors("a.md"))
    }

    @Test
    fun vectorsAreNotSavedForATextThatIsNotThere() {
        store.writeVectors("missing.md", byteArrayOf(1))
        assertNull(store.readVectors("missing.md"))
    }

    @Test
    fun deletingAFileForgetsItsTextAndVectors() {
        store.writeText("a.md", "x")
        store.writeVectors("a.md", byteArrayOf(1))
        store.delete("a.md")
        assertNull(store.readText("a.md"))
        assertNull(store.readVectors("a.md"))
    }

    @Test
    fun forgettingTheFolderLeavesNothingBehind() {
        store.writeText("a.md", "secret notes")
        store.saveManifest(listOf(stamp("a.md")))
        store.clear()
        assertTrue(store.manifest().isEmpty())
        assertFalse(File(root, "knowledge").exists())
    }

    @Test
    fun aDamagedIndexIsTreatedAsEmpty() {
        File(root, "knowledge").mkdirs()
        File(root, "knowledge/index.json").writeText("{ broken")
        assertTrue(store.manifest().isEmpty())
    }
}
