package com.pocketlm.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ChatCodecExtrasTest {

    private fun roundTrip(chat: SavedChat) = ChatCodec.decode(ChatCodec.encode(chat).toString())

    @Test
    fun aGrammarIsSavedWithTheChat() {
        val chat = SavedChat("a", "T", 1, null, emptyList(), grammar = """root ::= "yes" | "no"""")
        assertEquals(chat.grammar, roundTrip(chat).grammar)
        assertFalse(ChatCodec.encode(chat.copy(grammar = null)).has("grammar"))
        assertNull(roundTrip(chat.copy(grammar = null)).grammar)
    }

    @Test
    fun anOldChatWithoutAGrammarOrSourcesStillOpens() {
        val old = JSONObject()
            .put("id", "x").put("title", "Old").put("updatedAt", 5).put("modelFile", JSONObject.NULL)
            .put("messages", org.json.JSONArray().put(JSONObject().put("text", "hi").put("isUser", true)))
        val chat = ChatCodec.decode(old.toString())
        assertNull(chat.grammar)
        assertEquals(emptyList<SourceRef>(), chat.messages.single().sources)
    }

    @Test
    fun theSourcesOfAReplyAndOfItsEarlierVersionsAreSaved() {
        val one = listOf(SourceRef("notes/a.md", true, "Alpha"))
        val two = listOf(SourceRef("b.pdf", false, "Beta"), SourceRef("c.txt", false, "Gamma"))
        val chat = SavedChat(
            "a", "T", 1, null,
            listOf(
                StoredMessage("q", true),
                StoredMessage("answer", false, sources = one, others = listOf(Reply("older", null, two)), position = 1),
            ),
        )
        val back = roundTrip(chat).messages[1]
        assertEquals(one, back.sources)
        assertEquals(two, back.others.single().sources)
        assertEquals(1, back.position)
    }

    @Test
    fun aMessageWithoutSourcesWritesNothingExtra() {
        val json = ChatCodec.encode(SavedChat("a", "T", 1, null, listOf(StoredMessage("q", true)))).toString()
        assertFalse(json.contains("sources"))
    }
}
