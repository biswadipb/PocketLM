package com.pocketlm.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A project groups chats that share instructions and documents: every chat in it is told the instructions, and can
 * answer from the project's documents without attaching them again.
 */
data class Project(
    val id: String,
    val name: String,
    val instructions: String = "",
    val documents: List<Attachment> = emptyList(),
)

object Projects {
    const val MAX_NAME = 60
    const val MAX_INSTRUCTIONS = 4_000
    const val MAX_DOCUMENTS = 20

    fun toJson(projects: List<Project>): String = JSONArray(projects.map { p ->
        JSONObject().put("id", p.id).put("name", p.name).put("instructions", p.instructions)
            .put("documents", JSONArray(p.documents.map { JSONObject().put("id", it.id).put("name", it.name).put("chars", it.chars) }))
    }).toString()

    fun fromJson(json: String?): List<Project> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                val docs = o.optJSONArray("documents")
                Project(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    instructions = o.optString("instructions", ""),
                    documents = List(docs?.length() ?: 0) { j ->
                        val d = docs!!.getJSONObject(j)
                        Attachment(d.getString("id"), d.getString("name"), d.optInt("chars"))
                    },
                )
            }
        }.getOrDefault(emptyList())
    }

    /** A name worth keeping, or null when [raw] is blank. */
    fun cleanName(raw: String): String? = raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).ifEmpty { null }

    /** [system] with the project's instructions after it, or [system] unchanged when there are none. */
    fun withInstructions(system: String, project: Project?): String {
        val text = project?.instructions?.trim().orEmpty()
        if (project == null || text.isEmpty()) return system
        return system.trimEnd() + "\n\nThis chat belongs to the project \"${project.name}\". Follow these project instructions:\n" +
            text.take(MAX_INSTRUCTIONS)
    }
}

/** Keeps the projects in one small file in the app's private storage. */
class ProjectStore(context: Context) {
    private val file = File(context.filesDir, "projects.json")

    fun load(): List<Project> = runCatching { Projects.fromJson(file.takeIf { it.isFile }?.readText()) }.getOrDefault(emptyList())

    fun save(projects: List<Project>) {
        val tmp = File(file.parentFile, "projects.json.tmp")
        tmp.writeText(Projects.toJson(projects))
        tmp.renameTo(file)
    }
}
