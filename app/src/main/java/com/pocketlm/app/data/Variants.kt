package com.pocketlm.app.data

/** One version of a reply. */
data class Reply(val text: String, val stats: GenStats? = null, val sources: List<SourceRef> = emptyList())

/** The versions of one reply other than the one on screen, and where the one on screen sits among all of them. */
data class OtherReplies(val others: List<Reply> = emptyList(), val position: Int = 0)

/** Regenerating keeps the earlier answers so you can page back to them. */
object Variants {
    const val MAX_VERSIONS = 8

    fun count(others: List<Reply>) = others.size + 1

    /** Every version in order, with [current] at [position]. */
    fun all(current: Reply, others: List<Reply>, position: Int): List<Reply> =
        others.toMutableList().also { it.add(position.coerceIn(0, it.size), current) }

    /** Moves to version [target] (0-based); the version on screen takes its place in the list. */
    fun select(current: Reply, versions: OtherReplies, target: Int): Pair<Reply, OtherReplies> {
        val full = all(current, versions.others, versions.position)
        val index = target.coerceIn(0, full.lastIndex)
        return full[index] to OtherReplies(full.filterIndexed { i, _ -> i != index }, index)
    }

    /** Called when a reply is regenerated: the one on screen becomes an earlier version and the new one goes last. */
    fun startNew(current: Reply, versions: OtherReplies): OtherReplies {
        val kept = all(current, versions.others, versions.position).takeLast(MAX_VERSIONS - 1)
        return OtherReplies(kept, kept.size)
    }

    /** Called when the new reply never appeared: the newest earlier version comes back. Null if there is none. */
    fun restoreLast(versions: OtherReplies): Pair<Reply, OtherReplies>? {
        val last = versions.others.lastOrNull() ?: return null
        val rest = versions.others.dropLast(1)
        return last to OtherReplies(rest, rest.size)
    }
}
