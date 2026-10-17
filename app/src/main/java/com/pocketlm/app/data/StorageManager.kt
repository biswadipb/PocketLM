package com.pocketlm.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** How much space PocketLM uses, and which saved files no chat refers to any more. */
data class StorageReport(
    val modelsBytes: Long,
    val chatsBytes: Long,
    val imagesBytes: Long,
    val documentsBytes: Long,
    val cacheBytes: Long,
    val unusedBytes: Long,
    val unusedCount: Int,
) {
    val totalBytes get() = modelsBytes + chatsBytes + imagesBytes + documentsBytes + cacheBytes
}

class StorageManager(private val context: Context) {

    private fun dir(name: String) = File(context.filesDir, name)

    suspend fun report(referencedImages: Set<String>, referencedDocIds: Set<String>): StorageReport =
        withContext(Dispatchers.IO) {
            val unused = unusedFiles(referencedImages, referencedDocIds)
            StorageReport(
                modelsBytes = size(dir("models")),
                chatsBytes = size(dir("chats")),
                imagesBytes = size(dir("images")),
                documentsBytes = size(dir("docs")),
                cacheBytes = size(context.cacheDir),
                unusedBytes = unused.sumOf { it.length() },
                unusedCount = unused.size,
            )
        }

    /** Deletes images and documents that no chat refers to. Returns how many files were removed. */
    suspend fun deleteUnused(referencedImages: Set<String>, referencedDocIds: Set<String>): Int =
        withContext(Dispatchers.IO) {
            unusedFiles(referencedImages, referencedDocIds).count { it.delete() }
        }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun unusedFiles(referencedImages: Set<String>, referencedDocIds: Set<String>): List<File> {
        val images = dir("images").listFiles().orEmpty().toList()
        val docs = dir("docs").listFiles().orEmpty().toList()
        // A document may have two files: its text and its saved search vectors.
        val unusedDocs = unusedNames(docs.map { it.nameWithoutExtension }, referencedDocIds).toSet()
        return unusedNames(images.map { it.absolutePath }, referencedImages).mapNotNull { path -> images.find { it.absolutePath == path } } +
            docs.filter { it.nameWithoutExtension in unusedDocs }
    }

    private fun size(file: File): Long =
        if (file.isFile) file.length() else file.listFiles().orEmpty().sumOf { size(it) }

    companion object {
        /** Entries of [all] that are not in [referenced]. */
        fun unusedNames(all: List<String>, referenced: Set<String>): List<String> =
            all.filterNot { it in referenced }
    }
}
