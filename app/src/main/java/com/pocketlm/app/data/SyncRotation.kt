package com.pocketlm.app.data

import org.json.JSONException
import org.json.JSONObject
import java.io.File

/** The sync location is now locked with a different passphrase than this phone has; it must be entered again. */
class SyncPassphraseChangedException : SyncException(
    "The passphrase for this sync location was changed on another device. Enter the new passphrase to continue.",
)

/**
 * Changes the passphrase of a sync location. Files cannot be re-locked in place without leaving a mix of old and
 * new ones if the connection drops, so everything the old passphrase protected is removed and this phone, which
 * holds all the chats after a sync, uploads them again under the new one. Other devices keep their own chats and
 * are asked for the new passphrase the next time they sync.
 *
 * The record of which chats were deleted is carried over, so a phone that has not synced for a while cannot
 * bring a deleted chat back.
 */
class SyncRotation(
    private val chatsDir: File,
    private val backend: SyncBackend,
    private val oldKey: ByteArray,
    private val stateStore: SyncStateStore,
) {
    class Result(val header: SyncHeader, val key: ByteArray, val filesRemoved: Int)

    /**
     * [saveConfig] must store the new header and key before this returns, as the old ones no longer open anything.
     * Call it only right after a sync that succeeded, so that everything is here to be uploaded again.
     */
    fun rotate(newPassphrase: CharArray, iterations: Int = SyncCrypto.DEFAULT_ITERATIONS, saveConfig: (SyncHeader, ByteArray) -> Unit): Result {
        val oldManifest = try {
            backend.get(SyncNames.MANIFEST)?.let {
                SyncManifest.fromJson(String(SyncCrypto.open(oldKey, SyncNames.MANIFEST, it), Charsets.UTF_8))
            } ?: SyncManifest()
        } catch (e: SyncDecryptException) {
            SyncManifest()
        } catch (e: JSONException) {
            SyncManifest()
        }
        val deletions = oldManifest.chats.filterValues { it.deleted }

        // Done before anything is removed, so a failure here changes nothing.
        val (header, key) = SyncCrypto.newHeader(newPassphrase, iterations)

        val doomed = LinkedHashSet<String>()
        doomed += oldManifest.chats.keys.map(SyncNames::chat)
        for (file in chatsDir.listFiles().orEmpty()) {
            if (!file.name.endsWith(".json")) continue
            val chat = runCatching { JSONObject(file.readText()) }.getOrNull() ?: continue
            val id = chat.optString("id")
            if (!SyncNames.validId(id)) continue
            doomed += SyncNames.chat(id)
            val media = SyncChats.media(chat)
            doomed += media.images.map(SyncNames::image)
            doomed += media.docs.map(SyncNames::doc)
        }
        val state = stateStore.get()
        doomed += state.uploaded
        doomed += state.tombstones.values.flatMap { it.files }
        doomed += SyncNames.SETTINGS
        doomed += SyncNames.MANIFEST
        // Anything else of ours at the location, such as photos of chats this phone no longer has.
        runCatching { backend.list() }.getOrNull()?.forEach { if (isOurs(it.name)) doomed += it.name }

        var removed = 0
        for (name in doomed) {
            if (name == SyncNames.HEADER || !SyncNames.valid(name)) continue
            backend.delete(name)
            removed++
        }

        backend.put(SyncNames.HEADER, header.toJson().toByteArray())
        saveConfig(header, key)
        stateStore.update { it.copy(uploaded = emptySet(), settingsFingerprint = null, settingsStamp = 0, lastSyncAt = 0) }
        if (deletions.isNotEmpty()) {
            backend.put(
                SyncNames.MANIFEST,
                SyncCrypto.seal(key, SyncNames.MANIFEST, SyncManifest(deletions).toJson().toByteArray(), compress = true),
            )
        }
        return Result(header, key, removed)
    }

    private fun isOurs(name: String): Boolean =
        name == SyncNames.MANIFEST || name == SyncNames.SETTINGS ||
            (name.endsWith(".bin") && (name.startsWith("c-") || name.startsWith("i-") || name.startsWith("d-")))
}
