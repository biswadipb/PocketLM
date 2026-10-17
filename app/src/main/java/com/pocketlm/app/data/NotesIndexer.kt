package com.pocketlm.app.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Brings PocketLM's saved copy of the notes folder up to date: files that are new or changed are read again, files that
 * went are forgotten, the rest is left alone. Used by the chat (with progress on screen) and by prompts that run by
 * themselves.
 */
class NotesIndexer(private val scanner: NotesScanner, private val knowledge: KnowledgeStore) {

    /** What was left out: [truncated] when the folder holds more text than is read, [tooMany] when it has too many files. */
    class Result(val truncated: Boolean, val tooMany: Boolean)

    suspend fun refresh(tree: Uri, progress: (String) -> Unit = {}): Result {
        val scan = withContext(Dispatchers.IO) { scanner.scan(tree) }
        val known = withContext(Dispatchers.IO) { knowledge.manifest() }
        val plan = NotesRules.plan(known, scan.files.map { it.stamp })
        val byPath = scan.files.associateBy { it.stamp.path }
        val manifest = LinkedHashMap(known)
        withContext(Dispatchers.IO) { plan.drop.forEach { knowledge.delete(it); manifest.remove(it) } }

        var used = plan.keep.sumOf { known[it]?.size ?: 0L }
        var truncated = false
        plan.read.forEachIndexed { done, stamp ->
            currentCoroutineContext().ensureActive()
            progress("Reading notes… ${done + 1} of ${plan.read.size}")
            val text = withContext(Dispatchers.IO) { runCatching { scanner.read(byPath.getValue(stamp.path)) }.getOrNull() }
            // A file that cannot be read is kept as empty, so it is not tried again until it changes.
            val keep = text != null && used + text.length <= NotesRules.MAX_TOTAL_CHARS
            if (text != null && !keep) truncated = true
            if (keep) used += text!!.length
            withContext(Dispatchers.IO) { knowledge.writeText(stamp.path, if (keep) text!! else "") }
            manifest[stamp.path] = stamp
        }
        withContext(Dispatchers.IO) { knowledge.saveManifest(manifest.values) }
        return Result(truncated, scan.tooMany)
    }
}
