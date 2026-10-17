package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A set of instructions for one kind of job, such as preparing for a meeting. The model sees only the name and the
 * one-line [description] of each skill; when one fits, it loads the full [instructions] with the skill tool. That keeps
 * the model's small memory free until a skill is actually needed.
 */
data class Skill(
    val id: String,
    /** Lower case words joined with hyphens, such as "prep-for-meetings". This is what the model asks for. */
    val name: String,
    val description: String,
    val instructions: String,
    val enabled: Boolean = true,
    /** The id of the [SkillCatalog] entry it was added from, so Discover can show it as added. */
    val source: String? = null,
)

object Skills {
    const val KEY = "skills"
    const val MAX_SKILLS = 30
    const val MAX_NAME_CHARS = 40
    const val MAX_DESCRIPTION_CHARS = 200
    const val MAX_INSTRUCTIONS_CHARS = 4000

    /** The most of the list of skills put in the system prompt. A long list would crowd out the conversation. */
    private const val MAX_LISTING_CHARS = 1500

    /** [text] as a skill name: lower case letters and digits, with single hyphens between words. */
    fun slug(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(MAX_NAME_CHARS).trim('-')

    /** What is wrong with [skill], or null. [others] are the other skills, whose names it may not repeat. */
    fun problem(skill: Skill, others: List<Skill>): String? = when {
        skill.name.isBlank() -> "Give the skill a name."
        slug(skill.name) != skill.name -> "The name can only use lower case letters, digits and hyphens, such as prep-for-meetings."
        skill.name.length < 2 -> "The name is too short."
        others.any { it.id != skill.id && it.name == skill.name } -> "You already have a skill called ${skill.name}."
        skill.description.isBlank() -> "Write a one-line description, so the model knows when to use it."
        skill.description.length > MAX_DESCRIPTION_CHARS -> "The description is longer than $MAX_DESCRIPTION_CHARS characters."
        skill.instructions.isBlank() -> "Write the instructions."
        skill.instructions.length > MAX_INSTRUCTIONS_CHARS -> "The instructions are longer than $MAX_INSTRUCTIONS_CHARS characters."
        else -> null
    }

    fun toJson(all: List<Skill>): String = JSONArray(all.map {
        JSONObject()
            .put("id", it.id).put("name", it.name).put("description", it.description)
            .put("instructions", it.instructions).put("enabled", it.enabled)
            .put("source", it.source ?: JSONObject.NULL)
    }).toString()

    fun fromJson(text: String?): List<Skill> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(text)
            val out = ArrayList<Skill>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val skill = Skill(
                    id = o.optString("id").ifEmpty { continue },
                    name = o.optString("name"),
                    description = o.optString("description"),
                    instructions = o.optString("instructions"),
                    enabled = o.optBoolean("enabled", true),
                    source = o.optString("source", "").ifEmpty { null },
                )
                if (problem(skill, out) == null) out += skill
            }
            out.take(MAX_SKILLS)
        }.getOrDefault(emptyList())
    }

    fun enabled(all: List<Skill>): List<Skill> = all.filter { it.enabled }

    /** The skill the model asked for. It may write the name with spaces, capitals or underscores. */
    fun find(all: List<Skill>, asked: String): Skill? {
        val wanted = slug(asked.replace('_', ' '))
        if (wanted.isEmpty()) return null
        return enabled(all).firstOrNull { it.name == wanted }
    }

    /** The list the model is shown: one line for each switched-on skill. Empty when there are none. */
    fun listing(all: List<Skill>): String {
        val lines = ArrayList<String>()
        var used = 0
        for (s in enabled(all)) {
            val line = "- ${s.name}: ${s.description.replace(Regex("\\s+"), " ").trim()}"
            if (used + line.length > MAX_LISTING_CHARS) break
            lines += line
            used += line.length + 1
        }
        return lines.joinToString("\n")
    }

    /** What goes back to the model when it loads [skill]. */
    fun loaded(skill: Skill): String =
        "Instructions of the skill ${skill.name}. Follow them for the user's request:\n${skill.instructions.trim()}"
}
