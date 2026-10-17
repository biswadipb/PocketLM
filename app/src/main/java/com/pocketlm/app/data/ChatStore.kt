package com.pocketlm.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Speed of one reply. [tokensPerSecond] is null when fewer than two tokens were produced. */
data class GenStats(val tokens: Int, val tokensPerSecond: Float?, val firstTokenMs: Long)

data class StoredMessage(
    val text: String,
    val isUser: Boolean,
    val stats: GenStats? = null,
    val imagePath: String? = null,
    /** True for a summary that stands in for everything said before it. */
    val summary: Boolean = false,
    /** Earlier versions of this reply (from regenerating), and where the shown one sits among all of them. */
    val others: List<Reply> = emptyList(),
    val position: Int = 0,
    /** True if the user starred this message to find it again. */
    val starred: Boolean = false,
    /** Photos after the first one ([imagePath]), when a message carries several. */
    val moreImages: List<String> = emptyList(),
    /** The passages from documents and notes the model was shown for this reply. */
    val sources: List<SourceRef> = emptyList(),
) {
    /** Every photo sent with this message, in order. */
    val images: List<String> get() = listOfNotNull(imagePath) + moreImages
}

/**
 * Reading and rewriting the photos of a stored message. The first photo is kept under "image", as before, and any
 * others under "moreImages", so a chat with one photo is stored exactly as it always was.
 */
object MessageImages {
    fun paths(message: JSONObject): List<String> {
        val first = message.optString("image").takeIf { it.isNotEmpty() }
        val more = message.optJSONArray("moreImages")
        return listOfNotNull(first) + List(more?.length() ?: 0) { more!!.optString(it) }.filter { it.isNotEmpty() }
    }

    /** Replaces each photo path with [transform]'s answer; a photo it answers null for is dropped. */
    fun rewrite(message: JSONObject, transform: (String) -> String?) {
        val kept = paths(message).mapNotNull(transform)
        if (kept.isEmpty()) message.remove("image") else message.put("image", kept.first())
        if (kept.size > 1) message.put("moreImages", JSONArray(kept.drop(1))) else message.remove("moreImages")
    }
}

data class SavedChat(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val modelFile: String?,
    val messages: List<StoredMessage>,
    val attachments: List<Attachment> = emptyList(),
    val pinned: Boolean = false,
    /** True once the user renamed the chat, so saving no longer derives the title from the first message. */
    val customTitle: Boolean = false,
    /** System prompt preset chosen for this chat; null means the app-wide default. */
    val presetId: String? = null,
    /** Whether replies in this chat are restricted to valid JSON. */
    val jsonMode: Boolean = false,
    /** A JSON Schema the replies in this chat must follow, or null. */
    val jsonSchema: String? = null,
    /** Labels the user gave this chat, used to filter History. See [ChatTags]. */
    val tags: List<String> = emptyList(),
    /** The project this chat belongs to (see [Project]), or null. Projects stay on the phone that made them. */
    val projectId: String? = null,
    /** False when the user switched off the thinking section of a reasoning model for this chat. */
    val thinking: Boolean = true,
    /** Whether this chat searches the user's notes folder; null follows the app-wide choice at the time. */
    val useNotes: Boolean? = null,
    /** A grammar (GBNF) the replies in this chat must follow, or null. It replaces JSON mode while it is set. */
    val grammar: String? = null,
)

/** Saves each conversation as a small JSON file in the app's private storage. */
class ChatStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "chats").also { it.mkdirs() }

    suspend fun list(): List<SavedChat> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.isFile && f.extension == "json" }
            .orEmpty()
            .mapNotNull { runCatching { ChatCodec.decode(it.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun save(chat: SavedChat) = withContext(Dispatchers.IO) { saveNow(chat) }

    /** The same as [save] for code that already runs off the main thread. */
    fun saveNow(chat: SavedChat) {
        val target = File(dir, "${chat.id}.json")
        val tmp = File(dir, "${chat.id}.json.tmp")
        tmp.writeText(ChatCodec.encode(chat).toString())
        tmp.renameTo(target)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
    }
}

/** Turns a chat into the JSON kept on disk (and synced), and back. Pure, so it can be tested. */
object ChatCodec {

    fun encode(chat: SavedChat): JSONObject = JSONObject()
        .put("id", chat.id)
        .put("title", chat.title)
        .put("updatedAt", chat.updatedAt)
        .put("modelFile", chat.modelFile ?: JSONObject.NULL)
        .put("pinned", chat.pinned)
        .put("customTitle", chat.customTitle)
        .put("presetId", chat.presetId ?: JSONObject.NULL)
        .put("jsonMode", chat.jsonMode)
        .put("jsonSchema", chat.jsonSchema ?: JSONObject.NULL)
        .apply { if (chat.tags.isNotEmpty()) put("tags", JSONArray(chat.tags)) }
        .apply { chat.projectId?.let { put("projectId", it) } }
        .apply { if (!chat.thinking) put("thinking", false) }
        .apply { chat.useNotes?.let { put("useNotes", it) } }
        .apply { chat.grammar?.let { put("grammar", it) } }
        .put(
            "attachments",
            JSONArray(chat.attachments.map {
                JSONObject().put("id", it.id).put("name", it.name).put("chars", it.chars)
            }),
        )
        .put(
            "messages",
            JSONArray(chat.messages.map { m ->
                JSONObject().put("text", m.text).put("isUser", m.isUser).apply {
                    m.imagePath?.let { put("image", it) }
                    if (m.moreImages.isNotEmpty()) put("moreImages", JSONArray(m.moreImages))
                    putStats(this, m.stats)
                    if (m.summary) put("summary", true)
                    if (m.starred) put("starred", true)
                    if (m.sources.isNotEmpty()) put("sources", Sources.toJson(m.sources))
                    if (m.others.isNotEmpty()) {
                        put("others", JSONArray(m.others.map { r ->
                            JSONObject().put("text", r.text).also {
                                putStats(it, r.stats)
                                if (r.sources.isNotEmpty()) it.put("sources", Sources.toJson(r.sources))
                            }
                        }))
                        put("position", m.position)
                    }
                }
            }),
        )

    fun decode(text: String): SavedChat {
        val o = JSONObject(text)
        val arr = o.getJSONArray("messages")
        val att = o.optJSONArray("attachments")
        val tagList = o.optJSONArray("tags")
        return SavedChat(
            id = o.getString("id"),
            title = o.getString("title"),
            updatedAt = o.getLong("updatedAt"),
            modelFile = if (o.isNull("modelFile")) null else o.getString("modelFile"),
            pinned = o.optBoolean("pinned", false),
            customTitle = o.optBoolean("customTitle", false),
            presetId = if (o.isNull("presetId")) null else o.optString("presetId").ifEmpty { null },
            jsonMode = o.optBoolean("jsonMode", false),
            jsonSchema = if (o.isNull("jsonSchema")) null else o.optString("jsonSchema").ifEmpty { null },
            tags = ChatTags.clean(List(tagList?.length() ?: 0) { tagList!!.optString(it) }),
            projectId = o.optString("projectId", "").ifEmpty { null },
            thinking = o.optBoolean("thinking", true),
            useNotes = if (o.has("useNotes")) o.optBoolean("useNotes") else null,
            grammar = o.optString("grammar", "").ifEmpty { null },
            attachments = List(att?.length() ?: 0) {
                val a = att!!.getJSONObject(it)
                Attachment(a.getString("id"), a.getString("name"), a.optInt("chars"))
            },
            messages = List(arr.length()) {
                val m = arr.getJSONObject(it)
                val others = m.optJSONArray("others")
                StoredMessage(
                    m.getString("text"),
                    m.getBoolean("isUser"),
                    readStats(m),
                    if (m.has("image")) m.getString("image") else null,
                    summary = m.optBoolean("summary", false),
                    others = List(others?.length() ?: 0) {
                        val r = others!!.getJSONObject(it)
                        Reply(r.getString("text"), readStats(r), Sources.fromJson(r.optJSONArray("sources")))
                    },
                    position = m.optInt("position", 0),
                    starred = m.optBoolean("starred", false),
                    moreImages = MessageImages.paths(m).drop(1),
                    sources = Sources.fromJson(m.optJSONArray("sources")),
                )
            },
        )
    }

    private fun putStats(o: JSONObject, stats: GenStats?) {
        stats?.let {
            o.put("tokens", it.tokens)
            o.put("tps", it.tokensPerSecond?.toDouble() ?: JSONObject.NULL)
            o.put("ttft", it.firstTokenMs)
        }
    }

    private fun readStats(m: JSONObject): GenStats? =
        if (m.has("tokens")) {
            GenStats(
                tokens = m.getInt("tokens"),
                tokensPerSecond = if (m.isNull("tps")) null else m.getDouble("tps").toFloat(),
                firstTokenMs = m.getLong("ttft"),
            )
        } else null
}
