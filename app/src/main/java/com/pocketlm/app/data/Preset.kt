package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONObject

/** A named system prompt. Built-in presets can't be edited or deleted. */
data class Preset(
    val id: String,
    val name: String,
    val prompt: String,
    val builtIn: Boolean = false,
)

object Presets {
    const val DEFAULT_ID = "default"

    val builtIn = listOf(
        Preset(
            DEFAULT_ID,
            "Default",
            "You are PocketLM, a helpful assistant running entirely on the user's phone.",
            builtIn = true,
        ),
        Preset(
            "concise",
            "Concise",
            "You are a helpful assistant. Answer as briefly as possible. Use short sentences and skip filler.",
            builtIn = true,
        ),
        Preset(
            "coder",
            "Coding helper",
            "You are an expert programmer. Give correct, idiomatic code with a short explanation. Ask a question if the request is ambiguous.",
            builtIn = true,
        ),
        Preset(
            "tutor",
            "Tutor",
            "You are a patient tutor. Explain ideas step by step in plain language, use examples, and check understanding.",
            builtIn = true,
        ),
    )

    fun toJson(custom: List<Preset>): String =
        JSONArray(
            custom.map { JSONObject().put("id", it.id).put("name", it.name).put("prompt", it.prompt) }
        ).toString()

    fun fromJson(json: String?): List<Preset> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                Preset(o.getString("id"), o.getString("name"), o.getString("prompt"))
            }
        }.getOrDefault(emptyList())
    }
}
