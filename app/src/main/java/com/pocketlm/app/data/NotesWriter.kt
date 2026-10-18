package com.pocketlm.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.time.LocalDate

/** The notes folder was chosen without permission to add files to it. */
class NotesReadOnlyException : IOException(
    "PocketLM can only read your notes folder. Choose the folder again in Settings, My notes, and allow editing.",
)

/**
 * Adds a note to a "PocketLM" folder inside the folder the person chose for My notes. Nothing already in the folder is
 * changed or removed: every note is a new file.
 */
class NotesWriter(private val context: Context) {

    /** Writes [content] as a new Markdown file named after [title]; returns its path inside the notes folder. */
    fun write(tree: Uri, title: String, date: LocalDate, content: String): String {
        if (!NotesScanner.canWrite(context, tree)) throw NotesReadOnlyException()
        val root = DocumentFile.fromTreeUri(context, tree)?.takeIf { it.exists() }
            ?: throw IOException("The notes folder is no longer available. Choose it again in Settings, My notes.")
        val folder = root.findFile(NoteExport.FOLDER)?.takeIf { it.isDirectory }
            ?: root.createDirectory(NoteExport.FOLDER)
            ?: throw IOException("Could not make a ${NoteExport.FOLDER} folder inside the notes folder.")

        val name = NoteExport.fileName(title, date, namesIn(tree, folder))
        val file = folder.createFile("text/markdown", name) ?: throw IOException("Could not create the note.")
        try {
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                ?: throw IOException("Could not write the note.")
        } catch (e: Exception) {
            runCatching { file.delete() }
            throw if (e is IOException) e else IOException("Could not write the note: ${e.message}", e)
        }
        return "${NoteExport.FOLDER}/${file.name ?: name}"
    }

    /** The names of the files in [folder], from one query instead of one per file. */
    private fun namesIn(tree: Uri, folder: DocumentFile): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(folder.uri))
        val names = HashSet<String>()
        runCatching {
            context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) c.getString(0)?.let(names::add)
            }
        }
        return names
    }
}
