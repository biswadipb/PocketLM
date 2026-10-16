package com.pocketlm.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Decides whether a saved chat state still describes the chat it was saved for. */
object ChatState {
    private const val FORMAT = 1

    /** A saved state is used again only when this label is identical to the one it was saved under. */
    fun tag(modelId: String, contextSize: Int, template: String, systemPrompt: String, turns: List<Turn>): String {
        val chat = MessageDigest.getInstance("SHA-256")
        for (turn in turns) {
            chat.update(byteArrayOf(if (turn.isUser) 1 else 0, if (turn.summary) 1 else 0))
            val bytes = turn.text.toByteArray()
            chat.update(bytes.size.toString().toByteArray())
            chat.update(0)
            chat.update(bytes)
        }
        return listOf(
            "v$FORMAT", modelId, contextSize.toString(), template, hash(systemPrompt), turns.size.toString(), hex(chat.digest()),
        ).joinToString("|")
    }

    fun modelId(fileName: String, sizeBytes: Long, modifiedMs: Long) = "$fileName:$sizeBytes:$modifiedMs"

    /** A short fingerprint of [text]. */
    fun hash(text: String): String = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray())).take(16)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}

/** How much saved chat state to keep, and which to throw away first. */
object StatePolicy {
    /** A single chat's state is not saved when it would be bigger than this. */
    const val MAX_STATE_BYTES = 256L * 1024 * 1024

    /** All saved states together are kept under this, oldest removed first. */
    const val BUDGET_BYTES = 768L * 1024 * 1024

    /** Free space that must stay free after saving. */
    const val FREE_SPACE_MARGIN = 512L * 1024 * 1024

    data class Entry(val chatId: String, val bytes: Long, val modifiedMs: Long)

    /** Chats whose state should be deleted to bring the total under [budget]; oldest first, never [keep]. */
    fun evict(entries: List<Entry>, budget: Long, keep: String? = null): List<String> {
        var total = entries.sumOf { it.bytes }
        val victims = ArrayList<String>()
        for (entry in entries.sortedBy { it.modifiedMs }) {
            if (total <= budget) break
            if (entry.chatId == keep) continue
            victims += entry.chatId
            total -= entry.bytes
        }
        return victims
    }

    /** The most a new state may take: the per-chat cap, and never so much that the phone runs out of space. */
    fun maxBytes(freeSpace: Long): Long = minOf(MAX_STATE_BYTES, freeSpace - FREE_SPACE_MARGIN).coerceAtLeast(0)
}

/**
 * Saved chat states live in the app's cache folder: they can always be rebuilt by reading the chat again,
 * so Android (or "Clear cache" in Storage) is free to remove them.
 */
class ChatStateCache(private val context: Context) {

    private val dir: File
        get() = File(context.cacheDir, "chat_state").also { it.mkdirs() }

    /** The path the engine writes "<prefix>.kv" and "<prefix>.meta" to. */
    fun prefix(chatId: String): String = File(dir, fileKey(chatId)).path

    fun maxBytes(): Long = StatePolicy.maxBytes(dir.usableSpace)

    suspend fun delete(chatId: String) = withContext(Dispatchers.IO) { remove(fileKey(chatId)) }

    suspend fun clear() = withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) { dir.listFiles().orEmpty().sumOf { it.length() } }

    /** Removes the oldest states until the total is within budget, keeping [keep]. */
    suspend fun trim(keep: String?) = withContext(Dispatchers.IO) {
        val keepKey = keep?.let(::fileKey)
        val entries = dir.listFiles { f -> f.extension == "kv" }.orEmpty().map { kv ->
            val key = kv.nameWithoutExtension
            val meta = File(dir, "$key.meta")
            StatePolicy.Entry(key, kv.length() + meta.length(), kv.lastModified())
        }
        StatePolicy.evict(entries, StatePolicy.BUDGET_BYTES, keepKey).forEach(::remove)
        // Leftovers of a save that was interrupted.
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    private fun remove(key: String) {
        File(dir, "$key.kv").delete()
        File(dir, "$key.meta").delete()
    }

    /** Chat ids are UUIDs; anything else is turned into a safe file name. */
    private fun fileKey(chatId: String): String =
        if (chatId.isNotEmpty() && chatId.length <= 64 && chatId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) chatId
        else ChatState.hash(chatId)
}
