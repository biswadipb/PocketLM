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
)

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
)

/** Saves each conversation as a small JSON file in the app's private storage. */
class ChatStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "chats").also { it.mkdirs() }

    suspend fun list(): List<SavedChat> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.isFile && f.extension == "json" }
            .orEmpty()
            .mapNotNull { runCatching { parse(it.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun save(chat: SavedChat) = withContext(Dispatchers.IO) {
        val json = JSONObject()
            .put("id", chat.id)
            .put("title", chat.title)
            .put("updatedAt", chat.updatedAt)
            .put("modelFile", chat.modelFile ?: JSONObject.NULL)
            .put("pinned", chat.pinned)
            .put("customTitle", chat.customTitle)
            .put("presetId", chat.presetId ?: JSONObject.NULL)
            .put("jsonMode", chat.jsonMode)
            .put("jsonSchema", chat.jsonSchema ?: JSONObject.NULL)
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
                        putStats(this, m.stats)
                        if (m.summary) put("summary", true)
                        if (m.others.isNotEmpty()) {
                            put("others", JSONArray(m.others.map { r ->
                                JSONObject().put("text", r.text).also { putStats(it, r.stats) }
                            }))
                            put("position", m.position)
                        }
                    }
                }),
            )
        val target = File(dir, "${chat.id}.json")
        val tmp = File(dir, "${chat.id}.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(target)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
    }

    private fun parse(text: String): SavedChat {
        val o = JSONObject(text)
        val arr = o.getJSONArray("messages")
        val att = o.optJSONArray("attachments")
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
                        Reply(r.getString("text"), readStats(r))
                    },
                    position = m.optInt("position", 0),
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
