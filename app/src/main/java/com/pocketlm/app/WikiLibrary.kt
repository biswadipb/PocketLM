package com.pocketlm.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.pocketlm.app.data.ChannelZimSource
import com.pocketlm.app.data.OfflineWikipedia
import com.pocketlm.app.data.ZimException
import com.pocketlm.app.data.ZimFile
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The Wikipedia file (a Kiwix ZIM file) the person chose, kept open between questions. It is either a file picked from
 * the phone's storage, read where it is without copying it, or one downloaded into the app.
 */
class WikiLibrary private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private class Open(val key: String, val wiki: OfflineWikipedia, val zim: ZimFile, val close: () -> Unit)

    @Volatile
    private var open: Open? = null

    /** What Settings shows. */
    data class Status(val name: String?, val entries: Int = 0, val problem: String? = null) {
        val configured: Boolean get() = name != null
    }

    private fun modelsFile(name: String) = File(File(context.filesDir, "models"), File(name).name)

    val configured: Boolean get() = prefs.getString(KEY_URI, null) != null || prefs.getString(KEY_FILE, null) != null

    fun displayName(): String? = prefs.getString(KEY_NAME, null)

    /** Reads the file once to check that it is a usable ZIM file and report what is in it. */
    fun status(): Status {
        val name = displayName() ?: return Status(null)
        return try {
            Status(name, entries = opened().zim.entryCount)
        } catch (e: IOException) {
            Status(name, problem = e.message ?: "The file could not be read.")
        }
    }

    /** Uses the file at [uri], a ZIM file picked from storage. Throws [IOException] saying why if it is not usable. */
    fun chooseUri(uri: Uri, name: String) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val previous = prefs.getString(KEY_URI, null)
        val check = openUri(uri)
        check.close()
        forgetCurrent(deleteFile = true)
        prefs.edit().putString(KEY_URI, uri.toString()).remove(KEY_FILE).putString(KEY_NAME, name).apply()
        if (previous != null && previous != uri.toString()) {
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    /** Uses the file [fileName] that was downloaded into the app. */
    fun chooseDownloaded(fileName: String) {
        val file = modelsFile(fileName)
        openFile(file).close()
        forgetCurrent(deleteFile = true)
        prefs.edit().putString(KEY_FILE, file.name).remove(KEY_URI).putString(KEY_NAME, file.name.removeSuffix(".zim")).apply()
    }

    /** Stops using the file; a downloaded one is deleted, a picked one is left where it is. */
    fun forget() = forgetCurrent(deleteFile = true).also {
        prefs.edit().remove(KEY_URI).remove(KEY_FILE).remove(KEY_NAME).apply()
    }

    private fun forgetCurrent(deleteFile: Boolean) {
        open?.close?.invoke()
        open = null
        val file = prefs.getString(KEY_FILE, null)
        if (deleteFile && file != null) modelsFile(file).delete()
        prefs.getString(KEY_URI, null)?.let { uri ->
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    /** The lookup the Wikipedia tool uses, or null when no file is chosen. */
    fun lookup(): ((String) -> OfflineWikipedia.Article?)? =
        if (!configured) null else { query -> opened().wiki.lookup(query) }

    @Synchronized
    private fun opened(): Open {
        val uri = prefs.getString(KEY_URI, null)
        val file = prefs.getString(KEY_FILE, null)
        val key = uri ?: file ?: throw IOException("No Wikipedia file is chosen.")
        open?.takeIf { it.key == key }?.let { return it }
        open?.close?.invoke()
        val made = if (uri != null) openUri(Uri.parse(uri)) else openFile(modelsFile(file!!))
        open = made
        return made
    }

    private fun openUri(uri: Uri): Open {
        val descriptor: ParcelFileDescriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        } ?: throw IOException("PocketLM can no longer open that file. Choose it again.")
        try {
            val channel = FileInputStream(descriptor.fileDescriptor).channel
            // A file that cannot be read in pieces (some cloud storage) would be read from the start every time.
            runCatching { channel.position(channel.position()) }.onFailure { throw IOException("That location cannot be read in pieces. Copy the file to this phone first.") }
            val zim = ZimFile.open(ChannelZimSource(channel))
            return Open(uri.toString(), OfflineWikipedia(zim), zim) { runCatching { descriptor.close() } }
        } catch (e: Exception) {
            runCatching { descriptor.close() }
            throw if (e is IOException) e else IOException("That file could not be read: ${e.message}")
        }
    }

    private fun openFile(file: File): Open {
        if (!file.isFile) throw IOException("The Wikipedia file is missing. Download it again.")
        val raf = RandomAccessFile(file, "r")
        try {
            val zim = ZimFile.open(ChannelZimSource(raf.channel))
            return Open(file.name, OfflineWikipedia(zim), zim) { runCatching { raf.close() } }
        } catch (e: ZimException) {
            runCatching { raf.close() }
            throw e
        }
    }

    companion object {
        private const val KEY_URI = "wiki_zim_uri"
        private const val KEY_FILE = "wiki_zim_file"
        private const val KEY_NAME = "wiki_zim_name"

        @Volatile
        private var instance: WikiLibrary? = null

        fun get(context: Context): WikiLibrary =
            instance ?: synchronized(this) { instance ?: WikiLibrary(context.applicationContext).also { instance = it } }
    }
}
