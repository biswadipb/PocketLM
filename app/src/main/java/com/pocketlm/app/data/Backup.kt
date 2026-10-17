package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What a restore did. [settings] is the saved settings file's content, for the caller to apply. */
data class RestoreResult(val chatsRestored: Int, val chatsKept: Int, val files: Int, val settings: JSONObject?)

/**
 * Packs chats (with their photos and attached documents) and settings into one zip file, and reads
 * such a file back. Downloaded models are not included; they are too large and can be downloaded again.
 * A backup is untrusted input when restoring, so every entry name and size is checked.
 */
class BackupManager(private val chatsDir: File, private val imagesDir: File, private val docsDir: File) {

    fun export(out: OutputStream, settings: JSONObject, createdAt: Long): Int {
        var chatCount = 0
        ZipOutputStream(out).use { zip ->
            zip.put("backup.json", JSONObject().put("format", FORMAT).put("app", "PocketLM").put("createdAt", createdAt).toString().toByteArray())
            zip.put("settings.json", settings.toString().toByteArray())

            val chatFiles = chatsDir.listFiles { f -> f.isFile && CHAT_NAME.matches(f.name) }.orEmpty().sortedBy { it.name }
            val images = LinkedHashSet<String>()
            val docs = LinkedHashSet<String>()
            for (file in chatFiles) {
                val text = file.readText()
                val chat = runCatching { JSONObject(text) }.getOrNull() ?: continue
                zip.put("chats/${file.name}", text.toByteArray())
                chatCount++
                chat.optJSONArray("messages")?.let { msgs ->
                    for (i in 0 until msgs.length()) {
                        msgs.optJSONObject(i)?.let { m -> MessageImages.paths(m).forEach { images += File(it).name } }
                    }
                }
                chat.optJSONArray("attachments")?.let { atts ->
                    for (i in 0 until atts.length()) atts.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }
                        ?.let { docs += "$it.txt" }
                }
            }
            for (name in images) File(imagesDir, name).takeIf { it.isFile && IMAGE_NAME.matches(name) }?.let { zip.put("images/$name", it.readBytes()) }
            for (name in docs) File(docsDir, name).takeIf { it.isFile && DOC_NAME.matches(name) }?.let { zip.put("docs/$name", it.readBytes()) }
        }
        return chatCount
    }

    fun restore(input: InputStream): RestoreResult {
        var restored = 0
        var kept = 0
        var files = 0
        var settings: JSONObject? = null
        var total = 0L
        var entries = 0
        var sawHeader = false

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (++entries > MAX_ENTRIES) throw IOException("That backup has too many files.")
                val name = entry.name

                if (!sawHeader) {
                    if (name != "backup.json") throw IOException("That is not a PocketLM backup.")
                    val header = runCatching { JSONObject(String(zip.readLimited(SMALL_LIMIT))) }.getOrNull()
                        ?: throw IOException("That is not a PocketLM backup.")
                    if (header.optInt("format") != FORMAT) throw IOException("That backup was made by a newer version of PocketLM.")
                    sawHeader = true
                    continue
                }

                when {
                    name == "settings.json" -> settings = runCatching { JSONObject(String(zip.readLimited(SMALL_LIMIT))) }.getOrNull()
                    name.startsWith("chats/") -> {
                        val file = safeName(name, "chats/", CHAT_NAME) ?: continue
                        val bytes = zip.readLimited(CHAT_LIMIT).also { total += it.size }
                        val incoming = runCatching { JSONObject(String(bytes)) }.getOrNull() ?: continue
                        val target = File(chatsDir, file)
                        val existing = if (target.isFile) runCatching { JSONObject(target.readText()).optLong("updatedAt") }.getOrDefault(0L) else -1L
                        if (existing >= incoming.optLong("updatedAt") && existing >= 0) {
                            kept++
                        } else {
                            chatsDir.mkdirs()
                            target.writeText(withLocalImagePaths(incoming).toString())
                            restored++
                        }
                    }
                    name.startsWith("images/") -> {
                        val file = safeName(name, "images/", IMAGE_NAME) ?: continue
                        imagesDir.mkdirs()
                        File(imagesDir, file).writeBytes(zip.readLimited(IMAGE_LIMIT).also { total += it.size })
                        files++
                    }
                    name.startsWith("docs/") -> {
                        val file = safeName(name, "docs/", DOC_NAME) ?: continue
                        docsDir.mkdirs()
                        File(docsDir, file).writeBytes(zip.readLimited(DOC_LIMIT).also { total += it.size })
                        files++
                    }
                }
                if (total > MAX_TOTAL) throw IOException("That backup is larger than expected.")
            }
        }
        if (!sawHeader) throw IOException("That is not a PocketLM backup.")
        return RestoreResult(restored, kept, files, settings)
    }

    /** Photo paths in a chat point into this phone's own storage, which may differ from where the backup was made. */
    private fun withLocalImagePaths(chat: JSONObject): JSONObject {
        chat.optJSONArray("messages")?.let { msgs ->
            for (i in 0 until msgs.length()) {
                val m = msgs.optJSONObject(i) ?: continue
                MessageImages.rewrite(m) { File(imagesDir, File(it).name).absolutePath }
            }
        }
        return chat
    }

    /** The file name part of a zip entry, or null if it is not a plain, expected name (blocks `../` tricks). */
    private fun safeName(entryName: String, prefix: String, allowed: Regex): String? {
        val rest = entryName.removePrefix(prefix)
        return rest.takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it && ".." !in it && allowed.matches(it) }
    }

    private fun ZipOutputStream.put(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    /** Reads the current entry, refusing to read more than [limit] bytes (guards against zip bombs). */
    private fun ZipInputStream.readLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            if (out.size() + n > limit) throw IOException("A file in that backup is too large.")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val FORMAT = 1
        private val CHAT_NAME = Regex("""[A-Za-z0-9-]+\.json""")
        private val IMAGE_NAME = Regex("""[A-Za-z0-9._-]+\.(jpg|jpeg|png)""")
        private val DOC_NAME = Regex("""[A-Za-z0-9-]+\.txt""")
        private const val SMALL_LIMIT = 1_000_000
        private const val CHAT_LIMIT = 8_000_000
        private const val IMAGE_LIMIT = 30_000_000
        private const val DOC_LIMIT = 8_000_000
        private const val MAX_TOTAL = 800_000_000L
        private const val MAX_ENTRIES = 50_000
    }
}

/** Saves the chosen settings as typed JSON and applies them back. Only [allowed] keys are ever read or written. */
object SettingsBackup {

    fun toJson(all: Map<String, *>, allowed: Set<String>): JSONObject {
        val json = JSONObject()
        for ((key, value) in all) {
            if (key !in allowed) continue
            val entry = when (value) {
                is String -> JSONObject().put("t", "s").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                else -> continue
            }
            json.put(key, entry)
        }
        return json
    }

    /** Calls [put] for each recognised, allowed setting; returns how many were applied. */
    fun apply(json: JSONObject, allowed: Set<String>, put: (key: String, value: Any) -> Unit): Int {
        var applied = 0
        for (key in json.keys()) {
            if (key !in allowed) continue
            val entry = json.optJSONObject(key) ?: continue
            val value: Any? = when (entry.optString("t")) {
                "s" -> entry.optString("v")
                "i" -> if (entry.has("v")) entry.optInt("v") else null
                "f" -> if (entry.has("v")) entry.optDouble("v").toFloat() else null
                "b" -> if (entry.has("v")) entry.optBoolean("v") else null
                else -> null
            }
            if (value != null) {
                put(key, value)
                applied++
            }
        }
        return applied
    }
}
