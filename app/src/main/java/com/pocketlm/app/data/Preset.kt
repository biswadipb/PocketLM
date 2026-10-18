package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A named system prompt. Built-in presets can't be edited or deleted. A preset with a [greeting] is a character: a
 * chat can start as it, with the greeting already said, and [emoji] marks it in lists.
 */
data class Preset(
    val id: String,
    val name: String,
    val prompt: String,
    val builtIn: Boolean = false,
    val greeting: String = "",
    val emoji: String = "",
) {
    val isCharacter: Boolean get() = greeting.isNotBlank()

    /** The name with its emoji in front, for lists. */
    val title: String get() = if (emoji.isBlank()) name else "${emoji.trim()} $name"
}

object Presets {
    const val DEFAULT_ID = "default"

    val builtIn = listOf(
        Preset(
            DEFAULT_ID,
            "Default",
            "You are Piko, a helpful assistant running entirely on the user's phone.",
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
        Preset(
            "storyteller",
            "Storyteller",
            "You run a text adventure. Describe the scene in a few vivid sentences, then offer two or three choices and wait for " +
                "the player to decide. Keep the story going from their choices, and never decide for them.",
            builtIn = true,
            greeting = "Welcome, traveller. Tell me where our story begins: a haunted lighthouse, a space station adrift, a quiet village with a secret? Or say \"surprise me\".",
            emoji = "\uD83D\uDCD6",
        ),
        Preset(
            "interviewer",
            "Interview coach",
            "You are a friendly but thorough interviewer helping the user practise. Ask one question at a time, wait for the " +
                "answer, then give brief, specific feedback and the next question. Stay in role.",
            builtIn = true,
            greeting = "Hi, thanks for coming in. Which role are we practising for today? Tell me a little about it and I'll start with the first question.",
            emoji = "\uD83C\uDFA4",
        ),
        Preset(
            "debater",
            "Debate partner",
            "You are a sharp, fair-minded debate partner. Take the opposite side of whatever the user argues, make your strongest " +
                "points briefly, and point out weak spots in their reasoning politely. Concede when they make a good point.",
            builtIn = true,
            greeting = "Pick a topic and tell me your side. I'll argue against it as well as I can.",
            emoji = "\u2696\uFE0F",
        ),
        Preset(
            "coach",
            "Piko coach",
            HealthPrompts.COACH,
            builtIn = true,
            greeting = HealthPrompts.COACH_GREETING,
            emoji = "\uD83D\uDCAA",
        ),
        Preset(
            "pirate",
            "Pirate",
            "You are a cheerful pirate captain. Answer helpfully and correctly, but always in pirate speech, with short sentences.",
            builtIn = true,
            greeting = "Ahoy, matey! What be troublin' ye today?",
            emoji = "\uD83C\uDFF4\u200D\u2620\uFE0F",
        ),
    )

    /** The presets a chat can start as. */
    fun characters(all: List<Preset>): List<Preset> = all.filter { it.isCharacter }

    fun toJson(custom: List<Preset>): String =
        JSONArray(
            custom.map {
                JSONObject().put("id", it.id).put("name", it.name).put("prompt", it.prompt)
                    .put("greeting", it.greeting).put("emoji", it.emoji)
            }
        ).toString()

    fun fromJson(json: String?): List<Preset> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                Preset(
                    o.getString("id"), o.getString("name"), o.getString("prompt"),
                    greeting = o.optString("greeting", ""), emoji = o.optString("emoji", ""),
                )
            }
        }.getOrDefault(emptyList())
    }
}
