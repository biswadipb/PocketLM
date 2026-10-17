package com.pocketlm.app.data

/**
 * Branching a chat: copying it up to one message into a new chat, so a different direction can be tried without losing
 * the original. Pure, so the rules can be tested; copying the photos and files is done by the caller.
 */
object ChatBranch {

    /** What goes into the new chat: the messages kept, and text to put in the message box (see [plan]). */
    data class Plan(val messages: List<StoredMessage>, val draft: String?)

    /**
     * The branch point is [index]. On a reply the new chat keeps everything up to and including that reply. On one of
     * the person's own messages it keeps what came before and offers that message again in the message box, to be
     * reworded. Null when there is nothing to branch from (the first message, or an index outside the chat).
     */
    fun plan(messages: List<StoredMessage>, index: Int): Plan? {
        val at = messages.getOrNull(index) ?: return null
        return if (at.isUser) {
            val kept = messages.take(index)
            if (kept.isEmpty()) null else Plan(kept.map(::detach), at.text)
        } else {
            Plan(messages.take(index + 1).map(::detach), null)
        }
    }

    /** A copy for the new chat: not starred (the stars of the original stay with it). */
    private fun detach(m: StoredMessage) = m.copy(starred = false)

    /** The same messages with every photo path replaced by [copy]'s answer; a photo it cannot copy is left out. */
    fun withPhotos(messages: List<StoredMessage>, copy: (String) -> String?): List<StoredMessage> = messages.map { m ->
        if (m.images.isEmpty()) {
            m
        } else {
            val kept = m.images.mapNotNull(copy)
            m.copy(imagePath = kept.firstOrNull(), moreImages = kept.drop(1))
        }
    }

    /** The paths of every photo in [messages], for copying. */
    fun photoPaths(messages: List<StoredMessage>): List<String> = messages.flatMap { it.images }.distinct()

    private const val PREFIX = "Branch: "

    /** A title for the new chat, kept to the length titles are allowed. */
    fun title(original: String): String {
        val base = original.trim().removePrefix(PREFIX).ifBlank { "Chat" }
        return (PREFIX + base).take(80)
    }

    /** Whether branching from [index] is offered: there must be something to branch from. */
    fun canBranch(messages: List<StoredMessage>, index: Int): Boolean = plan(messages, index) != null
}
