package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONObject

/** A fact about the user that is added to every chat's system prompt while it is switched on. */
data class Note(val id: String, val text: String, val enabled: Boolean = true)

object MemoryNotes {
    const val MAX_NOTE_CHARS = 300
    const val MAX_TOTAL_CHARS = 1500

    fun toJson(notes: List<Note>): String =
        JSONArray(notes.map { JSONObject().put("id", it.id).put("text", it.text).put("on", it.enabled) }).toString()

    fun fromJson(json: String?): List<Note> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                Note(o.getString("id"), o.getString("text"), o.optBoolean("on", true))
            }
        }.getOrDefault(emptyList())
    }

    /** The notes that will be used, trimmed and kept within the size limits. */
    fun active(notes: List<Note>): List<String> {
        var total = 0
        val used = ArrayList<String>()
        for (note in notes) {
            if (!note.enabled) continue
            val text = note.text.trim().replace(Regex("\\s+"), " ").take(MAX_NOTE_CHARS)
            if (text.isEmpty()) continue
            if (total + text.length > MAX_TOTAL_CHARS) break
            used += text
            total += text.length
        }
        return used
    }

    /** [system] followed by the active notes, or [system] unchanged when there are none. */
    fun withNotes(system: String, notes: List<Note>): String {
        val used = active(notes)
        if (used.isEmpty()) return system
        return system.trimEnd() + "\n\nThings to remember about the user:\n" + used.joinToString("\n") { "- $it" }
    }
}
