package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** A sync location kept in memory, with a count of what was done to it. */
class MemoryBackend : SyncBackend {
    val files = LinkedHashMap<String, ByteArray>()
    val puts = ArrayList<String>()
    val deletes = ArrayList<String>()
    var failAfterPuts = -1

    override val label = "memory"
    override fun list() = files.map { RemoteFile(it.key, it.value.size.toLong()) }
    override fun get(name: String) = files[name]?.copyOf()
    override fun put(name: String, data: ByteArray) {
        if (failAfterPuts >= 0 && puts.size >= failAfterPuts) throw IOException("The connection dropped")
        files[name] = data.copyOf()
        puts += name
    }
    override fun delete(name: String) {
        files.remove(name)
        deletes += name
    }
}

class FakeSettings(var values: MutableMap<String, String> = mutableMapOf(), var editedAt: Long = 1) : SyncSettings {
    var applied = 0
    override fun fingerprint() = SettingsSync.fingerprint(values, values.keys)
    override fun changedAt() = editedAt
    override fun export() = JSONObject(values as Map<*, *>).toString()
    override fun apply(json: String) {
        val o = JSONObject(json)
        values = o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
        applied++
    }
}

class SyncEngineTest {

    private lateinit var root: File
    private val backend = MemoryBackend()
    private lateinit var header: SyncHeader
    private lateinit var key: ByteArray
    private var now = 1_000_000L

    /** One phone: its own folders, its own sync memory, a clock it can share with the others. */
    private inner class Phone(val name: String) {
        val chats = File(root, "$name/chats").also { it.mkdirs() }
        val images = File(root, "$name/images").also { it.mkdirs() }
        val docs = File(root, "$name/docs").also { it.mkdirs() }
        val state = SyncStateStore(File(root, "$name/sync_state.json"))
        val removed = ArrayList<String>()

        fun engine(b: SyncBackend = backend, k: ByteArray = key, h: SyncHeader = header) =
            SyncEngine(chats, images, docs, b, h, k, state) { now }

        fun sync(settings: SyncSettings? = null, busy: Set<String> = emptySet()): SyncReport =
            engine().run(SyncOptions(settings = settings, busyChatIds = busy, onChatChanged = { removed += it }))

        fun write(id: String, title: String, updatedAt: Long, image: String? = null, doc: String? = null, text: String = "hello from $name") {
            val messages = JSONArray().put(JSONObject().put("text", text).put("isUser", true))
            if (image != null) {
                File(images, image).writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7) + image.toByteArray())
                messages.put(JSONObject().put("text", "photo").put("isUser", true).put("image", File(images, image).absolutePath))
            }
            val attachments = JSONArray()
            if (doc != null) {
                File(docs, "$doc.txt").writeText("document $doc: " + "words ".repeat(200))
                attachments.put(JSONObject().put("id", doc).put("name", "$doc.txt").put("chars", 100))
            }
            File(chats, "$id.json").writeText(
                JSONObject().put("id", id).put("title", title).put("updatedAt", updatedAt).put("modelFile", JSONObject.NULL)
                    .put("messages", messages).put("attachments", attachments).toString(),
            )
        }

        fun chat(id: String): JSONObject? = File(chats, "$id.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) }
        fun title(id: String): String? = chat(id)?.getString("title")
        fun ids(): Set<String> = chats.listFiles { f -> f.extension == "json" }.orEmpty().map { it.nameWithoutExtension }.toSet()

        /** What the app does when the user deletes a chat. */
        fun delete(id: String) {
            val chat = chat(id)!!
            val media = SyncChats.media(chat)
            state.addTombstone(id, now, media.images.map(SyncNames::image) + media.docs.map(SyncNames::doc))
            media.images.forEach { File(images, it).delete() }
            media.docs.forEach { File(docs, "$it.txt").delete() }
            File(chats, "$id.json").delete()
        }
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("sync").toFile()
        val made = SyncCrypto.newHeader("our shared passphrase".toCharArray(), 1_000)
        header = made.first
        key = made.second
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun tick(ms: Long = 1_000) { now += ms }

    // ---- First sync ----------------------------------------------------------------

    @Test
    fun aFirstSyncSendsEveryChatAndWritesAnIndex() {
        val a = Phone("a")
        a.write("chat-1", "First", 100)
        a.write("chat-2", "Second", 200)
        val report = a.sync()
        assertEquals(2, report.uploaded)
        assertEquals(0, report.downloaded)
        assertTrue(backend.files.containsKey(SyncNames.HEADER))
        assertTrue(backend.files.containsKey(SyncNames.MANIFEST))
        assertTrue(backend.files.containsKey("c-chat-1.bin"))
        assertTrue(backend.files.containsKey("c-chat-2.bin"))
        assertEquals(now, a.state.get().lastSyncAt)
    }

    @Test
    fun nothingReadableLeavesThePhone() {
        val a = Phone("a")
        a.write("chat-1", "My secret diary", 100, image = "pic.jpg", doc = "doc-1", text = "dear diary, the password is hunter2")
        a.sync()
        for ((name, bytes) in backend.files) {
            if (name == SyncNames.HEADER) continue
            val text = String(bytes, Charsets.ISO_8859_1)
            assertFalse(name, text.contains("diary"))
            assertFalse(name, text.contains("hunter2"))
            assertFalse(name, text.contains("words words"))
            assertFalse(name, text.contains("chat-1"))
        }
        assertFalse(backend.files.getValue(SyncNames.HEADER).toString(Charsets.UTF_8).contains("passphrase"))
    }

    @Test
    fun aSecondPhoneReceivesChatsWithTheirPhotosAndDocuments() {
        val a = Phone("a")
        a.write("chat-1", "With media", 100, image = "pic.jpg", doc = "doc-1")
        a.sync()

        val b = Phone("b")
        val report = b.sync()
        assertEquals(1, report.downloaded)
        assertEquals("With media", b.title("chat-1"))
        assertArrayEquals(File(a.images, "pic.jpg").readBytes(), File(b.images, "pic.jpg").readBytes())
        assertEquals(File(a.docs, "doc-1.txt").readText(), File(b.docs, "doc-1.txt").readText())
        // The photo path points into the second phone's own folder, not the first one's.
        val imagePath = b.chat("chat-1")!!.getJSONArray("messages").getJSONObject(1).getString("image")
        assertEquals(File(b.images, "pic.jpg").absolutePath, imagePath)
        assertEquals(listOf("chat-1"), b.removed)
    }

    @Test
    fun theUpdatedTimeIsKeptSoTheChatIsNotSentBackAgain() {
        val a = Phone("a")
        a.write("chat-1", "One", 12345)
        a.sync()
        val b = Phone("b")
        b.sync()
        assertEquals(12345L, b.chat("chat-1")!!.getLong("updatedAt"))
        backend.puts.clear()
        val again = b.sync()
        assertEquals(0, again.uploaded + again.downloaded)
        assertTrue(backend.puts.isEmpty())
    }

    @Test
    fun syncingAgainWithNothingNewTouchesNothing() {
        val a = Phone("a")
        a.write("chat-1", "One", 100, image = "pic.jpg")
        a.sync()
        backend.puts.clear(); backend.deletes.clear()
        val report = a.sync()
        assertEquals(0, report.uploaded + report.downloaded + report.removedHere + report.removedThere)
        assertEquals("Everything is up to date", report.summary())
        assertTrue(backend.puts.isEmpty())
        assertTrue(backend.deletes.isEmpty())
    }

    @Test
    fun photosAndDocumentsAreSentOnlyOnce() {
        val a = Phone("a")
        a.write("chat-1", "One", 100, image = "pic.jpg", doc = "doc-1")
        a.sync()
        assertTrue(backend.puts.contains("i-pic.jpg.bin"))
        assertTrue(backend.puts.contains("d-doc-1.bin"))
        backend.puts.clear()

        a.write("chat-1", "One edited", 200, image = "pic.jpg", doc = "doc-1")
        tick()
        a.sync()
        assertEquals(listOf("c-chat-1.bin", SyncNames.MANIFEST), backend.puts)
    }

    // ---- Conflicts ---------------------------------------------------------------

    @Test
    fun differentChatsOnTwoPhonesEndUpOnBoth() {
        val a = Phone("a"); val b = Phone("b")
        a.write("only-a", "From A", 100)
        b.write("only-b", "From B", 150)
        a.sync(); b.sync(); a.sync()
        assertEquals(setOf("only-a", "only-b"), a.ids())
        assertEquals(setOf("only-a", "only-b"), b.ids())
    }

    @Test
    fun theSameChatEditedOnTwoPhonesKeepsTheLaterEdit() {
        val a = Phone("a"); val b = Phone("b")
        a.write("chat-1", "Original", 100)
        a.sync(); b.sync()

        a.write("chat-1", "Edited on A", 300)
        b.write("chat-1", "Edited on B", 400)
        a.sync()          // A sends its edit
        b.sync()          // B is newer, so it wins
        a.sync()          // and A receives it
        assertEquals("Edited on B", a.title("chat-1"))
        assertEquals("Edited on B", b.title("chat-1"))
    }

    @Test
    fun theLaterEditWinsEvenIfItIsSyncedFirst() {
        val a = Phone("a"); val b = Phone("b")
        a.write("chat-1", "Original", 100)
        a.sync(); b.sync()

        a.write("chat-1", "Edited on A", 300)
        b.write("chat-1", "Edited on B", 400)
        b.sync()
        a.sync()          // A's edit is older than what is there, so it is replaced
        assertEquals("Edited on B", a.title("chat-1"))
        b.sync()
        assertEquals("Edited on B", b.title("chat-1"))
    }

    @Test
    fun manyChatsChangedAllOverStillConverge() {
        val a = Phone("a"); val b = Phone("b"); val c = Phone("c")
        for (i in 0 until 12) a.write("chat-$i", "A$i", 100L + i)
        a.sync(); b.sync(); c.sync()
        for (i in 0 until 12 step 2) a.write("chat-$i", "A-edit-$i", 1_000L + i)
        for (i in 0 until 12 step 3) b.write("chat-$i", "B-edit-$i", 2_000L + i)
        c.delete("chat-1"); c.delete("chat-5")
        c.write("chat-new", "C new", 500)
        tick(); a.sync(); tick(); b.sync(); tick(); c.sync(); tick(); a.sync(); tick(); b.sync(); tick(); c.sync()

        val expected = a.ids().associateWith { a.title(it) }
        assertEquals(expected, b.ids().associateWith { b.title(it) })
        assertEquals(expected, c.ids().associateWith { c.title(it) })
        assertFalse(expected.containsKey("chat-1"))
        assertFalse(expected.containsKey("chat-5"))
        assertEquals("C new", expected["chat-new"])
        assertEquals("B-edit-0", expected["chat-0"])   // edited on both, B was later
        assertEquals("A-edit-2", expected["chat-2"])   // edited on A only
        assertEquals("B-edit-3", expected["chat-3"])   // edited on B only
    }

    // ---- Deletions ---------------------------------------------------------------

    @Test
    fun aDeletionReachesTheOtherPhoneAndRemovesItsFiles() {
        val a = Phone("a"); val b = Phone("b")
        a.write("chat-1", "Doomed", 100, image = "pic.jpg", doc = "doc-1")
        a.write("chat-2", "Survivor", 100)
        a.sync(); b.sync()
        assertTrue(File(b.images, "pic.jpg").isFile)

        tick()
        a.delete("chat-1")
        val sent = a.sync()
        assertEquals(1, sent.removedThere)
        assertFalse(backend.files.containsKey("c-chat-1.bin"))
        assertFalse(backend.files.containsKey("i-pic.jpg.bin"))
        assertFalse(backend.files.containsKey("d-doc-1.bin"))
        assertTrue(backend.files.containsKey("c-chat-2.bin"))

        tick()
        val received = b.sync()
        assertEquals(1, received.removedHere)
        assertEquals(setOf("chat-2"), b.ids())
        assertFalse(File(b.images, "pic.jpg").exists())
        assertFalse(File(b.docs, "doc-1.txt").exists())
    }

    @Test
    fun aDeletedChatDoesNotComeBackFromAPhoneThatStillHasIt() {
        val a = Phone("a"); val b = Phone("b")
        a.write("chat-1", "Doomed", 100)
        a.sync(); b.sync()
        tick(); a.delete("chat-1"); a.sync()
        tick(); b.sync()
        tick(); a.sync()
        assertTrue(a.ids().isEmpty())
        assertTrue(b.ids().isEmpty())
    }

    @Test
    fun aChatEditedAfterItWasDeletedElsewhereSurvives() {
        val a = Phone("a"); val b = Phone("b")
        a.write("chat-1", "Shared", 100)
        a.sync(); b.sync()
        tick(); a.delete("chat-1"); a.sync()
        b.write("chat-1", "Edited later on B", now + 5_000)
        tick(); b.sync()
        tick(); a.sync()
        assertEquals("Edited later on B", b.title("chat-1"))
        assertEquals("Edited later on B", a.title("chat-1"))
    }

    @Test
    fun aDeletionIsPassedOnEvenIfTheChatWasNeverSent() {
        val a = Phone("a")
        a.write("chat-1", "Brief", 100)
        a.delete("chat-1")
        val report = a.sync()
        assertEquals(1, report.removedThere)
        val manifest = SyncManifest.fromJson(String(SyncCrypto.open(key, SyncNames.MANIFEST, backend.files.getValue(SyncNames.MANIFEST))))
        assertTrue(manifest.chats.getValue("chat-1").deleted)
    }

    @Test
    fun anUnfinishedDeletionIsCompletedOnTheNextSync() {
        val a = Phone("a")
        a.write("chat-1", "Half deleted", 100, image = "pic.jpg")
        a.sync()
        a.state.addTombstone("chat-1", now + 10, SyncChats.media(a.chat("chat-1")!!).images.map(SyncNames::image))
        tick(100)          // the app stopped before it removed the files
        a.sync()
        assertTrue(a.ids().isEmpty())
        assertFalse(backend.files.containsKey("c-chat-1.bin"))
    }

    // ---- Safety ------------------------------------------------------------------

    @Test
    fun theChatThatIsOpenIsNeverReplacedUnderTheUser() {
        val a = Phone("a"); val b = Phone("b")
        a.write("chat-1", "Version 1", 100)
        a.sync(); b.sync()
        a.write("chat-1", "Version 2", 300)
        tick(); a.sync()

        tick()
        val report = b.sync(busy = setOf("chat-1"))
        assertEquals(1, report.deferred)
        assertEquals("Version 1", b.title("chat-1"))
        tick()
        b.sync()
        assertEquals("Version 2", b.title("chat-1"))
    }

    @Test
    fun aDeletionOfTheOpenChatWaitsToo() {
        val a = Phone("a"); val b = Phone("b")
        a.write("chat-1", "Open on B", 100)
        a.sync(); b.sync()
        tick(); a.delete("chat-1"); a.sync()
        tick()
        assertEquals(1, b.sync(busy = setOf("chat-1")).deferred)
        assertEquals(setOf("chat-1"), b.ids())
        tick(); b.sync()
        assertTrue(b.ids().isEmpty())
    }

    @Test
    fun aWrongKeyIsReportedAndChangesNothing() {
        val a = Phone("a")
        a.write("chat-1", "One", 100)
        a.sync()
        val wrong = SyncCrypto.newHeader("another passphrase".toCharArray(), 1_000)
        val b = Phone("b")
        try {
            b.engine(k = wrong.second, h = wrong.first).run()
            fail("a different passphrase should not sync")
        } catch (e: SyncException) {
            assertTrue(e.message!!, e.message!!.contains("different passphrase"))
        }
        assertTrue(b.ids().isEmpty())
        assertEquals(0L, b.state.get().lastSyncAt)
    }

    @Test
    fun aDamagedIndexIsRebuiltFromWhatEachPhoneHas() {
        val a = Phone("a")
        a.write("chat-1", "One", 100)
        a.sync()
        val damaged = backend.files.getValue(SyncNames.MANIFEST).copyOf()
        damaged[damaged.size - 1] = (damaged[damaged.size - 1].toInt() xor 1).toByte()
        backend.files[SyncNames.MANIFEST] = damaged
        tick()
        val report = a.sync()
        assertEquals(1, report.uploaded)
        assertTrue(report.warnings.single().contains("damaged"))
        val b = Phone("b")
        assertEquals(1, b.sync().downloaded)
        assertEquals("One", b.title("chat-1"))
    }

    @Test
    fun anIndexFromANewerVersionIsLeftAloneAndReported() {
        val a = Phone("a")
        a.write("chat-1", "One", 100)
        a.sync()
        val future = backend.files.getValue(SyncNames.MANIFEST).copyOf().also { it[0] = 2 }
        backend.files[SyncNames.MANIFEST] = future
        try {
            a.sync()
            fail("a newer index must not be overwritten")
        } catch (e: SyncVersionException) {
            assertTrue(e.message!!.contains("newer version"))
        }
        assertArrayEquals(future, backend.files.getValue(SyncNames.MANIFEST))
    }

    @Test
    fun aMissingIndexIsRebuiltToo() {
        val a = Phone("a")
        a.write("chat-1", "One", 100)
        a.sync()
        backend.files.remove(SyncNames.MANIFEST)
        assertEquals(1, a.sync().uploaded)
        assertTrue(backend.files.containsKey(SyncNames.MANIFEST))
    }

    @Test
    fun aChatSwappedForAnotherOneAtTheLocationIsRejected() {
        val a = Phone("a")
        a.write("chat-1", "One", 100)
        a.write("chat-2", "Two", 100)
        a.sync()
        backend.files["c-chat-1.bin"] = backend.files.getValue("c-chat-2.bin")
        val b = Phone("b")
        val report = b.sync()
        assertEquals(1, report.downloaded)
        assertEquals(setOf("chat-2"), b.ids())
        assertTrue(report.warnings.single().contains("could not be read"))
    }

    @Test
    fun aChatFileWithTheWrongIdInsideIsRejected() {
        val a = Phone("a")
        a.write("chat-1", "One", 100)
        a.sync()
        val forged = JSONObject(a.chat("chat-1")!!.toString()).put("id", "other-id")
        backend.files["c-chat-1.bin"] = SyncCrypto.seal(key, "c-chat-1.bin", forged.toString().toByteArray(), compress = true)
        val b = Phone("b")
        val report = b.sync()
        assertEquals(0, report.downloaded)
        assertTrue(b.ids().isEmpty())
        assertTrue(report.warnings.single().contains("id does not match"))
    }

    @Test
    fun aMissingPhotoIsReportedButTheChatStillSyncs() {
        val a = Phone("a")
        a.write("chat-1", "With photo", 100, image = "pic.jpg")
        a.sync()
        backend.files.remove("i-pic.jpg.bin")
        val b = Phone("b")
        val report = b.sync()
        assertEquals(1, report.downloaded)
        assertTrue(report.warnings.single().contains("photo"))
        assertEquals("With photo", b.title("chat-1"))
    }

    @Test
    fun aChatThatCannotBeReadHereIsSkippedWithoutStoppingTheRest() {
        val a = Phone("a")
        a.write("good", "Fine", 100)
        File(a.chats, "broken.json").writeText("{ not json")
        val report = a.sync()
        assertEquals(1, report.uploaded)
        assertTrue(backend.files.containsKey("c-good.bin"))
        assertFalse(backend.files.containsKey("c-broken.bin"))
    }

    @Test
    fun aDroppedConnectionFailsTheSyncAndTheNextOneFinishesTheJob() {
        val a = Phone("a")
        a.write("chat-1", "One", 100, image = "pic.jpg")
        a.write("chat-2", "Two", 100)
        backend.failAfterPuts = 3        // the header, the photo and one chat get through
        try {
            a.sync()
            fail("the sync should fail")
        } catch (e: IOException) {
            assertEquals(0L, a.state.get().lastSyncAt)
            assertFalse(backend.files.containsKey(SyncNames.MANIFEST))
        }
        backend.failAfterPuts = -1
        val report = a.sync()
        assertEquals(2, report.uploaded)
        val b = Phone("b")
        assertEquals(2, b.sync().downloaded)
    }

    @Test
    fun cancellingStopsBeforeTheIndexIsWritten() {
        val a = Phone("a")
        a.write("chat-1", "One", 100)
        var calls = 0
        try {
            a.engine().run(SyncOptions(cancelled = { ++calls > 1 }))
            fail("should have been cancelled")
        } catch (e: java.util.concurrent.CancellationException) {
            // expected
        }
        assertFalse(backend.files.containsKey(SyncNames.MANIFEST))
    }

    @Test
    fun theLocationIsRestartedIfSomeoneEmptiedIt() {
        val a = Phone("a")
        a.write("chat-1", "One", 100)
        a.sync()
        backend.files.clear()
        a.sync()
        assertTrue(backend.files.containsKey(SyncNames.HEADER))
        assertTrue(backend.files.containsKey("c-chat-1.bin"))
        val b = Phone("b")
        assertEquals(1, b.sync().downloaded)
    }

    @Test
    fun theIndexIsReadAgainBeforeItIsWrittenSoAnotherPhonesNewChatSurvives() {
        val a = Phone("a"); val b = Phone("b")
        a.write("chat-a", "A", 100)
        a.sync()
        // While A is syncing, B adds a chat to the index. A's engine reads the index again just before writing it.
        val racing = object : SyncBackend by backend {
            var raced = false
            override fun put(name: String, data: ByteArray) {
                if (name == "c-chat-a2.bin" && !raced) {
                    raced = true
                    b.write("chat-b", "B", 150)
                    b.sync()
                }
                backend.put(name, data)
            }
        }
        a.write("chat-a2", "A2", 200)
        tick()
        a.engine(b = racing).run()
        val manifest = SyncManifest.fromJson(String(SyncCrypto.open(key, SyncNames.MANIFEST, backend.files.getValue(SyncNames.MANIFEST))))
        assertEquals(setOf("chat-a", "chat-a2", "chat-b"), manifest.chats.keys)
    }

    // ---- Settings ------------------------------------------------------------------

    @Test
    fun settingsGoToAnotherPhoneAndAreAppliedThere() {
        val a = Phone("a"); val b = Phone("b")
        val settingsA = FakeSettings(mutableMapOf("temp" to "0.9", "notes" to "[]"), editedAt = 500)
        val sent = a.sync(settingsA)
        assertEquals(SettingsOutcome.Sent, sent.settings)

        val settingsB = FakeSettings(mutableMapOf("temp" to "0.7"), editedAt = 900)
        val got = b.sync(settingsB)
        assertEquals(SettingsOutcome.Received, got.settings)
        assertEquals(mapOf("temp" to "0.9", "notes" to "[]"), settingsB.values)
        assertTrue(got.changedHere)

        // And a later change on B goes back to A.
        tick(); settingsB.values["temp"] = "0.2"; settingsB.editedAt = now
        assertEquals(SettingsOutcome.Sent, b.sync(settingsB).settings)
        tick()
        assertEquals(SettingsOutcome.Received, a.sync(settingsA).settings)
        assertEquals("0.2", settingsA.values["temp"])
        // Then everything is quiet.
        tick()
        assertEquals(SettingsOutcome.Untouched, a.sync(settingsA).settings)
        assertEquals(SettingsOutcome.Untouched, b.sync(settingsB).settings)
    }

    @Test
    fun settingsStayOutOfTheSyncWhenTheyAreSwitchedOff() {
        val a = Phone("a")
        a.write("chat-1", "One", 100)
        a.sync(settings = null)
        assertFalse(backend.files.containsKey(SyncNames.SETTINGS))
        val manifest = SyncManifest.fromJson(String(SyncCrypto.open(key, SyncNames.MANIFEST, backend.files.getValue(SyncNames.MANIFEST))))
        assertNull(manifest.settings)
    }

    @Test
    fun settingsAreEncryptedLikeEverythingElse() {
        val a = Phone("a")
        a.sync(FakeSettings(mutableMapOf("notes" to "my home address is 1 Example Road")))
        assertFalse(String(backend.files.getValue(SyncNames.SETTINGS), Charsets.ISO_8859_1).contains("Example Road"))
    }

    // ---- Chat file handling -----------------------------------------------------------

    @Test
    fun portableChatsHaveNoPathsOnThisPhone() {
        val chat = JSONObject()
            .put("id", "x").put("title", "t").put("updatedAt", 1)
            .put("messages", JSONArray().put(JSONObject().put("text", "hi").put("image", "/data/user/0/com.pocketlm.app/files/images/abc.jpg")))
        val portable = SyncChats.portable(chat)
        assertEquals("abc.jpg", portable.getJSONArray("messages").getJSONObject(0).getString("image"))
        // The original is untouched.
        assertTrue(chat.getJSONArray("messages").getJSONObject(0).getString("image").startsWith("/data"))
        val local = SyncChats.localize(portable, File("/phone/images"))
        assertEquals(File("/phone/images", "abc.jpg").absolutePath, local.getJSONArray("messages").getJSONObject(0).getString("image"))
    }

    @Test
    fun unsafeImageNamesFromTheLocationAreDroppedNotFollowed() {
        val chat = JSONObject()
            .put("id", "x").put("title", "t").put("updatedAt", 1)
            .put("messages", JSONArray().put(JSONObject().put("text", "hi").put("image", "../../shared_prefs/settings.xml")))
        val local = SyncChats.localize(chat, File("/phone/images"))
        assertFalse(local.getJSONArray("messages").getJSONObject(0).has("image"))
        assertTrue(SyncChats.media(chat).images.isEmpty())
    }

    @Test
    fun summariesDescribeWhatHappened() {
        val r = SyncReport().apply { uploaded = 2; downloaded = 1; removedHere = 3 }
        assertEquals("Sent 2 chats, received 1 chat, removed 3 chats deleted elsewhere", r.summary())
        assertTrue(SyncReport().apply { warnings += "a problem" }.summary().contains("1 problem: a problem"))
    }
}
