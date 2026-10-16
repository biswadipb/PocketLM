package com.pocketlm.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class StoredMessage(val text: String, val isUser: Boolean)

data class SavedChat(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val modelFile: String?,
    val messages: List<StoredMessage>,
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
                "messages",
                JSONArray(chat.messages.map { JSONObject().put("text", it.text).put("isUser", it.isUser) }),
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
        return SavedChat(
            id = o.getString("id"),
            title = o.getString("title"),
            updatedAt = o.getLong("updatedAt"),
            modelFile = if (o.isNull("modelFile")) null else o.getString("modelFile"),
            messages = List(arr.length()) {
                val m = arr.getJSONObject(it)
                StoredMessage(m.getString("text"), m.getBoolean("isUser"))
            },
        )
    }
}
