package com.pocketlm.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException

/**
 * A folder the user picked with Android's folder picker. Any app that offers its files there works: Google Drive,
 * Dropbox, OneDrive, Nextcloud, Syncthing, or a plain folder on the phone. The app keeps its permission to the
 * folder until the user disconnects (see [persistPermission]).
 *
 * The folder is read once per [FolderBackend] and the list is kept up to date as files are written, because asking
 * a storage app about each file in turn is slow.
 */
class FolderBackend(private val context: Context, private val treeUri: Uri) : SyncBackend {

    private class Entry(val uri: Uri, var size: Long)

    private val resolver get() = context.contentResolver

    override val label: String
        get() = "Folder (${DocumentFile.fromTreeUri(context, treeUri)?.name ?: "chosen folder"})"

    private val root: DocumentFile
        get() = DocumentFile.fromTreeUri(context, treeUri)?.takeIf { it.exists() && it.isDirectory }
            ?: throw SyncException("The sync folder is no longer available. Choose it again in Settings.")

    private val index: MutableMap<String, Entry> by lazy { load() }

    private fun load(): MutableMap<String, Entry> {
        root // fails early with a clear message if the folder was removed
        val found = HashMap<String, Entry>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        try {
            resolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    found[name] = Entry(DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)), if (c.isNull(2)) 0L else c.getLong(2))
                }
            }
        } catch (e: SecurityException) {
            throw SyncException("PocketLM no longer has permission to use the sync folder. Choose it again in Settings.", e)
        }
        return found
    }

    override fun list(): List<RemoteFile> = index.map { RemoteFile(it.key, it.value.size) }.filter { SyncNames.valid(it.name) }

    override fun get(name: String): ByteArray? {
        require(SyncNames.valid(name)) { "Unexpected file name" }
        val entry = index[name] ?: return null
        return try {
            resolver.openInputStream(entry.uri)?.use { it.readBytes() } ?: throw SyncException("Could not read $name from the sync folder.")
        } catch (e: FileNotFoundException) {
            index.remove(name)
            null
        }
    }

    override fun put(name: String, data: ByteArray) {
        require(SyncNames.valid(name)) { "Unexpected file name" }
        val existing = index[name]
        val uri = existing?.uri ?: create(name)
        try {
            // "wt" replaces the old content; plain "w" may leave the tail of a longer file behind.
            (resolver.openOutputStream(uri, "wt") ?: throw SyncException("Could not write $name to the sync folder.")).use { it.write(data) }
        } catch (e: FileNotFoundException) {
            throw SyncException("Could not write $name to the sync folder: ${e.message}", e)
        }
        index[name] = Entry(uri, data.size.toLong())
    }

    private fun create(name: String): Uri {
        val mime = if (name.endsWith(".json")) "application/json" else "application/octet-stream"
        val file = root.createFile(mime, name) ?: throw SyncException("Could not create $name in the sync folder.")
        val actual = file.name
        if (actual != name) {
            // Some storage apps add or change a file extension. A renamed file could never be found again.
            file.delete()
            throw SyncException("The storage app renamed $name to $actual. Choose a folder in a different storage app.")
        }
        return file.uri
    }

    override fun delete(name: String) {
        require(SyncNames.valid(name)) { "Unexpected file name" }
        val entry = index.remove(name) ?: return
        DocumentFile.fromSingleUri(context, entry.uri)?.delete()
    }

    companion object {
        /** Keeps access to the chosen folder after the app is closed or the phone restarts. */
        fun persistPermission(context: Context, uri: Uri) {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }

        fun releasePermission(context: Context, uri: Uri) {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
        }

        /** The folder's display name, for showing which one is chosen. */
        fun displayName(context: Context, uri: Uri): String =
            runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull() ?: "chosen folder"
    }
}
