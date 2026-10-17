package com.pocketlm.app.data

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException

/** Reads and writes the settings that take part in syncing. Which settings those are is up to the app. */
interface SyncSettings {
    /** Changes whenever a synced setting changes; see [SettingsSync.fingerprint]. */
    fun fingerprint(): String

    /** When the settings were last edited here, as far as is known. */
    fun changedAt(): Long

    fun export(): String
    fun apply(json: String)
}

class SyncOptions(
    /** Null leaves settings out of this sync. */
    val settings: SyncSettings? = null,
    /** Chats that are open right now. They are sent, but never replaced or removed under the user's hands. */
    val busyChatIds: Set<String> = emptySet(),
    val onChatChanged: (String) -> Unit = {},
    val onProgress: (String) -> Unit = {},
    val cancelled: () -> Boolean = { false },
)

enum class SettingsOutcome { Untouched, Sent, Received }

class SyncReport {
    var uploaded = 0
    var downloaded = 0
    var removedHere = 0
    var removedThere = 0
    var deferred = 0
    var settings = SettingsOutcome.Untouched
    val warnings = ArrayList<String>()

    val changedHere get() = downloaded + removedHere > 0 || settings == SettingsOutcome.Received

    fun summary(): String {
        val parts = ArrayList<String>()
        if (uploaded > 0) parts += "sent ${chats(uploaded)}"
        if (downloaded > 0) parts += "received ${chats(downloaded)}"
        if (removedHere > 0) parts += "removed ${chats(removedHere)} deleted elsewhere"
        if (removedThere > 0) parts += "passed on ${chats(removedThere)} you deleted"
        if (settings == SettingsOutcome.Sent) parts += "sent your settings"
        if (settings == SettingsOutcome.Received) parts += "updated your settings"
        var text = if (parts.isEmpty()) "Everything is up to date" else parts.joinToString(", ").replaceFirstChar { it.uppercase() }
        if (deferred > 0) text += ". ${chats(deferred)} will update once you leave the open chat"
        if (warnings.isNotEmpty()) text += ". ${warnings.size} problem${if (warnings.size == 1) "" else "s"}: ${warnings.first()}"
        return text
    }

    private fun chats(n: Int) = if (n == 1) "1 chat" else "$n chats"
}

/** Reading and rewriting the chat JSON that is stored on the phone. Pure so it can be tested. */
object SyncChats {
    class Media(val images: List<String>, val docs: List<String>)

    /** The photo file names and attached-document ids a chat uses. */
    fun media(chat: JSONObject): Media {
        val images = LinkedHashSet<String>()
        val docs = LinkedHashSet<String>()
        chat.optJSONArray("messages")?.let { msgs ->
            for (i in 0 until msgs.length()) {
                msgs.optJSONObject(i)?.let { m ->
                    MessageImages.paths(m).forEach { path -> File(path).name.takeIf(SyncNames::validImage)?.let { images += it } }
                }
            }
        }
        chat.optJSONArray("attachments")?.let { atts ->
            for (i in 0 until atts.length()) {
                atts.optJSONObject(i)?.optString("id")?.takeIf(SyncNames::validId)?.let { docs += it }
            }
        }
        return Media(images.toList(), docs.toList())
    }

    /** The chat as it is sent: photo paths on this phone are reduced to file names. */
    fun portable(chat: JSONObject): JSONObject = rewriteImages(JSONObject(chat.toString())) { File(it).name }

    /** The chat as it is kept here: photo names point into this phone's own photo folder. */
    fun localize(chat: JSONObject, imagesDir: File): JSONObject =
        rewriteImages(JSONObject(chat.toString())) { File(imagesDir, File(it).name).absolutePath }

    /** Why a chat from the sync location cannot be used, or null if it can. */
    fun problem(chat: JSONObject, expectedId: String): String? = when {
        chat.optString("id") != expectedId -> "its id does not match"
        !chat.has("updatedAt") || chat.optLong("updatedAt", -1) < 0 -> "it has no date"
        !chat.has("title") || chat.optJSONArray("messages") == null -> "it is incomplete"
        else -> null
    }

    private fun rewriteImages(chat: JSONObject, transform: (String) -> String): JSONObject {
        chat.optJSONArray("messages")?.let { msgs ->
            for (i in 0 until msgs.length()) {
                val m = msgs.optJSONObject(i) ?: continue
                MessageImages.rewrite(m) { path -> if (SyncNames.validImage(File(path).name)) transform(path) else null }
            }
        }
        return chat
    }
}

/**
 * Brings this phone's chats (with their photos and attached documents) and, optionally, settings level with the
 * sync location. Everything is encrypted with [key] before it is handed to the [backend].
 *
 * Each chat is compared on its own by when it last changed: the newer one wins, and a deleted chat is kept as a
 * small note ("tombstone") so the deletion reaches the other devices too. The order of work keeps the location
 * consistent if a sync is cut short: photos and documents first, then the chat, then the index.
 */
class SyncEngine(
    private val chatsDir: File,
    private val imagesDir: File,
    private val docsDir: File,
    private val backend: SyncBackend,
    private val header: SyncHeader,
    private val key: ByteArray,
    private val stateStore: SyncStateStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val chatFile = Regex("""([A-Za-z0-9-]{1,64})\.json""")

    fun run(options: SyncOptions = SyncOptions()): SyncReport {
        val report = SyncReport()
        val now = clock()
        val newlyUploaded = HashSet<String>()
        val noLongerUploaded = HashSet<String>()
        var settingsAfter: Pair<String, Long>? = null
        var succeeded = false

        try {
            checkHeader()
            stateStore.update { it.pruned(now) }
            val state = stateStore.get()

            options.onProgress("Reading the sync location")
            val remote = loadManifest(report)
            val chats = scanChats()

            // A deletion that was recorded but whose files are still here (the app stopped halfway): finish it.
            for ((id, tomb) in state.tombstones) {
                val at = chats[id] ?: continue
                if (at <= tomb.at && id !in options.busyChatIds) {
                    removeLocal(id, options)
                    chats.remove(id)
                }
            }

            val local = SyncPlanner.localView(chats, state.tombstones.mapValues { it.value.at })
            val actions = SyncPlanner.plan(local, remote.chats)
            val finalChats = HashMap(remote.chats)

            var done = 0
            for (action in actions) {
                check(options)
                options.onProgress("Syncing chat ${++done} of ${actions.size}")
                val id = action.id
                when (action) {
                    is SyncAction.Upload -> upload(id, state, newlyUploaded, report)?.let { updatedAt ->
                        finalChats[id] = ManifestEntry(updatedAt)
                        report.uploaded++
                    }
                    is SyncAction.Download -> if (id in options.busyChatIds) report.deferred++
                    else if (download(id, remote.chats.getValue(id), options, report)) report.downloaded++
                    is SyncAction.DeleteHere -> if (id in options.busyChatIds) report.deferred++
                    else {
                        removeLocal(id, options)
                        report.removedHere++
                    }
                    is SyncAction.DeleteThere -> {
                        val files = state.tombstones[id]?.files.orEmpty()
                        backend.delete(SyncNames.chat(id))
                        for (media in files) {
                            if (SyncNames.valid(media)) backend.delete(media)
                            noLongerUploaded += media
                        }
                        finalChats[id] = local.getValue(id)
                        report.removedThere++
                    }
                }
            }

            // Settings travel as one block.
            var settingsEntry = remote.settings
            options.settings?.let { settings ->
                check(options)
                options.onProgress("Syncing settings")
                val fingerprint = settings.fingerprint()
                when (val decision = SettingsSync.decide(fingerprint, settings.changedAt(), state, remote.settings)) {
                    is SettingsSync.Decision.InSync -> settingsAfter = fingerprint to decision.stamp
                    is SettingsSync.Decision.Upload -> {
                        backend.put(SyncNames.SETTINGS, SyncCrypto.seal(key, SyncNames.SETTINGS, settings.export().toByteArray(), compress = true))
                        settingsEntry = SettingsEntry(decision.stamp, fingerprint)
                        settingsAfter = fingerprint to decision.stamp
                        report.settings = SettingsOutcome.Sent
                    }
                    SettingsSync.Decision.Apply -> {
                        val entry = remote.settings
                        val blob = backend.get(SyncNames.SETTINGS)
                        if (entry == null || blob == null) {
                            report.warnings += "The settings file is missing at the sync location"
                        } else {
                            try {
                                settings.apply(String(SyncCrypto.open(key, SyncNames.SETTINGS, blob), Charsets.UTF_8))
                                settingsAfter = settings.fingerprint() to entry.updatedAt
                                report.settings = SettingsOutcome.Received
                            } catch (e: SyncDecryptException) {
                                report.warnings += "The settings at the sync location could not be read"
                            }
                        }
                    }
                }
            }

            // Write the index last. Read it again first so a change another device just made is not lost.
            val target = SyncManifest(finalChats, settingsEntry)
            if (target.pruned(now) != remote) {
                check(options)
                options.onProgress("Updating the index")
                val latest = try {
                    readManifest()
                } catch (e: SyncDecryptException) {
                    null
                } catch (e: JSONException) {
                    null
                }
                val merged = (if (latest != null) target.merge(latest) else target).pruned(now)
                backend.put(SyncNames.MANIFEST, SyncCrypto.seal(key, SyncNames.MANIFEST, merged.toJson().toByteArray(), compress = true))
            }
            succeeded = true
        } finally {
            stateStore.update { s ->
                s.copy(
                    uploaded = (s.uploaded + newlyUploaded) - noLongerUploaded,
                    settingsFingerprint = if (succeeded) settingsAfter?.first ?: s.settingsFingerprint else s.settingsFingerprint,
                    settingsStamp = if (succeeded) settingsAfter?.second ?: s.settingsStamp else s.settingsStamp,
                    lastSyncAt = if (succeeded) now else s.lastSyncAt,
                )
            }
        }
        return report
    }

    private fun check(options: SyncOptions) {
        if (options.cancelled()) throw CancellationException("Sync was stopped")
    }

    /** Makes sure the sync location was made with the key we hold; recreates the header if the location was emptied. */
    private fun checkHeader() {
        val bytes = backend.get(SyncNames.HEADER)
        if (bytes == null) {
            backend.put(SyncNames.HEADER, header.toJson().toByteArray())
            return
        }
        val remote = SyncHeader.fromJson(String(bytes, Charsets.UTF_8), minIterations = 1)
        if (!remote.sameKeyAs(header)) {
            throw SyncPassphraseChangedException()
        }
    }

    private fun readManifest(): SyncManifest? {
        val blob = backend.get(SyncNames.MANIFEST) ?: return null
        return SyncManifest.fromJson(String(SyncCrypto.open(key, SyncNames.MANIFEST, blob), Charsets.UTF_8))
    }

    /** The index, or an empty one if it is missing or damaged (everything on this phone is then sent again). */
    private fun loadManifest(report: SyncReport): SyncManifest = try {
        readManifest() ?: SyncManifest()
    } catch (e: SyncDecryptException) {
        report.warnings += "The index at the sync location was damaged and is being rebuilt"
        SyncManifest()
    } catch (e: JSONException) {
        report.warnings += "The index at the sync location was damaged and is being rebuilt"
        SyncManifest()
    }

    /** Every chat saved here, with the time it last changed. */
    private fun scanChats(): MutableMap<String, Long> {
        val found = HashMap<String, Long>()
        for (file in chatsDir.listFiles().orEmpty()) {
            val id = chatFile.matchEntire(file.name)?.groupValues?.get(1) ?: continue
            val updatedAt = readUpdatedAt(file) ?: continue
            found[id] = updatedAt
        }
        return found
    }

    private fun readUpdatedAt(file: File): Long? =
        runCatching { JSONObject(file.readText()).optLong("updatedAt", -1).takeIf { it >= 0 } }.getOrNull()

    /** Sends a chat with its photos and documents. Returns when it last changed, or null if it could not be read and was skipped. */
    private fun upload(id: String, state: SyncState, uploadedNow: MutableSet<String>, report: SyncReport): Long? {
        val chat = try {
            JSONObject(File(chatsDir, "$id.json").readText())
        } catch (e: Exception) {
            report.warnings += "A chat on this phone could not be read and was skipped"
            return null
        }
        val media = SyncChats.media(chat)
        val known = state.uploaded + uploadedNow
        for (name in media.images) {
            val remoteName = SyncNames.image(name)
            if (remoteName in known) continue
            val file = File(imagesDir, name)
            if (!file.isFile) {
                report.warnings += "A photo in a chat is missing on this phone"
                continue
            }
            backend.put(remoteName, SyncCrypto.seal(key, remoteName, file.readBytes()))
            uploadedNow += remoteName
        }
        for (doc in media.docs) {
            val remoteName = SyncNames.doc(doc)
            if (remoteName in known) continue
            val file = File(docsDir, "$doc.txt")
            if (!file.isFile) {
                report.warnings += "An attached document is missing on this phone"
                continue
            }
            backend.put(remoteName, SyncCrypto.seal(key, remoteName, file.readBytes(), compress = true))
            uploadedNow += remoteName
        }
        val remoteName = SyncNames.chat(id)
        backend.put(remoteName, SyncCrypto.seal(key, remoteName, SyncChats.portable(chat).toString().toByteArray(), compress = true))
        return chat.optLong("updatedAt")
    }

    /** Fetches a chat and its photos and documents. False if it could not be used and was skipped. */
    private fun download(id: String, entry: ManifestEntry, options: SyncOptions, report: SyncReport): Boolean {
        val remoteName = SyncNames.chat(id)
        val blob = backend.get(remoteName)
        if (blob == null) {
            report.warnings += "A chat listed at the sync location is missing there"
            return false
        }
        val chat = try {
            JSONObject(String(SyncCrypto.open(key, remoteName, blob), Charsets.UTF_8))
        } catch (e: SyncDecryptException) {
            report.warnings += "A chat at the sync location could not be read"
            return false
        } catch (e: JSONException) {
            report.warnings += "A chat at the sync location could not be read"
            return false
        }
        SyncChats.problem(chat, id)?.let {
            report.warnings += "A chat at the sync location was skipped: $it"
            return false
        }

        val media = SyncChats.media(chat)
        imagesDir.mkdirs()
        for (name in media.images) {
            val target = File(imagesDir, name)
            if (target.isFile) continue
            val name2 = SyncNames.image(name)
            val photo = backend.get(name2)?.let { runCatching { SyncCrypto.open(key, name2, it) }.getOrNull() }
            if (photo == null) report.warnings += "A photo in a chat is missing at the sync location" else target.writeBytes(photo)
        }
        docsDir.mkdirs()
        for (doc in media.docs) {
            val target = File(docsDir, "$doc.txt")
            if (target.isFile) continue
            val name2 = SyncNames.doc(doc)
            val text = backend.get(name2)?.let { runCatching { SyncCrypto.open(key, name2, it) }.getOrNull() }
            if (text == null) report.warnings += "An attached document is missing at the sync location" else target.writeBytes(text)
        }

        // The user may have saved this chat again while it was being fetched; never replace something newer.
        val target = File(chatsDir, "$id.json")
        val incoming = chat.optLong("updatedAt")
        if (target.isFile && (readUpdatedAt(target) ?: -1) >= maxOf(incoming, entry.updatedAt)) return false
        chatsDir.mkdirs()
        val tmp = File(chatsDir, "$id.json.tmp")
        tmp.writeText(SyncChats.localize(chat, imagesDir).toString())
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
        options.onChatChanged(id)
        return true
    }

    /** Deletes a chat here along with its photos and documents. */
    private fun removeLocal(id: String, options: SyncOptions) {
        val file = File(chatsDir, "$id.json")
        runCatching { SyncChats.media(JSONObject(file.readText())) }.getOrNull()?.let { media ->
            media.images.forEach { File(imagesDir, it).delete() }
            media.docs.forEach { File(docsDir, "$it.txt").delete() }
        }
        file.delete()
        options.onChatChanged(id)
    }
}
