package com.pocketlm.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/** A file found in the notes folder, with where to read it from. */
class FoundNote(val stamp: NoteStamp, val uri: Uri, val kind: NotesRules.Kind)

/**
 * Looks through a folder the user chose with Android's folder picker (it can be inside Google Drive, Dropbox, Nextcloud
 * or any other storage app) and reads the notes in it. Reading never changes the folder; saving a reply or a chat into
 * it is done separately by [NotesWriter], and only where the person allowed it.
 */
class NotesScanner(private val context: Context) {

    class Result(val files: List<FoundNote>, val tooMany: Boolean)

    /** All readable notes under [tree], found by asking each folder once for its children. */
    suspend fun scan(tree: Uri): Result {
        val found = ArrayList<FoundNote>()
        var tooMany = false

        suspend fun walk(folderDocId: String, prefix: String, depth: Int) {
            currentCoroutineContext().ensureActive()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderDocId)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            class Child(val id: String, val name: String, val folder: Boolean, val modified: Long, val size: Long)
            val entries = ArrayList<Child>()
            try {
                context.contentResolver.query(children, columns, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        entries += Child(
                            c.getString(0), name, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                            if (c.isNull(3)) 0L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4),
                        )
                    }
                }
            } catch (e: SecurityException) {
                throw IOException("PocketLM no longer has permission to read that folder. Choose it again.", e)
            }
            for (child in entries.sortedBy { it.name.lowercase() }) {
                if (NotesRules.skipName(child.name, child.folder)) continue
                val path = prefix + child.name
                if (child.folder) {
                    if (depth < NotesRules.MAX_DEPTH) walk(child.id, "$path/", depth + 1)
                    continue
                }
                val kind = NotesRules.kind(child.name) ?: continue
                if (child.size > NotesRules.maxBytes(kind)) continue
                if (found.size >= NotesRules.MAX_FILES) {
                    tooMany = true
                    continue
                }
                found += FoundNote(
                    NoteStamp(path, child.size, child.modified),
                    DocumentsContract.buildDocumentUriUsingTree(tree, child.id), kind,
                )
            }
        }

        walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        return Result(found, tooMany)
    }

    companion object {
        private const val READ = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        private const val WRITE = android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        /**
         * Keeps access to the chosen folder after the app is closed or the phone restarts: reading, and also writing when
         * the storage app allows it (needed to save replies and chats into the folder).
         */
        fun persistPermission(context: Context, uri: Uri) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, READ or WRITE)
            } catch (e: SecurityException) {
                context.contentResolver.takePersistableUriPermission(uri, READ)
            }
        }

        fun releasePermission(context: Context, uri: Uri) {
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, READ or WRITE) }
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, READ) }
        }

        /** True if the app may add files to [uri]. A folder chosen before saving existed has to be chosen again. */
        fun canWrite(context: Context, uri: Uri): Boolean =
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
    }

    /** The plain text of a note, or null if it has none worth keeping. */
    suspend fun read(note: FoundNote): String? {
        val bytes = context.contentResolver.openInputStream(note.uri)?.use { it.readBytes() } ?: return null
        return when (note.kind) {
            NotesRules.Kind.Text -> String(bytes, Charsets.UTF_8)
            NotesRules.Kind.Html -> HtmlText.extract(String(bytes, Charsets.UTF_8)).text
            NotesRules.Kind.Pdf -> try {
                PdfText.extract(context, bytes.inputStream())
            } catch (e: ScannedPdfException) {
                null
            }
        }?.replace("\r\n", "\n")?.trim()?.ifEmpty { null }
    }
}
