package com.pocketlm.app.data

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.zip.ZipInputStream

/** Where an exported conversation came from. */
enum class ImportSource(val label: String, private val idPrefix: String) {
    ChatGpt("ChatGPT", "chatgpt"),
    Claude("Claude", "claude");

    /** The same exported conversation always gets the same id, so importing a file twice does not duplicate chats. */
    fun chatId(sourceId: String): String = UUID.nameUUIDFromBytes("$idPrefix:$sourceId".toByteArray()).toString()
}

/** One conversation read from an export, before it is turned into a [SavedChat]. */
class ImportedChat(
    val source: ImportSource,
    val sourceId: String,
    val title: String,
    val updatedAt: Long,
    /** Alternating-ish turns, oldest first: text and whether the person (not the assistant) wrote it. */
    val turns: List<Pair<String, Boolean>>,
) {
    val id: String get() = source.chatId(sourceId)

    fun toSavedChat(): SavedChat {
        val first = turns.firstOrNull { it.second }?.first.orEmpty()
        val name = title.trim().ifEmpty { first.lineSequence().firstOrNull().orEmpty().trim() }.ifEmpty { "Imported chat" }
        return SavedChat(
            id = id,
            title = name.take(80),
            updatedAt = updatedAt,
            modelFile = null,
            messages = turns.map { StoredMessage(it.first, it.second) },
            customTitle = title.isNotBlank(),
            tags = ChatTags.clean(listOf("Imported", source.label)),
        )
    }
}

/** What an import did. */
data class ImportSummary(
    val imported: Int = 0,
    /** Chats already here (same export imported before) and not older than the file's copy. */
    val alreadyHere: Int = 0,
    /** Conversations with nothing to keep, such as ones that only held images or tool output. */
    val empty: Int = 0,
    /** Conversations that could not be read, or were too large. */
    val unreadable: Int = 0,
) {
    val total get() = imported + alreadyHere + empty + unreadable

    fun message(): String {
        if (total == 0) return "No conversations were found in that file."
        val parts = ArrayList<String>()
        parts += "Imported $imported ${if (imported == 1) "chat" else "chats"}"
        if (alreadyHere > 0) parts += "$alreadyHere already here"
        if (empty > 0) parts += "$empty empty skipped"
        if (unreadable > 0) parts += "$unreadable could not be read"
        return parts.joinToString(", ")
    }
}

/**
 * Walks the top-level array of a JSON file one element at a time, so an export of hundreds of megabytes is never
 * held in memory at once. Each element comes back as its own JSON text.
 */
object JsonArrayStream {

    /** Longest single element that is read; a longer one is skipped over (and reported as null). */
    const val MAX_ELEMENT_CHARS = 48_000_000

    fun elements(reader: Reader, maxChars: Int = MAX_ELEMENT_CHARS): Sequence<String?> = sequence {
        val input = if (reader is BufferedReader) reader else BufferedReader(reader, 64 * 1024)
        var c = skipSpace(input)
        if (c == 0xFEFF) c = skipSpace(input)            // byte order mark
        if (c != '['.code) throw IOException("That file is not a list of conversations.")
        while (true) {
            c = skipSpace(input)
            if (c < 0) throw IOException("The file ends in the middle of the list.")
            if (c == ','.code) continue
            if (c == ']'.code) return@sequence

            val text = StringBuilder()
            var kept = true
            fun add(ch: Int) {
                if (!kept) return
                if (text.length >= maxChars) { kept = false; text.setLength(0) } else text.append(ch.toChar())
            }
            when (c.toChar()) {
                '{', '[' -> {
                    var depth = 0
                    var inString = false
                    var escaped = false
                    var ch = c
                    while (true) {
                        add(ch)
                        if (inString) {
                            if (escaped) escaped = false else if (ch == '\\'.code) escaped = true else if (ch == '"'.code) inString = false
                        } else when (ch.toChar()) {
                            '"' -> inString = true
                            '{', '[' -> depth++
                            '}', ']' -> if (--depth == 0) break
                        }
                        ch = input.read()
                        if (ch < 0) throw IOException("The file ends in the middle of a conversation.")
                    }
                }
                '"' -> {
                    var escaped = false
                    add(c)
                    while (true) {
                        val ch = input.read()
                        if (ch < 0) throw IOException("The file ends in the middle of a conversation.")
                        add(ch)
                        if (escaped) escaped = false else if (ch == '\\'.code) escaped = true else if (ch == '"'.code) break
                    }
                }
                else -> {
                    add(c)
                    while (true) {
                        input.mark(1)
                        val ch = input.read()
                        if (ch < 0 || ch == ','.code || ch == ']'.code || ch.toChar().isWhitespace()) { if (ch >= 0) input.reset(); break }
                        add(ch)
                    }
                }
            }
            yield(if (kept) text.toString() else null)
        }
    }

    private fun skipSpace(input: Reader): Int {
        while (true) {
            val c = input.read()
            if (c < 0 || !c.toChar().isWhitespace()) return c
        }
    }
}

/** Reads the conversation lists that ChatGPT and Claude give when you export your data. */
object ChatImporter {

    private const val MAX_CHAT_CHARS = 2_000_000

    private val conversationFile = Regex("""(^|/)conversations(-\d+)?\.json$""", RegexOption.IGNORE_CASE)

    /**
     * Reads an export, either the .zip the services send or a conversations.json from inside it, and hands each
     * conversation to [accept], which says whether it was stored (true) or was already here (false).
     */
    fun read(input: InputStream, accept: (ImportedChat) -> Boolean): ImportSummary {
        val buffered = BufferedInputStream(input)
        buffered.mark(4)
        val head = ByteArray(4)
        val n = buffered.read(head)
        buffered.reset()
        val isZip = n >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        var summary = ImportSummary()
        if (isZip) {
            var found = false
            ZipInputStream(buffered).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !conversationFile.containsMatchIn(entry.name)) continue
                    found = true
                    summary = summary.plus(readList(nonClosing(zip), accept))
                }
            }
            if (!found) throw IOException("That zip file has no conversations.json in it.")
        } else {
            summary = readList(buffered, accept)
        }
        return summary
    }

    private fun readList(stream: InputStream, accept: (ImportedChat) -> Boolean): ImportSummary {
        var imported = 0
        var already = 0
        var empty = 0
        var unreadable = 0
        for (text in JsonArrayStream.elements(InputStreamReader(stream, Charsets.UTF_8))) {
            if (text == null) { unreadable++; continue }
            val parsed = runCatching { parse(JSONObject(text)) }.getOrElse { unreadable++; continue }
            when {
                parsed == null -> empty++
                accept(parsed) -> imported++
                else -> already++
            }
        }
        return ImportSummary(imported, already, empty, unreadable)
    }

    /** One exported conversation, or null if it has nothing worth keeping. Throws if it is not a conversation at all. */
    fun parse(conversation: JSONObject): ImportedChat? = when {
        conversation.has("mapping") -> parseChatGpt(conversation)
        conversation.has("chat_messages") -> parseClaude(conversation)
        else -> throw IOException("Not a ChatGPT or Claude conversation")
    }

    // ---- ChatGPT --------------------------------------------------------------------

    private fun parseChatGpt(c: JSONObject): ImportedChat? {
        val mapping = c.getJSONObject("mapping")
        // The conversation as it was last shown: from the final message back up through its parents.
        // Edited messages leave other branches in the tree; only this one is kept.
        var node = c.optString("current_node").takeIf { it.isNotEmpty() && mapping.has(it) } ?: lastLeaf(mapping)
        val path = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        while (node != null && seen.add(node)) {
            val entry = mapping.optJSONObject(node) ?: break
            path += entry
            node = if (entry.isNull("parent")) null else entry.optString("parent").takeIf { it.isNotEmpty() }
        }
        val turns = ArrayList<Pair<String, Boolean>>()
        for (entry in path.asReversed()) {
            val message = entry.optJSONObject("message") ?: continue
            val role = message.optJSONObject("author")?.optString("role").orEmpty()
            if (role != "user" && role != "assistant") continue
            if (message.optJSONObject("metadata")?.optBoolean("is_visually_hidden_from_conversation") == true) continue
            val recipient = message.optString("recipient", "all")
            if (role == "assistant" && recipient != "all" && recipient.isNotEmpty()) continue   // a call to a tool, not a reply
            val text = chatGptText(message.optJSONObject("content") ?: continue)
            if (text.isBlank()) continue
            turns.add(text to (role == "user"))
        }
        val id = c.optString("conversation_id").ifEmpty { c.optString("id") }.ifEmpty { return null }
        val time = c.optDouble("update_time").takeIf { !it.isNaN() && it > 0 }
            ?: c.optDouble("create_time").takeIf { !it.isNaN() && it > 0 }
        return build(ImportSource.ChatGpt, id, c.optString("title"), time?.let { (it * 1000).toLong() }, turns)
    }

    private fun lastLeaf(mapping: JSONObject): String? {
        var best: String? = null
        var bestTime = -1.0
        for (key in mapping.keys()) {
            val entry = mapping.optJSONObject(key) ?: continue
            if ((entry.optJSONArray("children")?.length() ?: 0) > 0) continue
            val t = entry.optJSONObject("message")?.optDouble("create_time")?.takeIf { !it.isNaN() } ?: 0.0
            if (t >= bestTime) { best = key; bestTime = t }
        }
        return best
    }

    private fun chatGptText(content: JSONObject): String {
        val parts = content.optJSONArray("parts")
        val text = when {
            parts != null -> (0 until parts.length()).mapNotNull { i ->
                when (val part = parts.opt(i)) {
                    is String -> part
                    is JSONObject -> if (part.optString("content_type").contains("image")) "[image]" else null
                    else -> null
                }
            }.joinToString("\n\n")
            content.optString("content_type") == "text" -> content.optString("text")
            else -> ""
        }
        return clean(text)
    }

    /** Removes the markers ChatGPT puts around citations (private-use characters), and surrounding blank space. */
    fun clean(text: String): String =
        text.replace(Regex("[^]*"), "").replace(Regex("[-]"), "").trim()

    // ---- Claude ---------------------------------------------------------------------

    private fun parseClaude(c: JSONObject): ImportedChat? {
        val messages = c.getJSONArray("chat_messages")
        val turns = ArrayList<Pair<String, Boolean>>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val sender = m.optString("sender")
            if (sender != "human" && sender != "assistant") continue
            val blocks = m.optJSONArray("content")
            var text = if (blocks != null && blocks.length() > 0) {
                (0 until blocks.length()).mapNotNull { b ->
                    blocks.optJSONObject(b)?.takeIf { it.optString("type") == "text" }?.optString("text")?.takeIf { it.isNotBlank() }
                }.joinToString("\n\n")
            } else m.optString("text")
            if (text.isBlank()) text = m.optString("text")
            val attached = m.optJSONArray("attachments")
            val names = (0 until (attached?.length() ?: 0)).mapNotNull { attached!!.optJSONObject(it)?.optString("file_name")?.takeIf { n -> n.isNotBlank() } }
            if (names.isNotEmpty()) text = (text + "\n\n" + names.joinToString("\n") { "[Attached file: $it]" }).trim()
            if (text.isBlank()) continue
            turns.add(text.trim() to (sender == "human"))
        }
        val id = c.optString("uuid").ifEmpty { return null }
        val time = parseTime(c.optString("updated_at")) ?: parseTime(c.optString("created_at"))
        return build(ImportSource.Claude, id, c.optString("name"), time, turns)
    }

    internal fun parseTime(text: String): Long? {
        if (text.isBlank()) return null
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
    }

    // ---- Shared ---------------------------------------------------------------------

    private fun build(source: ImportSource, id: String, title: String, time: Long?, rawTurns: List<Pair<String, Boolean>>): ImportedChat? {
        // Consecutive turns from the same side (for example several assistant steps) become one, so the chat alternates.
        val turns = ArrayList<Pair<String, Boolean>>()
        for ((text, isUser) in rawTurns) {
            val last = turns.lastOrNull()
            if (last != null && last.second == isUser) turns[turns.lastIndex] = (last.first + "\n\n" + text) to isUser
            else turns.add(text to isUser)
        }
        if (turns.none { it.second }) return null
        if (turns.sumOf { it.first.length } > MAX_CHAT_CHARS) throw IOException("This conversation is too large to import")
        return ImportedChat(source, id, title, time ?: System.currentTimeMillis(), turns)
    }

    private fun ImportSummary.plus(other: ImportSummary) = ImportSummary(
        imported + other.imported, alreadyHere + other.alreadyHere, empty + other.empty, unreadable + other.unreadable,
    )

    /** Lets the JSON reader finish without closing the zip it is reading from. */
    private fun nonClosing(stream: InputStream): InputStream = object : InputStream() {
        override fun read() = stream.read()
        override fun read(b: ByteArray, off: Int, len: Int) = stream.read(b, off, len)
        override fun close() {}
    }

    /** True if [existing] already holds this chat at least as recently as the file's copy, so it should be left alone. */
    fun alreadyUpToDate(existingUpdatedAt: Long?, incoming: ImportedChat): Boolean =
        existingUpdatedAt != null && existingUpdatedAt >= incoming.updatedAt

    /** For tests and callers that already hold the whole text of a small export. */
    fun readText(json: String): List<ImportedChat> {
        val out = ArrayList<ImportedChat>()
        read(json.byteInputStream()) { out += it; true }
        return out
    }
}
