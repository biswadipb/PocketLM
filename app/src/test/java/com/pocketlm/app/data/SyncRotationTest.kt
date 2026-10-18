package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SyncRotationTest {

    private lateinit var root: File
    private val backend = MemoryBackend()
    private lateinit var header: SyncHeader
    private lateinit var key: ByteArray
    private var now = 5_000_000L

    private inner class Phone(val name: String) {
        val chats = File(root, "$name/chats").also { it.mkdirs() }
        val images = File(root, "$name/images").also { it.mkdirs() }
        val docs = File(root, "$name/docs").also { it.mkdirs() }
        val state = SyncStateStore(File(root, "$name/state.json"))

        fun engine(h: SyncHeader, k: ByteArray) = SyncEngine(chats, images, docs, backend, h, k, state) { now }

        fun write(id: String, title: String, updatedAt: Long, image: String? = null) {
            val messages = JSONArray().put(JSONObject().put("text", "text of $title").put("isUser", true))
            if (image != null) {
                File(images, image).writeBytes(byteArrayOf(9, 8, 7))
                messages.put(JSONObject().put("text", "pic").put("isUser", true).put("image", File(images, image).absolutePath))
            }
            File(chats, "$id.json").writeText(
                JSONObject().put("id", id).put("title", title).put("updatedAt", updatedAt).put("modelFile", JSONObject.NULL)
                    .put("messages", messages).put("attachments", JSONArray()).toString(),
            )
        }

        fun ids() = chats.listFiles { f -> f.extension == "json" }.orEmpty().map { it.nameWithoutExtension }.toSet()
        fun rotation(oldKey: ByteArray) = SyncRotation(chats, backend, oldKey, state)
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("rotate").toFile()
        val made = SyncCrypto.newHeader("the first passphrase".toCharArray(), 1_000)
        header = made.first
        key = made.second
        backend.put(SyncNames.HEADER, header.toJson().toByteArray())
        backend.puts.clear()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun manifestWith(k: ByteArray): SyncManifest? =
        backend.files[SyncNames.MANIFEST]?.let { SyncManifest.fromJson(String(SyncCrypto.open(k, SyncNames.MANIFEST, it))) }

    @Test
    fun afterTheChangeTheOldPassphraseOpensNothingAndTheNewOneOpensEverything() {
        val a = Phone("a")
        a.write("chat-1", "One", 100, image = "pic.jpg")
        a.write("chat-2", "Two", 200)
        a.engine(header, key).run()
        val filesBefore = backend.files.keys.filter { it != SyncNames.HEADER }.toSet()
        assertTrue(filesBefore.isNotEmpty())

        var saved: Pair<SyncHeader, ByteArray>? = null
        val result = a.rotation(key).rotate("a brand new passphrase".toCharArray(), iterations = 1_000) { h, k -> saved = h to k }
        assertTrue(result.filesRemoved > 0)
        val (newHeader, newKey) = saved!!
        // Nothing is left that the old key can read.
        for ((name, bytes) in backend.files) {
            if (name == SyncNames.HEADER) continue
            try {
                SyncCrypto.open(key, name, bytes)
                fail("$name can still be read with the old key")
            } catch (e: SyncException) {
                // expected
            }
        }
        assertFalse(SyncCrypto.keyMatches(SyncHeader.fromJson(String(backend.files.getValue(SyncNames.HEADER)), 1), key))
        assertArrayEquals(newKey, SyncCrypto.keyFor(SyncHeader.fromJson(String(backend.files.getValue(SyncNames.HEADER)), 1), "a brand new passphrase".toCharArray()))

        // This phone uploads everything again under the new key, and a new phone can then join.
        val report = a.engine(newHeader, newKey).run()
        assertEquals(2, report.uploaded)
        val b = Phone("b")
        assertEquals(2, b.engine(newHeader, newKey).run().downloaded)
        assertEquals(setOf("chat-1", "chat-2"), b.ids())
        assertArrayEquals(byteArrayOf(9, 8, 7), File(b.images, "pic.jpg").readBytes())
    }

    @Test
    fun deletionsSurviveTheChange() {
        val a = Phone("a"); val b = Phone("b")
        a.write("keep", "Keep", 100)
        a.write("gone", "Gone", 100)
        a.engine(header, key).run()
        b.engine(header, key).run()
        // B deletes a chat and syncs; A has not heard yet.
        now += 10
        b.state.addTombstone("gone", now, emptyList())
        File(b.chats, "gone.json").delete()
        b.engine(header, key).run()
        // A (which still has the chat) syncs, learns of the deletion, then changes the passphrase.
        a.engine(header, key).run()
        assertEquals(setOf("keep"), a.ids())

        var saved: Pair<SyncHeader, ByteArray>? = null
        a.rotation(key).rotate("a brand new passphrase".toCharArray(), 1_000) { h, k -> saved = h to k }
        val (newHeader, newKey) = saved!!
        assertTrue(manifestWith(newKey)!!.chats.getValue("gone").deleted)

        // A third phone that still has the deleted chat cannot bring it back.
        val c = Phone("c")
        c.write("gone", "Gone", 100)
        c.engine(newHeader, newKey).run()
        assertTrue("the deleted chat must not come back", c.ids().isEmpty())
        // Once A has uploaded again, C receives what is left.
        a.engine(newHeader, newKey).run()
        c.engine(newHeader, newKey).run()
        assertEquals(setOf("keep"), a.ids())
        assertEquals(setOf("keep"), c.ids())
    }

    @Test
    fun anotherPhoneIsToldThePassphraseChangedAndKeepsItsChats() {
        val a = Phone("a"); val b = Phone("b")
        a.write("chat-1", "One", 100)
        a.engine(header, key).run()
        b.engine(header, key).run()
        a.rotation(key).rotate("a brand new passphrase".toCharArray(), 1_000) { _, _ -> }
        b.write("chat-b", "Written on B", 300)
        try {
            b.engine(header, key).run()
            fail("the old passphrase should be refused")
        } catch (e: SyncPassphraseChangedException) {
            assertTrue(e.message!!.contains("Enter the new passphrase"))
        }
        assertEquals(setOf("chat-1", "chat-b"), b.ids())
    }

    @Test
    fun theSettingsAndOrphanedFilesGoToo() {
        val a = Phone("a")
        a.write("chat-1", "One", 100)
        a.engine(header, key).run(SyncOptions(settings = FakeSettings(mutableMapOf("temp" to "0.5"))))
        backend.files["i-orphan.jpg.bin"] = byteArrayOf(1, 2, 3)
        backend.files["somebody-elses-file.txt"] = byteArrayOf(4)
        a.rotation(key).rotate("a brand new passphrase".toCharArray(), 1_000) { _, _ -> }
        assertFalse(backend.files.containsKey(SyncNames.SETTINGS))
        assertFalse(backend.files.containsKey("i-orphan.jpg.bin"))
        assertFalse(backend.files.containsKey("c-chat-1.bin"))
        assertTrue(backend.files.containsKey("somebody-elses-file.txt"))
        assertNull(manifestWith(key.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }).let { null })
        assertNotNull(backend.files[SyncNames.HEADER])
    }

    @Test
    fun aFailureBeforeAnythingIsRemovedChangesNothing() {
        val a = Phone("a")
        a.write("chat-1", "One", 100)
        a.engine(header, key).run()
        val before = HashMap(backend.files)
        try {
            a.rotation(key).rotate("a brand new passphrase".toCharArray(), iterations = 0) { _, _ -> fail("must not be saved") }
            fail("an unusable setting should be refused")
        } catch (e: Exception) {
            // key derivation fails before any file is touched
        }
        assertEquals(before.keys, backend.files.keys)
        for ((name, bytes) in before) assertArrayEquals(name, bytes, backend.files[name])
    }

    @Test
    fun thePhoneForgetsWhatItHadUploadedSoMediaIsSentAgain() {
        val a = Phone("a")
        a.write("chat-1", "One", 100, image = "pic.jpg")
        a.engine(header, key).run()
        assertTrue(a.state.get().uploaded.contains("i-pic.jpg.bin"))
        var saved: Pair<SyncHeader, ByteArray>? = null
        a.rotation(key).rotate("a brand new passphrase".toCharArray(), 1_000) { h, k -> saved = h to k }
        assertTrue(a.state.get().uploaded.isEmpty())
        a.engine(saved!!.first, saved!!.second).run()
        assertTrue(backend.files.containsKey("i-pic.jpg.bin"))
    }
}
