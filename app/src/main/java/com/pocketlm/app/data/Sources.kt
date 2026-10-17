package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A passage the model was shown with a question: the document or note it came from, and the text. It is kept with the
 * reply so the person can see, and open, what the answer was based on. It records what the model *saw*, not what it used.
 */
data class SourceRef(
    /** The attached document's name, or the note's path inside the notes folder. */
    val name: String,
    val fromNotes: Boolean,
    val excerpt: String,
)

object Sources {
    /** More passages than this per reply are shown to the model but not kept, so chats and syncs stay small. */
    const val MAX_PER_REPLY = 6
    const val MAX_EXCERPT_CHARS = 900

    /** The passages put in front of the model, as references: attached documents first, repeats dropped. */
    fun of(attached: List<Chunk>, notes: List<Chunk>): List<SourceRef> {
        val seen = HashSet<Pair<String, String>>()
        val out = ArrayList<SourceRef>()
        for ((fromNotes, chunks) in listOf(false to attached, true to notes)) {
            for (chunk in chunks) {
                val text = chunk.text.trim()
                if (text.isEmpty() || !seen.add(chunk.docName to text.take(80))) continue
                out += SourceRef(chunk.docName, fromNotes, text.take(MAX_EXCERPT_CHARS))
                if (out.size == MAX_PER_REPLY) return out
            }
        }
        return out
    }

    /** The file name without its folders. */
    fun shortName(name: String): String = name.substringAfterLast('/').ifEmpty { name }

    /**
     * Text for each chip: the file name, with a number when the same file appears more than once so the chips can be
     * told apart.
     */
    fun labels(refs: List<SourceRef>): List<String> {
        val total = refs.groupingBy { shortName(it.name) }.eachCount()
        val seen = HashMap<String, Int>()
        return refs.map { ref ->
            val short = shortName(ref.name)
            if (total.getValue(short) == 1) short else "$short ${seen.merge(short, 1, Int::plus)}"
        }
    }

    /** One line naming the files, for exports: "plan.md, report.pdf". */
    fun names(refs: List<SourceRef>): String = refs.map { it.name }.distinct().joinToString(", ")

    fun toJson(refs: List<SourceRef>): JSONArray = JSONArray(refs.map {
        JSONObject().put("name", it.name).put("text", it.excerpt).apply { if (it.fromNotes) put("notes", true) }
    })

    fun fromJson(array: JSONArray?): List<SourceRef> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            SourceRef(name, o.optBoolean("notes", false), o.optString("text").take(MAX_EXCERPT_CHARS))
        }.take(MAX_PER_REPLY)
    }
}
