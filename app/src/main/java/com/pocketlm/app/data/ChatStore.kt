package com.pocketlm.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Speed of one reply. [tokensPerSecond] is null when fewer than two tokens were produced. */
data class GenStats(val tokens: Int, val tokensPerSecond: Float?, val firstTokenMs: Long)

data class StoredMessage(val text: String, val isUser: Boolean, val stats: GenStats? = null)

data class SavedChat(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val modelFile: String?,
    val messages: List<StoredMessage>,
    val attachments: List<Attachment> = emptyList(),
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
                        m.stats?.let {
                            put("tokens", it.tokens)
                            put("tps", it.tokensPerSecond?.toDouble() ?: JSONObject.NULL)
                            put("ttft", it.firstTokenMs)
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
            attachments = List(att?.length() ?: 0) {
                val a = att!!.getJSONObject(it)
                Attachment(a.getString("id"), a.getString("name"), a.optInt("chars"))
            },
            messages = List(arr.length()) {
                val m = arr.getJSONObject(it)
                val stats = if (m.has("tokens")) {
                    GenStats(
                        tokens = m.getInt("tokens"),
                        tokensPerSecond = if (m.isNull("tps")) null else m.getDouble("tps").toFloat(),
                        firstTokenMs = m.getLong("ttft"),
                    )
                } else null
                StoredMessage(m.getString("text"), m.getBoolean("isUser"), stats)
            },
        )
    }
}
