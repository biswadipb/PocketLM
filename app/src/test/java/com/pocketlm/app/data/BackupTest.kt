package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupTest {

    private lateinit var root: File

    private fun manager(name: String): BackupManager {
        val base = File(root, name)
        return BackupManager(File(base, "chats"), File(base, "images"), File(base, "docs")).also {
            File(base, "chats").mkdirs(); File(base, "images").mkdirs(); File(base, "docs").mkdirs()
        }
    }

    private fun dir(name: String, sub: String) = File(File(root, name), sub)

    @Before
    fun setUp() {
        root = Files.createTempDirectory("backup").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun chatJson(id: String, updatedAt: Long, image: String? = null, docId: String? = null): String {
        val msg = JSONObject().put("text", "hi").put("isUser", true).apply { image?.let { put("image", it) } }
        return JSONObject().put("id", id).put("title", "T $id").put("updatedAt", updatedAt)
            .put("messages", JSONArray().put(msg))
            .put("attachments", JSONArray().apply { docId?.let { put(JSONObject().put("id", it).put("name", "n.txt").put("chars", 3)) } })
            .toString()
    }

    private fun makeBackup(): ByteArray {
        val src = manager("src")
        File(dir("src", "images"), "photo.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(dir("src", "docs"), "doc-1.txt").writeText("document text")
        File(dir("src", "docs"), "unused.txt").writeText("not referenced")
        File(dir("src", "chats"), "chat-a.json").writeText(chatJson("chat-a", 100, image = "/old/phone/files/images/photo.jpg", docId = "doc-1"))
        File(dir("src", "chats"), "chat-b.json").writeText(chatJson("chat-b", 200))
        val out = ByteArrayOutputStream()
        val count = src.export(out, SettingsBackup.toJson(mapOf("theme_mode" to "Dark"), setOf("theme_mode")), createdAt = 5L)
        assertEquals(2, count)
        return out.toByteArray()
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test
    fun aBackupRestoresChatsPhotosDocumentsAndSettingsOnAnotherPhone() {
        val result = manager("dst").restore(ByteArrayInputStream(makeBackup()))
        assertEquals(2, result.chatsRestored)
        assertEquals(2, result.files)
        assertEquals("Dark", result.settings?.getJSONObject("theme_mode")?.getString("v"))
        assertEquals("document text", File(dir("dst", "docs"), "doc-1.txt").readText())
        assertTrue(File(dir("dst", "images"), "photo.jpg").isFile)
    }

    @Test
    fun onlyFilesThatChatsUseAreIncluded() {
        manager("dst").restore(ByteArrayInputStream(makeBackup()))
        assertFalse(File(dir("dst", "docs"), "unused.txt").exists())
    }

    @Test
    fun photoPathsAreRewrittenToThisPhonesStorage() {
        manager("dst").restore(ByteArrayInputStream(makeBackup()))
        val chat = JSONObject(File(dir("dst", "chats"), "chat-a.json").readText())
        val path = chat.getJSONArray("messages").getJSONObject(0).getString("image")
        assertEquals(File(dir("dst", "images"), "photo.jpg").absolutePath, path)
    }

    @Test
    fun anOlderBackupDoesNotOverwriteANewerChat() {
        val dst = manager("dst")
        File(dir("dst", "chats"), "chat-a.json").writeText(chatJson("chat-a", 999))
        val result = dst.restore(ByteArrayInputStream(makeBackup()))
        assertEquals(1, result.chatsRestored)
        assertEquals(1, result.chatsKept)
        assertEquals(999L, JSONObject(File(dir("dst", "chats"), "chat-a.json").readText()).getLong("updatedAt"))
    }

    @Test
    fun aNewerBackupReplacesAnOlderChat() {
        val dst = manager("dst")
        File(dir("dst", "chats"), "chat-b.json").writeText(chatJson("chat-b", 50))
        dst.restore(ByteArrayInputStream(makeBackup()))
        assertEquals(200L, JSONObject(File(dir("dst", "chats"), "chat-b.json").readText()).getLong("updatedAt"))
    }

    @Test
    fun entriesThatTryToEscapeTheFoldersAreIgnored() {
        val zip = zipOf(
            "backup.json" to """{"format":1}""",
            "chats/../../evil.json" to chatJson("evil", 1),
            "images/../evil.jpg" to "x",
            "docs/sub/evil.txt" to "x",
            "/etc/passwd" to "x",
            "chats/ok-1.json" to chatJson("ok-1", 1),
        )
        val result = manager("dst").restore(ByteArrayInputStream(zip))
        assertEquals(1, result.chatsRestored)
        assertFalse(File(root, "evil.json").exists())
        assertFalse(File(root, "dst/evil.jpg").exists())
        assertFalse(File(dir("dst", "docs"), "evil.txt").exists())
    }

    @Test
    fun filesOfTheWrongTypeAreIgnored() {
        val zip = zipOf("backup.json" to """{"format":1}""", "images/run.sh" to "x", "docs/run.exe" to "x", "chats/c.txt" to "x")
        val result = manager("dst").restore(ByteArrayInputStream(zip))
        assertEquals(0, result.chatsRestored + result.files)
    }

    @Test
    fun somethingThatIsNotABackupIsRefused() {
        fun refused(bytes: ByteArray) = try { manager("dst").restore(ByteArrayInputStream(bytes)); "accepted" } catch (e: IOException) { e.message!! }
        assertTrue(refused("just text".toByteArray()).contains("not a PocketLM backup"))
        assertTrue(refused(zipOf("chats/a.json" to "{}")).contains("not a PocketLM backup"))
        assertTrue(refused(zipOf("backup.json" to "{}")).contains("newer version"))
    }

    @Test
    fun aHugeEntryIsRefusedInsteadOfFillingTheDisk() {
        val big = "x".repeat(9_000_000)
        val zip = zipOf("backup.json" to """{"format":1}""", "chats/big.json" to big)
        try {
            manager("dst").restore(ByteArrayInputStream(zip))
            fail("expected a refusal")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("too large"))
        }
    }

    @Test
    fun settingsKeepTheirTypesAndOnlyAllowedKeysTravel() {
        val all = mapOf("a" to "text", "b" to 7, "c" to 1.5f, "d" to true, "secret" to "api-key-123", "e" to setOf("x"))
        val allowed = setOf("a", "b", "c", "d", "e")
        val json = SettingsBackup.toJson(all, allowed)
        assertFalse(json.has("secret"))
        assertFalse(json.has("e")) // unsupported value types are skipped

        val applied = LinkedHashMap<String, Any>()
        val n = SettingsBackup.apply(json, allowed) { k, v -> applied[k] = v }
        assertEquals(4, n)
        assertEquals(mapOf<String, Any>("a" to "text", "b" to 7, "c" to 1.5f, "d" to true), applied)
    }

    @Test
    fun restoringIgnoresKeysThatAreNotAllowed() {
        val json = JSONObject().put("api_key", JSONObject().put("t", "s").put("v", "attacker")).put("ok", JSONObject().put("t", "i").put("v", 3))
        val applied = HashMap<String, Any>()
        SettingsBackup.apply(json, setOf("ok")) { k, v -> applied[k] = v }
        assertEquals(mapOf<String, Any>("ok" to 3), applied)
        assertNull(applied["api_key"])
    }
}
