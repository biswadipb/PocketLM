package com.pocketlm.app.data

import java.io.File

/**
 * A short list of the files an incognito chat made (attached documents and photos), kept on disk only so that they
 * can still be found and deleted if the app is killed before the chat is closed. It holds names, never any text.
 */
class IncognitoLedger(private val file: File) {

    @Synchronized
    fun add(entry: String) {
        if (!valid(entry)) return
        file.parentFile?.mkdirs()
        file.appendText(entry + "\n")
    }

    @Synchronized
    fun entries(): List<String> = runCatching { file.readLines() }.getOrDefault(emptyList()).filter(::valid).distinct()

    @Synchronized
    fun clear() {
        file.delete()
    }

    /** Deletes everything listed from [docsDir] and [imagesDir], forgets the list, and returns how many files went. */
    @Synchronized
    fun purge(docsDir: File, imagesDir: File): Int {
        var removed = 0
        for (entry in entries()) {
            val kind = entry.substringBefore(':')
            val name = entry.substringAfter(':')
            when (kind) {
                "doc" -> {
                    if (File(docsDir, "$name.txt").delete()) removed++
                    if (File(docsDir, "$name.emb").delete()) removed++
                }
                "img" -> if (File(imagesDir, name).delete()) removed++
            }
        }
        file.delete()
        return removed
    }

    private fun valid(entry: String): Boolean {
        val kind = entry.substringBefore(':', "")
        val name = entry.substringAfter(':', "")
        return (kind == "doc" && SyncNames.validId(name)) || (kind == "img" && SyncNames.validImage(name))
    }

    companion object {
        fun doc(id: String) = "doc:$id"
        fun image(path: String) = "img:" + File(path).name
    }
}
