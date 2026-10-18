package com.pocketlm.app.data

import org.json.JSONObject
import java.io.File
import java.util.TreeMap
import java.util.concurrent.TimeUnit

/** Names of the files kept at the sync location. Everything except the header is encrypted. */
object SyncNames {
    const val HEADER = "pocketlm-sync.json"
    const val MANIFEST = "manifest.bin"
    const val SETTINGS = "settings.bin"

    private val ID = Regex("""[A-Za-z0-9-]{1,64}""")
    private val IMAGE = Regex("""[A-Za-z0-9._-]{1,120}\.(jpg|jpeg|png)""")
    private val ANY = Regex("""[A-Za-z0-9._-]{1,200}""")

    fun chat(id: String) = "c-$id.bin"
    fun image(fileName: String) = "i-$fileName.bin"
    fun doc(id: String) = "d-$id.bin"

    fun validId(id: String) = ID.matches(id)
    fun validImage(fileName: String) = IMAGE.matches(fileName)

    /** Every name the backends will read or write; anything else (slashes, dots-only) is refused. */
    fun valid(name: String) = ANY.matches(name) && name != "." && name != ".."
}

/** One chat in the manifest: when it last changed, or when it was deleted. */
data class ManifestEntry(val updatedAt: Long, val deleted: Boolean = false) {

    /** True if this is the later of the two changes. Deleting wins a tie, so a delete is never undone by a stale copy. */
    fun isNewerThan(other: ManifestEntry): Boolean =
        updatedAt > other.updatedAt || (updatedAt == other.updatedAt && deleted && !other.deleted)
}

/** The synced settings as last uploaded: when, and a fingerprint of their content. */
data class SettingsEntry(val updatedAt: Long, val fingerprint: String)

/**
 * A small index of what is stored at the sync location, so a sync reads one file instead of listing everything.
 * Deleted chats stay in it as tombstones for a while, so other devices learn about the deletion.
 */
data class SyncManifest(
    val chats: Map<String, ManifestEntry> = emptyMap(),
    val settings: SettingsEntry? = null,
) {
    /** The newest version of every chat and of the settings across two copies of the index. */
    fun merge(other: SyncManifest): SyncManifest {
        val merged = HashMap(chats)
        for ((id, theirs) in other.chats) {
            val mine = merged[id]
            if (mine == null || theirs.isNewerThan(mine)) merged[id] = theirs
        }
        val newestSettings = listOfNotNull(settings, other.settings).maxByOrNull { it.updatedAt }
        return SyncManifest(merged, newestSettings)
    }

    /** Forgets deletions older than [retentionMs]. A device that was offline for longer may bring a deleted chat back. */
    fun pruned(now: Long, retentionMs: Long = SyncPlanner.TOMBSTONE_RETENTION_MS): SyncManifest =
        copy(chats = chats.filterValues { !(it.deleted && now - it.updatedAt > retentionMs) })

    fun toJson(): String {
        val chatsJson = JSONObject()
        for ((id, e) in TreeMap(chats)) {
            chatsJson.put(id, JSONObject().put("u", e.updatedAt).apply { if (e.deleted) put("d", 1) })
        }
        val o = JSONObject().put("v", 1).put("chats", chatsJson)
        settings?.let { o.put("settings", JSONObject().put("u", it.updatedAt).put("f", it.fingerprint)) }
        return o.toString()
    }

    companion object {
        fun fromJson(text: String): SyncManifest {
            val o = JSONObject(text)
            if (o.optInt("v") != 1) throw SyncException("The sync index was written by a newer version of PocketLM.")
            val chats = HashMap<String, ManifestEntry>()
            o.optJSONObject("chats")?.let { c ->
                for (id in c.keys()) {
                    if (!SyncNames.validId(id)) continue
                    val e = c.optJSONObject(id) ?: continue
                    chats[id] = ManifestEntry(e.optLong("u"), e.optInt("d") == 1)
                }
            }
            val settings = o.optJSONObject("settings")?.let { SettingsEntry(it.optLong("u"), it.optString("f")) }
            return SyncManifest(chats, settings)
        }
    }
}

/** What to do with one chat to bring this phone and the sync location level. */
sealed interface SyncAction {
    val id: String

    data class Upload(override val id: String) : SyncAction
    data class Download(override val id: String) : SyncAction

    /** The chat was deleted elsewhere: remove it here. */
    data class DeleteHere(override val id: String) : SyncAction

    /** The chat was deleted here: record that at the sync location and remove its files there. */
    data class DeleteThere(override val id: String) : SyncAction
}

/** Decides, chat by chat, which side is newer. Pure, so conflicts can be tested without any storage. */
object SyncPlanner {
    val TOMBSTONE_RETENTION_MS: Long = TimeUnit.DAYS.toMillis(180)

    /**
     * What this phone knows: every chat it has, plus the chats it deleted. A chat edited after it was deleted
     * counts as alive; otherwise the deletion stands.
     */
    fun localView(chatUpdatedAt: Map<String, Long>, deletedAt: Map<String, Long>): Map<String, ManifestEntry> {
        val view = HashMap<String, ManifestEntry>()
        for ((id, at) in chatUpdatedAt) view[id] = ManifestEntry(at)
        for ((id, at) in deletedAt) {
            val alive = view[id]
            if (alive == null || at >= alive.updatedAt) view[id] = ManifestEntry(at, deleted = true)
        }
        return view
    }

    /** Last writer wins per chat, comparing the time it was last changed. Ids are visited in a fixed order. */
    fun plan(local: Map<String, ManifestEntry>, remote: Map<String, ManifestEntry>): List<SyncAction> {
        val actions = ArrayList<SyncAction>()
        for (id in (local.keys + remote.keys).toSortedSet()) {
            val mine = local[id]
            val theirs = remote[id]
            when {
                mine == null && theirs != null -> if (!theirs.deleted) actions += SyncAction.Download(id)
                theirs == null && mine != null -> actions += if (mine.deleted) SyncAction.DeleteThere(id) else SyncAction.Upload(id)
                mine != null && theirs != null -> when {
                    mine.isNewerThan(theirs) -> actions += if (mine.deleted) SyncAction.DeleteThere(id) else SyncAction.Upload(id)
                    theirs.isNewerThan(mine) -> actions += if (theirs.deleted) SyncAction.DeleteHere(id) else SyncAction.Download(id)
                }
            }
        }
        return actions
    }
}

/** Decides what to do with the synced settings. They travel as one block, newest change wins. */
object SettingsSync {

    sealed interface Decision {
        /** Already the same, or nothing changed on either side. */
        data class InSync(val stamp: Long) : Decision
        data class Upload(val stamp: Long) : Decision
        data object Apply : Decision
    }

    /**
     * A fingerprint of the settings that sync, independent of the order they are stored in. Two phones with
     * the same settings have the same fingerprint, which is how a change is noticed without hooking every setter.
     */
    fun fingerprint(all: Map<String, *>, keys: Set<String>): String {
        val lines = TreeMap<String, String>()
        for ((key, value) in all) {
            if (key !in keys) continue
            val typed = when (value) {
                is String -> "s:$value"
                is Int -> "i:$value"
                is Float -> "f:$value"
                is Boolean -> "b:$value"
                else -> continue
            }
            lines[key] = typed
        }
        val text = lines.entries.joinToString("\n") { "${it.key}=${it.value}" }
        return SyncCrypto.sha256Hex(text.toByteArray())
    }

    /**
     * [localChangedAt] is when the settings were last edited here (best known), used only when both sides changed
     * since the last sync. A phone joining for the first time takes what is already there rather than overwriting it.
     */
    fun decide(localFingerprint: String, localChangedAt: Long, state: SyncState, remote: SettingsEntry?): Decision {
        val localChanged = state.settingsFingerprint != localFingerprint
        if (remote == null) {
            return Decision.Upload(if (localChanged) localChangedAt else state.settingsStamp.coerceAtLeast(1))
        }
        if (remote.fingerprint == localFingerprint) return Decision.InSync(remote.updatedAt)
        if (state.settingsFingerprint == null) return Decision.Apply
        val remoteChanged = remote.updatedAt != state.settingsStamp
        return when {
            !localChanged && !remoteChanged -> Decision.InSync(remote.updatedAt)
            !localChanged -> Decision.Apply
            !remoteChanged -> Decision.Upload(localChangedAt)
            localChangedAt > remote.updatedAt -> Decision.Upload(localChangedAt)
            else -> Decision.Apply
        }
    }
}

/** A chat deleted on this phone, remembered so the deletion can be passed on to the other devices. */
data class Tombstone(val at: Long, val files: List<String> = emptyList())

/**
 * What this phone remembers between syncs. It never leaves the phone.
 * [uploaded] are photos and documents already known to be at the sync location, so they are sent only once.
 */
data class SyncState(
    val tombstones: Map<String, Tombstone> = emptyMap(),
    val uploaded: Set<String> = emptySet(),
    val settingsFingerprint: String? = null,
    val settingsStamp: Long = 0,
    val lastSyncAt: Long = 0,
) {
    fun pruned(now: Long): SyncState =
        copy(tombstones = tombstones.filterValues { now - it.at <= SyncPlanner.TOMBSTONE_RETENTION_MS })

    fun toJson(): String {
        val tombs = JSONObject()
        for ((id, t) in TreeMap(tombstones)) {
            tombs.put(id, JSONObject().put("at", t.at).put("files", org.json.JSONArray(t.files)))
        }
        return JSONObject()
            .put("v", 1)
            .put("tombstones", tombs)
            .put("uploaded", org.json.JSONArray(uploaded.sorted()))
            .put("settingsFingerprint", settingsFingerprint ?: JSONObject.NULL)
            .put("settingsStamp", settingsStamp)
            .put("lastSyncAt", lastSyncAt)
            .toString()
    }

    companion object {
        fun fromJson(text: String?): SyncState {
            if (text.isNullOrBlank()) return SyncState()
            return try {
                val o = JSONObject(text)
                val tombs = HashMap<String, Tombstone>()
                o.optJSONObject("tombstones")?.let { t ->
                    for (id in t.keys()) {
                        val e = t.optJSONObject(id) ?: continue
                        val files = e.optJSONArray("files")
                        tombs[id] = Tombstone(e.optLong("at"), List(files?.length() ?: 0) { files!!.getString(it) })
                    }
                }
                val uploaded = o.optJSONArray("uploaded")
                SyncState(
                    tombstones = tombs,
                    uploaded = (0 until (uploaded?.length() ?: 0)).map { uploaded!!.getString(it) }.toSet(),
                    settingsFingerprint = if (o.isNull("settingsFingerprint")) null else o.optString("settingsFingerprint"),
                    settingsStamp = o.optLong("settingsStamp"),
                    lastSyncAt = o.optLong("lastSyncAt"),
                )
            } catch (e: Exception) {
                SyncState()
            }
        }
    }
}

/** [SyncState] kept in one file, safe to use from several threads. */
class SyncStateStore(private val file: File) {
    private var cached: SyncState? = null

    @Synchronized
    fun get(): SyncState = cached ?: SyncState.fromJson(runCatching { file.readText() }.getOrNull()).also { cached = it }

    @Synchronized
    fun update(change: (SyncState) -> SyncState): SyncState {
        val next = change(get())
        cached = next
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(next.toJson())
        if (!tmp.renameTo(file)) {
            file.writeText(next.toJson())
            tmp.delete()
        }
        return next
    }

    /** Remembers a deletion; [files] are the photo and document names to remove from the sync location. */
    fun addTombstone(id: String, at: Long, files: List<String>) {
        update { it.copy(tombstones = it.tombstones + (id to Tombstone(at, files))).pruned(at) }
    }

    @Synchronized
    fun clear() {
        cached = SyncState()
        file.delete()
    }
}
