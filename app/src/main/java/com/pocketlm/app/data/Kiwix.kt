package com.pocketlm.app.data

import java.net.HttpURLConnection
import java.net.URL

/** Kiwix publishes Wikipedia for offline reading as ZIM files at download.kiwix.org. */
object Kiwix {
    const val FOLDER = "https://download.kiwix.org/zim/wikipedia/"

    /** English Wikipedia, the most read articles, with the start of each: the smallest file that is useful (about 285 MB). */
    const val TOP_PREFIX = "wikipedia_en_top_mini_"

    /** The file to fall back on when the folder cannot be read. */
    const val FALLBACK_FILE = "wikipedia_en_top_mini_2026-09.zim"

    const val APPROX_MB = 285

    private val dated = Regex("""href="(wikipedia_[a-z0-9_]+?_(\d{4}-\d{2})\.zim)"""")

    /** The newest file called `<prefix><year>-<month>.zim` in the folder's web page [html], or null. */
    fun latest(html: String, prefix: String = TOP_PREFIX): String? =
        dated.findAll(html).map { it.groupValues[1] to it.groupValues[2] }
            .filter { (name, date) -> name == "$prefix$date.zim" }
            .maxByOrNull { it.second }?.first

    /** The file name and address of the newest file with [prefix], asking the folder's page; falls back to a known file. */
    fun resolve(prefix: String = TOP_PREFIX): Pair<String, String> {
        val name = try {
            val connection = URL(FOLDER).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", "PocketLM")
                val html = connection.inputStream.bufferedReader().use { it.readText().take(4_000_000) }
                latest(html, prefix)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        } ?: FALLBACK_FILE
        return name to FOLDER + name
    }

    /** The SHA-256 Kiwix lists for the file at [url] (it keeps it in `<file>.sha256`), or null. */
    fun expectedSha256(url: String): String? {
        if (!url.startsWith(FOLDER) || !url.endsWith(".zim")) return null
        return try {
            val connection = URL("$url.sha256").openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("User-Agent", "PocketLM")
                if (connection.responseCode != 200) null else parseChecksum(connection.inputStream.bufferedReader().use { it.readText().take(2_000) })
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** The checksum at the start of a line such as `9e75…05  file.zim`, in lower case, or null. */
    fun parseChecksum(text: String): String? =
        text.trim().takeWhile { !it.isWhitespace() }.lowercase().takeIf { Regex("[0-9a-f]{64}").matches(it) }
}
