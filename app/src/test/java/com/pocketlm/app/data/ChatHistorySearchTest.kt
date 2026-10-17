package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ChatHistorySearchTest {

    private fun chat(id: String, title: String, at: Long, vararg messages: Pair<Boolean, String>) = SavedChat(
        id, title, at, null, messages.map { (user, text) -> StoredMessage(text, user) },
    )

    private val chats = listOf(
        chat("a", "Trip planning", 3000, true to "I want to visit Lisbon in March", false to "Lisbon is lovely in March, with mild weather."),
        chat("b", "Cooking", 2000, true to "Give me a vegan lasagne recipe", false to "Layer the pasta with lentil ragù and cashew béchamel."),
        chat("c", "Old trip", 1000, true to "Which hotel in Lisbon was cheap?", false to "<think>Looking for hotels</think>The Alfama guesthouse was cheap."),
    )

    @Test
    fun dropsFillerWordsFromTheQuery() {
        assertEquals(listOf("lisbon", "hotel"), ChatHistorySearch.terms("what did we say about the Lisbon hotel?"))
        assertEquals(listOf("what", "did"), ChatHistorySearch.terms("what did"))
        assertEquals(emptyList<String>(), ChatHistorySearch.terms(" ? "))
    }

    @Test
    fun findsMessagesWithTheWords() {
        val hits = ChatHistorySearch.search(chats, "lisbon hotel")
        assertEquals("c", hits.first().chatId)
        assertEquals(setOf("a", "c"), hits.map { it.chatId }.toSet())
        assertTrue(hits.first().fromUser)
    }

    @Test
    fun aMessageNeedsAtLeastHalfOfTheWords() {
        // "vegan" alone matches one of two words, which is half, so the recipe is found; nothing has "kayak".
        assertEquals(listOf("b"), ChatHistorySearch.search(chats, "vegan kayak").map { it.chatId })
        assertEquals(emptyList<HistoryHit>(), ChatHistorySearch.search(chats, "kayak canoe"))
        assertEquals(emptyList<HistoryHit>(), ChatHistorySearch.search(chats, "   "))
    }

    @Test
    fun leavesOutTheChatBeingWritten() {
        val hits = ChatHistorySearch.search(chats, "lisbon", skipChatId = "a")
        assertEquals(listOf("c"), hits.map { it.chatId })
    }

    @Test
    fun theThinkingSectionIsNotSearched() {
        assertTrue(ChatHistorySearch.search(chats, "hotels").isEmpty())
        assertEquals("The Alfama guesthouse was cheap.", ChatHistorySearch.search(chats, "alfama").single().snippet)
    }

    @Test
    fun newerChatsComeFirstWhenScoresMatch() {
        val twins = listOf(
            chat("old", "Old", 1000, true to "remember the wifi password is on the fridge"),
            chat("new", "New", 5000, true to "remember the wifi password is on the fridge"),
        )
        assertEquals(listOf("new", "old"), ChatHistorySearch.search(twins, "wifi password").map { it.chatId })
    }

    @Test
    fun oneChatCannotFillTheWholeAnswer() {
        val many = chat("x", "Many", 1, *Array(6) { true to "kotlin question number $it" })
        val hits = ChatHistorySearch.search(listOf(many), "kotlin")
        assertEquals(ChatHistorySearch.MAX_PER_CHAT, hits.size)
    }

    @Test
    fun longMessagesAreCutAroundTheMatch() {
        val text = "intro ".repeat(60) + "the secret word is pineapple " + "outro ".repeat(60)
        val snippet = ChatHistorySearch.snippet(text, listOf("pineapple"))
        assertTrue(snippet.contains("pineapple"))
        assertTrue(snippet.startsWith("…") && snippet.endsWith("…"))
        assertTrue(snippet.length < 300)
        assertEquals("short", ChatHistorySearch.snippet("short", listOf("short")))
    }

    @Test
    fun writesTheHitsForTheModel() {
        val hits = ChatHistorySearch.search(chats, "lasagne")
        val text = ChatHistorySearch.format(hits, ZoneId.of("UTC"))
        assertEquals("1. In \"Cooking\" (1 Jan 1970), you wrote: Give me a vegan lasagne recipe", text)
        assertFalse(text.contains("\n"))
        val reply = ChatHistorySearch.format(ChatHistorySearch.search(chats, "cashew"), ZoneId.of("UTC"))
        assertTrue(reply.contains("Piko replied"))
    }
}
