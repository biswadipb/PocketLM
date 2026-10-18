package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class SyncModelTest {

    private fun live(at: Long) = ManifestEntry(at)
    private fun gone(at: Long) = ManifestEntry(at, deleted = true)

    // ---- Manifest ------------------------------------------------------------------

    @Test
    fun theManifestSurvivesBeingWrittenAndRead() {
        val m = SyncManifest(
            chats = mapOf("a-1" to live(100), "b-2" to gone(250)),
            settings = SettingsEntry(300, "abc123"),
        )
        assertEquals(m, SyncManifest.fromJson(m.toJson()))
        assertEquals(SyncManifest(), SyncManifest.fromJson(SyncManifest().toJson()))
    }

    @Test
    fun theManifestIgnoresEntriesWithUnsafeIds() {
        val json = """{"v":1,"chats":{"good-1":{"u":5},"../evil":{"u":9},"a/b":{"u":9}}}"""
        assertEquals(setOf("good-1"), SyncManifest.fromJson(json).chats.keys)
    }

    @Test
    fun aManifestFromANewerVersionIsRefusedRatherThanMisread() {
        try {
            SyncManifest.fromJson("""{"v":2,"chats":{}}""")
            fail()
        } catch (e: SyncException) {
            assertTrue(e.message!!.contains("newer version"))
        }
    }

    @Test
    fun mergingKeepsTheNewestVersionOfEachChat() {
        val a = SyncManifest(mapOf("x" to live(100), "y" to live(500), "only-a" to live(1)))
        val b = SyncManifest(mapOf("x" to live(200), "y" to live(400), "only-b" to live(2)))
        val merged = a.merge(b)
        assertEquals(live(200), merged.chats["x"])
        assertEquals(live(500), merged.chats["y"])
        assertEquals(live(1), merged.chats["only-a"])
        assertEquals(live(2), merged.chats["only-b"])
        assertEquals(merged.chats, b.merge(a).chats)
    }

    @Test
    fun aDeletionBeatsAnOlderEditAndAnEditBeatsAnOlderDeletion() {
        assertEquals(gone(200), SyncManifest(mapOf("x" to live(100))).merge(SyncManifest(mapOf("x" to gone(200)))).chats["x"])
        assertEquals(live(300), SyncManifest(mapOf("x" to gone(200))).merge(SyncManifest(mapOf("x" to live(300)))).chats["x"])
    }

    @Test
    fun aDeletionWinsATieSoItIsNeverUndoneByAStaleCopy() {
        val m = SyncManifest(mapOf("x" to live(100))).merge(SyncManifest(mapOf("x" to gone(100))))
        assertEquals(gone(100), m.chats["x"])
        assertEquals(m, SyncManifest(mapOf("x" to gone(100))).merge(SyncManifest(mapOf("x" to live(100)))))
    }

    @Test
    fun mergingKeepsTheNewerSettings() {
        val a = SyncManifest(settings = SettingsEntry(10, "old"))
        val b = SyncManifest(settings = SettingsEntry(20, "new"))
        assertEquals("new", a.merge(b).settings?.fingerprint)
        assertEquals("new", b.merge(a).settings?.fingerprint)
        assertEquals("old", a.merge(SyncManifest()).settings?.fingerprint)
    }

    @Test
    fun oldDeletionsAreForgottenButRecentOnesAndLiveChatsAreKept() {
        val now = TimeUnit.DAYS.toMillis(1000)
        val m = SyncManifest(
            mapOf(
                "live-old" to live(now - TimeUnit.DAYS.toMillis(900)),
                "gone-recent" to gone(now - TimeUnit.DAYS.toMillis(10)),
                "gone-old" to gone(now - TimeUnit.DAYS.toMillis(400)),
            ),
        ).pruned(now)
        assertEquals(setOf("live-old", "gone-recent"), m.chats.keys)
    }

    // ---- Planner -------------------------------------------------------------------

    private fun plan(local: Map<String, ManifestEntry>, remote: Map<String, ManifestEntry>) = SyncPlanner.plan(local, remote)

    @Test
    fun nothingToDoWhenBothSidesAgree() {
        val same = mapOf("a" to live(1), "b" to gone(2))
        assertTrue(plan(same, same).isEmpty())
        assertTrue(plan(emptyMap(), emptyMap()).isEmpty())
    }

    @Test
    fun newChatsGoInTheRightDirection() {
        val actions = plan(mapOf("here" to live(5)), mapOf("there" to live(6)))
        assertEquals(listOf(SyncAction.Upload("here"), SyncAction.Download("there")), actions)
    }

    @Test
    fun theNewerEditWins() {
        assertEquals(listOf(SyncAction.Upload("c")), plan(mapOf("c" to live(10)), mapOf("c" to live(5))))
        assertEquals(listOf(SyncAction.Download("c")), plan(mapOf("c" to live(5)), mapOf("c" to live(10))))
    }

    @Test
    fun aDeletionElsewhereRemovesTheChatHereUnlessItWasEditedLater() {
        assertEquals(listOf(SyncAction.DeleteHere("c")), plan(mapOf("c" to live(5)), mapOf("c" to gone(10))))
        assertEquals(listOf(SyncAction.Upload("c")), plan(mapOf("c" to live(15)), mapOf("c" to gone(10))))
    }

    @Test
    fun aDeletionHereIsPassedOnUnlessTheChatWasEditedElsewhereLater() {
        assertEquals(listOf(SyncAction.DeleteThere("c")), plan(mapOf("c" to gone(10)), mapOf("c" to live(5))))
        assertEquals(listOf(SyncAction.Download("c")), plan(mapOf("c" to gone(10)), mapOf("c" to live(15))))
        assertEquals(listOf(SyncAction.DeleteThere("c")), plan(mapOf("c" to gone(10)), emptyMap()))
    }

    @Test
    fun aDeletionAlreadyRecordedOnBothSidesNeedsNoWork() {
        assertTrue(plan(mapOf("c" to gone(10)), mapOf("c" to gone(10))).isEmpty())
        assertTrue(plan(emptyMap(), mapOf("c" to gone(10))).isEmpty())
    }

    @Test
    fun aTieBetweenAnEditAndADeletionIsADeletion() {
        assertEquals(listOf(SyncAction.DeleteHere("c")), plan(mapOf("c" to live(10)), mapOf("c" to gone(10))))
        assertEquals(listOf(SyncAction.DeleteThere("c")), plan(mapOf("c" to gone(10)), mapOf("c" to live(10))))
    }

    @Test
    fun theLocalViewCountsAChatEditedAfterItsDeletionAsAlive() {
        val view = SyncPlanner.localView(
            chatUpdatedAt = mapOf("kept" to 50L, "edited-after" to 90L, "restored-old" to 20L),
            deletedAt = mapOf("edited-after" to 80L, "restored-old" to 30L, "plain" to 70L),
        )
        assertEquals(live(50), view["kept"])
        assertEquals(live(90), view["edited-after"])
        assertEquals(gone(30), view["restored-old"])
        assertEquals(gone(70), view["plain"])
    }

    @Test
    fun conflictingChatSetsConvergeNoMatterWhoSyncsFirst() {
        // Two devices changed the same chat and different ones. Simulate the algorithm's effect on each.
        val a = mutableMapOf("shared" to live(100), "only-a" to live(10), "deleted-on-a" to gone(60), "old" to live(5))
        val b = mutableMapOf("shared" to live(200), "only-b" to live(20), "deleted-on-a" to live(40), "old" to live(5))
        var remote = SyncManifest()

        fun sync(device: MutableMap<String, ManifestEntry>) {
            val actions = SyncPlanner.plan(device, remote.chats)
            val next = HashMap(remote.chats)
            for (action in actions) when (action) {
                is SyncAction.Upload, is SyncAction.DeleteThere -> next[action.id] = device.getValue(action.id)
                is SyncAction.Download -> device[action.id] = remote.chats.getValue(action.id)
                is SyncAction.DeleteHere -> device[action.id] = remote.chats.getValue(action.id)
            }
            remote = SyncManifest(next)
        }

        sync(a); sync(b); sync(a)
        assertEquals(a, b)
        assertEquals(remote.chats, a)
        assertEquals(live(200), a["shared"])
        assertEquals(gone(60), a["deleted-on-a"])
        assertEquals(live(10), a["only-a"])
        assertEquals(live(20), a["only-b"])
        // Nothing left to do for either device.
        assertTrue(SyncPlanner.plan(a, remote.chats).isEmpty())
        assertTrue(SyncPlanner.plan(b, remote.chats).isEmpty())
    }

    // ---- Settings -----------------------------------------------------------------

    private val keys = setOf("temp", "prompts", "flag", "count")

    @Test
    fun theSettingsFingerprintIgnoresOrderAndOtherKeys() {
        val a = linkedMapOf<String, Any>("temp" to 0.7f, "prompts" to "[]", "api_key" to "secret-1", "flag" to true)
        val b = linkedMapOf<String, Any>("flag" to true, "api_key" to "secret-2", "prompts" to "[]", "temp" to 0.7f)
        assertEquals(SettingsSync.fingerprint(a, keys), SettingsSync.fingerprint(b, keys))
    }

    @Test
    fun theSettingsFingerprintChangesWithAnySyncedValue() {
        val base = mapOf<String, Any>("temp" to 0.7f, "count" to 3)
        val before = SettingsSync.fingerprint(base, keys)
        assertFalse(before == SettingsSync.fingerprint(base + ("temp" to 0.8f), keys))
        assertFalse(before == SettingsSync.fingerprint(base + ("count" to 4), keys))
        assertFalse(before == SettingsSync.fingerprint(base + ("flag" to false), keys))
        // The type counts too: the string "3" is not the number 3.
        assertFalse(before == SettingsSync.fingerprint(mapOf("temp" to 0.7f, "count" to "3"), keys))
    }

    private fun synced(fingerprint: String?, stamp: Long) = SyncState(settingsFingerprint = fingerprint, settingsStamp = stamp)

    @Test
    fun firstSyncWithNothingThereUploadsTheSettings() {
        val d = SettingsSync.decide("mine", 500, SyncState(), null)
        assertEquals(SettingsSync.Decision.Upload(500), d)
    }

    @Test
    fun aNewDeviceTakesTheSettingsThatAreAlreadyThereInsteadOfOverwritingThem() {
        val d = SettingsSync.decide("defaults", 9_999, SyncState(), SettingsEntry(100, "customised"))
        assertEquals(SettingsSync.Decision.Apply, d)
    }

    @Test
    fun identicalSettingsAreInSyncWhoeverMadeThem() {
        val d = SettingsSync.decide("same", 1, SyncState(), SettingsEntry(70, "same"))
        assertEquals(SettingsSync.Decision.InSync(70), d)
    }

    @Test
    fun aLocalEditIsUploadedAndARemoteEditIsApplied() {
        val state = synced("v1", 100)
        assertEquals(SettingsSync.Decision.Upload(400), SettingsSync.decide("v2-local", 400, state, SettingsEntry(100, "v1")))
        assertEquals(SettingsSync.Decision.Apply, SettingsSync.decide("v1", 50, state, SettingsEntry(300, "v2-remote")))
    }

    @Test
    fun nothingChangedOnEitherSideIsInSync() {
        val d = SettingsSync.decide("v1", 50, synced("v1", 100), SettingsEntry(100, "v1-with-other-keys"))
        assertEquals(SettingsSync.Decision.InSync(100), d)
    }

    @Test
    fun whenBothSidesChangedTheLaterEditWins() {
        val state = synced("v1", 100)
        assertEquals(SettingsSync.Decision.Upload(500), SettingsSync.decide("local", 500, state, SettingsEntry(300, "remote")))
        assertEquals(SettingsSync.Decision.Apply, SettingsSync.decide("local", 200, state, SettingsEntry(300, "remote")))
    }

    @Test
    fun missingSettingsAtTheLocationAreUploadedAgainWithTheirOldStampWhenUnchanged() {
        val d = SettingsSync.decide("v1", 9_000, synced("v1", 100), null)
        assertEquals(SettingsSync.Decision.Upload(100), d)
    }

    // ---- Sync state ---------------------------------------------------------------

    @Test
    fun theSyncStateSurvivesBeingSavedAndLoaded() {
        val state = SyncState(
            tombstones = mapOf("gone-1" to Tombstone(500, listOf("i-a.jpg.bin", "d-b.bin")), "gone-2" to Tombstone(600)),
            uploaded = setOf("i-a.jpg.bin", "d-b.bin"),
            settingsFingerprint = "fp",
            settingsStamp = 77,
            lastSyncAt = 1234,
        )
        assertEquals(state, SyncState.fromJson(state.toJson()))
        assertNull(SyncState.fromJson(SyncState().toJson()).settingsFingerprint)
    }

    @Test
    fun aDamagedSyncStateFileStartsOverInsteadOfCrashing() {
        assertEquals(SyncState(), SyncState.fromJson("{ broken"))
        assertEquals(SyncState(), SyncState.fromJson(null))
        assertEquals(SyncState(), SyncState.fromJson(""))
    }

    @Test
    fun theSyncStateStoreKeepsWhatItIsGiven() {
        val dir = Files.createTempDirectory("syncstate").toFile()
        try {
            val file = File(dir, "state.json")
            val store = SyncStateStore(file)
            store.addTombstone("chat-1", 1_000, listOf("i-x.jpg.bin"))
            store.update { it.copy(uploaded = it.uploaded + "i-x.jpg.bin", lastSyncAt = 5) }
            val reloaded = SyncStateStore(file).get()
            assertEquals(Tombstone(1_000, listOf("i-x.jpg.bin")), reloaded.tombstones["chat-1"])
            assertEquals(setOf("i-x.jpg.bin"), reloaded.uploaded)
            assertEquals(5L, reloaded.lastSyncAt)
            store.clear()
            assertFalse(file.exists())
            assertEquals(SyncState(), store.get())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun oldTombstonesAreDroppedWhenTheStateIsPruned() {
        val now = TimeUnit.DAYS.toMillis(1000)
        val state = SyncState(
            tombstones = mapOf(
                "recent" to Tombstone(now - TimeUnit.DAYS.toMillis(1)),
                "ancient" to Tombstone(now - TimeUnit.DAYS.toMillis(365)),
            ),
        ).pruned(now)
        assertEquals(setOf("recent"), state.tombstones.keys)
    }

    @Test
    fun remoteFileNamesAreValidatedBeforeUse() {
        assertTrue(SyncNames.valid("c-8f2a-11.bin"))
        assertTrue(SyncNames.valid(SyncNames.HEADER))
        for (bad in listOf("", ".", "..", "a/b", "..\\x", "a b", "x".repeat(300))) assertFalse(bad, SyncNames.valid(bad))
        assertTrue(SyncNames.validImage("0f3c-uuid.jpg"))
        assertFalse(SyncNames.validImage("a/b.jpg"))
        assertFalse(SyncNames.validImage("evil.sh"))
        assertTrue(SyncNames.validId("8f2a-11-ee"))
        assertFalse(SyncNames.validId("../x"))
    }
}
