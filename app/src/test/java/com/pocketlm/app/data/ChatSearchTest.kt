package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSearchTest {

    private val texts = listOf("Hello there", "Tell me about Cats", "I like dogs and cats", "Goodbye")

    @Test
    fun matchesAreFoundIgnoringCapitals() {
        assertEquals(listOf(1, 2), ChatSearch.find(texts, "cats"))
        assertEquals(listOf(0), ChatSearch.find(texts, "HEL"))
        assertEquals(listOf(0), ChatSearch.find(texts, "hello"))
    }

    @Test
    fun aBlankQueryFindsNothing() {
        assertTrue(ChatSearch.find(texts, "").isEmpty())
        assertTrue(ChatSearch.find(texts, "   ").isEmpty())
        assertTrue(ChatSearch.find(texts, "zebra").isEmpty())
        assertTrue(ChatSearch.find(emptyList(), "a").isEmpty())
    }

    @Test
    fun surroundingSpacesInTheQueryAreIgnored() {
        assertEquals(listOf(1, 2), ChatSearch.find(texts, "  cats "))
    }

    @Test
    fun steppingWrapsRoundAtBothEnds() {
        assertEquals(1, ChatSearch.step(0, 3, forward = true))
        assertEquals(0, ChatSearch.step(2, 3, forward = true))
        assertEquals(2, ChatSearch.step(0, 3, forward = false))
        assertEquals(0, ChatSearch.step(5, 0, forward = true))
        assertEquals(0, ChatSearch.step(0, 1, forward = true))
        assertEquals(0, ChatSearch.step(99, 3, forward = true))   // a stale position is pulled back into range first
    }

    private fun chat(id: String, at: Long, vararg messages: StoredMessage) = SavedChat(id, "Chat $id", at, null, messages.toList())

    @Test
    fun starredMessagesAreCollectedNewestChatFirstInReadingOrder() {
        val chats = listOf(
            chat("old", 10, StoredMessage("plain", true), StoredMessage("keep this", false, starred = true)),
            chat("new", 99, StoredMessage("first star", true, starred = true), StoredMessage("x", false), StoredMessage("second star", false, starred = true)),
            chat("none", 50, StoredMessage("nothing", true)),
        )
        val items = Starred.collect(chats)
        assertEquals(listOf("new" to 0, "new" to 2, "old" to 1), items.map { it.chatId to it.index })
        assertEquals("keep this", items.last().snippet)
        assertFalse(items.last().isUser)
        assertEquals("Chat old", items.last().chatTitle)
    }

    @Test
    fun aSnippetIsOneShortLineWithoutThinking() {
        val long = "word ".repeat(100)
        val snippet = Starred.snippet(long)
        assertTrue(snippet.length <= 161 && snippet.endsWith("…"))
        assertEquals("The answer is 4.", Starred.snippet("<think>\nlet me work it out\n</think>\n\nThe answer is\n4."))
        assertEquals("only thinking", Starred.snippet("<think>only thinking</think>"))
    }
}
