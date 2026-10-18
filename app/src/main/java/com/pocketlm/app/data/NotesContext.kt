package com.pocketlm.app.data

/**
 * What a prompt that runs by itself is given from the notes folder: the notes that changed lately, or the passages that
 * match the prompt. Pure, so the choices can be tested.
 */
object NotesContext {
    /** How much of one note is read for a summary of what changed. */
    const val PER_NOTE_CHARS = 2_500

    class Built(val prompt: String, val sources: List<SourceRef>, val notes: Int)

    /**
     * [task] preceded by the notes that changed after [since] (newest first, each cut short, the whole kept within
     * [budgetChars]). Null if no note changed. Notes whose time is unknown (0) are not counted as changed.
     */
    fun recent(
        manifest: Map<String, NoteStamp>,
        read: (String) -> String?,
        since: Long,
        sinceLabel: String,
        budgetChars: Int,
        task: String,
    ): Built? {
        val changed = manifest.values.filter { it.modified > since }.sortedByDescending { it.modified }
        val parts = ArrayList<Pair<String, String>>()
        var used = 0
        for (stamp in changed) {
            val text = read(stamp.path)?.trim().orEmpty()
            if (text.isEmpty()) continue
            val clipped = text.take(PER_NOTE_CHARS)
            if (used + clipped.length > budgetChars && parts.isNotEmpty()) break
            parts += stamp.path to clipped.take((budgetChars - used).coerceAtLeast(200))
            used += clipped.length
        }
        if (parts.isEmpty()) return null
        val body = parts.joinToString("\n\n") { (path, text) -> "[notes: $path]\n$text" }
        val prompt = "These are the notes that changed since $sinceLabel (${parts.size} " +
            (if (parts.size == 1) "note" else "notes") + "):\n\n$body\n\n$task"
        val sources = parts.take(Sources.MAX_PER_REPLY).map { (path, text) -> SourceRef(path, true, text.take(Sources.MAX_EXCERPT_CHARS)) }
        return Built(prompt, sources, parts.size)
    }

    /** [task] with the passages of the notes that match it (the start of each note when none do), or null if there are no notes. */
    fun search(index: Bm25Index?, task: String, budgetChars: Int): Built? {
        val chunks = index?.search(task, budgetChars).orEmpty()
        if (chunks.isEmpty()) return null
        return Built(NotesRules.excerptsPrompt(task, emptyList(), chunks), Sources.of(emptyList(), chunks), chunks.map { it.docName }.distinct().size)
    }
}
