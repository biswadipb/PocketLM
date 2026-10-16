package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONObject

/** A reusable message the user can drop into the message box. */
data class SavedPrompt(val id: String, val title: String, val text: String, val builtIn: Boolean = false)

object PromptLibrary {
    val defaults = listOf(
        SavedPrompt("sum", "Summarise", "Summarise the following in three short bullet points:\n\n", builtIn = true),
        SavedPrompt("simple", "Explain simply", "Explain this in plain language, as if to a beginner:\n\n", builtIn = true),
        SavedPrompt("grammar", "Fix grammar", "Fix the spelling and grammar in the text below. Keep the meaning and tone:\n\n", builtIn = true),
        SavedPrompt("translate", "Translate to English", "Translate the following into English:\n\n", builtIn = true),
        SavedPrompt("ideas", "Brainstorm", "Give me ten varied ideas for: ", builtIn = true),
    )

    fun toJson(custom: List<SavedPrompt>): String =
        JSONArray(custom.map { JSONObject().put("id", it.id).put("title", it.title).put("text", it.text) }).toString()

    fun fromJson(json: String?): List<SavedPrompt> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                SavedPrompt(o.getString("id"), o.getString("title"), o.getString("text"))
            }
        }.getOrDefault(emptyList())
    }
}
