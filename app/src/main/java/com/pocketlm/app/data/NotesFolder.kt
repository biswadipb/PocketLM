package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A file in the notes folder as it was when last looked at. [path] is relative to the folder, with `/` between parts. */
data class NoteStamp(val path: String, val size: Long, val modified: Long)

/** What a new look at the notes folder means for the saved index. */
data class NotesPlan(val read: List<NoteStamp>, val drop: List<String>, val keep: List<String>)

/** Which files in the notes folder are read, and how a rescan is kept cheap. Pure, so it can be tested. */
object NotesRules {
    enum class Kind { Text, Html, Pdf }

    const val MAX_FILES = 3_000
    const val MAX_DEPTH = 8
    const val MAX_TEXT_BYTES = 2_000_000L
    const val MAX_PDF_BYTES = 25_000_000L

    /** Past this much text in all, the rest of the folder is left out, to keep the phone's memory in check. */
    const val MAX_TOTAL_CHARS = 12_000_000L

    private val textExtensions = setOf("txt", "md", "markdown", "mdx", "csv", "tsv", "org", "rst", "log", "tex", "text")
    private val htmlExtensions = setOf("html", "htm")
    private val skippedFolders = setOf(".git", ".obsidian", ".trash", "node_modules", ".stfolder", ".thumbnails", "__macosx")

    fun kind(fileName: String): Kind? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext in textExtensions -> Kind.Text
            ext in htmlExtensions -> Kind.Html
            ext == "pdf" -> Kind.Pdf
            else -> null
        }
    }

    fun maxBytes(kind: Kind): Long = if (kind == Kind.Pdf) MAX_PDF_BYTES else MAX_TEXT_BYTES

    /** Hidden files and the folders apps keep their own data in are not notes. */
    fun skipName(name: String, isFolder: Boolean): Boolean =
        name.startsWith(".") || (isFolder && name.lowercase() in skippedFolders)

    /** Files that are new or changed are read again; files that went are dropped; the rest are kept as they are. */
    fun plan(known: Map<String, NoteStamp>, scanned: List<NoteStamp>): NotesPlan {
        val now = scanned.associateBy { it.path }
        val read = scanned.filter { known[it.path] != it }
        val keep = scanned.filter { known[it.path] == it }.map { it.path }
        val drop = known.keys.filter { it !in now }
        return NotesPlan(read, drop, keep)
    }

    /** A file name for what is saved about [path]: letters and digits only, so no path can escape the folder. */
    fun fileId(path: String): String =
        MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    /** The text put before the question: excerpts from attached documents and from the notes folder. */
    fun excerptsPrompt(question: String, attached: List<Chunk>, notes: List<Chunk>): String {
        if (attached.isEmpty() && notes.isEmpty()) return question
        val sources = listOfNotNull("attached documents".takeIf { attached.isNotEmpty() }, "notes".takeIf { notes.isNotEmpty() })
        val excerpts = (attached.map { "[${it.docName}]\n${it.text}" } + notes.map { "[notes: ${it.docName}]\n${it.text}" })
            .joinToString("\n\n")
        return "Answer using these excerpts from the user's ${sources.joinToString(" and ")}. " +
            "If they don't contain the answer, say so.\n\nExcerpts:\n$excerpts\n\nQuestion: $question"
    }

    fun statusLine(files: Int, passages: Int, withMeaning: Boolean): String = buildString {
        append(if (files == 1) "1 file" else "$files files").append(", ")
        append(if (passages == 1) "1 passage" else "$passages passages")
        if (withMeaning) append(", searchable by meaning")
    }
}

/**
 * What PocketLM keeps about the notes folder, in the app's private storage: the text of each file and, for search by
 * meaning, its saved vectors. It never changes the folder itself.
 */
class KnowledgeStore(private val dir: File) {

    private val manifestFile get() = File(dir, "index.json")

    @Synchronized
    fun manifest(): Map<String, NoteStamp> = runCatching {
        val array = JSONArray(manifestFile.readText())
        (0 until array.length()).associate { i ->
            val o = array.getJSONObject(i)
            val stamp = NoteStamp(o.getString("path"), o.getLong("size"), o.getLong("modified"))
            stamp.path to stamp
        }
    }.getOrDefault(emptyMap())

    @Synchronized
    fun saveManifest(stamps: Collection<NoteStamp>) {
        dir.mkdirs()
        val array = JSONArray(stamps.sortedBy { it.path }.map { JSONObject().put("path", it.path).put("size", it.size).put("modified", it.modified) })
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(manifestFile)) {
            manifestFile.writeText(array.toString())
            tmp.delete()
        }
    }

    @Synchronized
    fun writeText(path: String, text: String) {
        dir.mkdirs()
        File(dir, NotesRules.fileId(path) + ".txt").writeText(text)
        // Saved vectors belong to the old text.
        File(dir, NotesRules.fileId(path) + ".emb").delete()
    }

    fun readText(path: String): String? = File(dir, NotesRules.fileId(path) + ".txt").takeIf { it.isFile }?.readText()

    @Synchronized
    fun delete(path: String) {
        File(dir, NotesRules.fileId(path) + ".txt").delete()
        File(dir, NotesRules.fileId(path) + ".emb").delete()
    }

    fun readVectors(path: String): ByteArray? = File(dir, NotesRules.fileId(path) + ".emb").takeIf { it.isFile }?.readBytes()

    @Synchronized
    fun writeVectors(path: String, bytes: ByteArray) {
        if (File(dir, NotesRules.fileId(path) + ".txt").isFile) File(dir, NotesRules.fileId(path) + ".emb").writeBytes(bytes)
    }

    /** Every saved file as (path, text), in path order. */
    fun documents(): List<Pair<String, String>> =
        manifest().keys.sorted().mapNotNull { path -> readText(path)?.let { path to it } }

    fun totalChars(): Long = manifest().keys.sumOf { readText(it)?.length?.toLong() ?: 0L }

    /** Forgets everything saved about the folder. */
    @Synchronized
    fun clear() {
        dir.deleteRecursively()
    }
}
