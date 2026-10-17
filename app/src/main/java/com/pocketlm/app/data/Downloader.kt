package com.pocketlm.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Downloads one file, resuming from a `.part` file, and only finishes it once every byte has arrived. */
object Downloader {

    /** True when the bytes received match the size the server announced; an unknown size can't be checked. */
    fun isComplete(done: Long, total: Long): Boolean = total < 0 || done == total

    /** The full size from a `Content-Range` header such as `bytes *&#47;1000` or `bytes 0-9/1000`. */
    fun totalFromContentRange(header: String?): Long? =
        header?.substringAfterLast('/')?.trim()?.toLongOrNull()

    /**
     * Fetches [url] into [partial], then renames it to [target]. [onProgress] receives
     * (bytesDone, totalBytes); total is -1 when the server did not say.
     */
    suspend fun download(url: String, target: File, partial: File, onProgress: (Long, Long) -> Unit): Verified =
        withContext(Dispatchers.IO) {
            var offset = if (partial.exists()) partial.length() else 0L
            val expected = Verification.expectedFor(url)

            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
                conn.connect()

                when (conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> offset = 0L // server ignored Range; start over
                    HttpURLConnection.HTTP_PARTIAL -> Unit
                    416 -> {
                        // Our partial file is at or past the end. If it is exactly the whole file we are done.
                        if (totalFromContentRange(conn.getHeaderField("Content-Range")) == offset) {
                            val verified = checkAgainst(expected, partial)
                            if (!partial.renameTo(target)) throw IOException("Could not save ${target.name}")
                            return@withContext verified
                        }
                        partial.delete()
                        throw IOException("The partly downloaded file was out of date and was removed. Try again.")
                    }
                    else -> throw IOException("Download failed: HTTP ${conn.responseCode}")
                }
                val total = conn.contentLengthLong.let { if (it < 0) -1L else it + offset }

                var done = offset
                conn.inputStream.use { input ->
                    FileOutputStream(partial, offset > 0).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var lastReport = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 250) {
                                lastReport = now
                                onProgress(done, total)
                            }
                        }
                        onProgress(done, total)
                    }
                }
                if (!isComplete(done, total)) {
                    // Keep the .part file so the next attempt resumes from here.
                    throw IOException("The download stopped early ($done of $total bytes). Try again to resume.")
                }
            } finally {
                conn.disconnect()
            }
            val verified = checkAgainst(expected, partial)
            if (!partial.renameTo(target)) throw IOException("Could not save ${target.name}")
            verified
        }

    /** What became of the checksum check on a finished download. */
    enum class Verified { Yes, NotChecked }

    /** Compares [file] with the SHA-256 the site published. A file that does not match is deleted, so it can't be used by mistake. */
    private fun checkAgainst(expected: String?, file: File): Verified {
        if (expected == null) return Verified.NotChecked
        if (!Verification.matches(expected, Verification.sha256(file))) {
            file.delete()
            throw IOException("The downloaded file does not match the checksum the model site lists for it, so it was deleted. Try downloading it again.")
        }
        return Verified.Yes
    }
}
