package com.pocketlm.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTagsTest {

    private fun chat(id: String, vararg tags: String) =
        SavedChat(id, "Chat $id", 1, null, emptyList(), tags = tags.toList())

    @Test
    fun aTagIsTidiedUp() {
        assertEquals("work", ChatTags.normalize("  #work  "))
        assertEquals("big idea", ChatTags.normalize("big    idea"))
        assertEquals("a".repeat(ChatTags.MAX_LENGTH), ChatTags.normalize("a".repeat(100)))
        assertNull(ChatTags.normalize("   "))
        assertNull(ChatTags.normalize("#"))
        assertNull(ChatTags.normalize("# #"))
    }

    @Test
    fun repeatsAreDroppedIgnoringCapitalsAndTheFirstSpellingStays() {
        assertEquals(listOf("Work", "home"), ChatTags.clean(listOf("Work", "work", "#WORK", "home", "  ")))
    }

    @Test
    fun aChatGetsAtMostEightTags() {
        val many = (1..20).map { "tag$it" }
        assertEquals(ChatTags.MAX_PER_CHAT, ChatTags.clean(many).size)
        assertEquals(many.take(8), ChatTags.clean(many))
        assertEquals(many.take(8), ChatTags.add(many.take(8), "extra"))
    }

    @Test
    fun addingAndRemovingIgnoreCapitals() {
        assertEquals(listOf("work", "ideas"), ChatTags.add(listOf("work"), "Ideas".lowercase()))
        assertEquals(listOf("work"), ChatTags.add(listOf("work"), "WORK"))
        assertEquals(listOf("home"), ChatTags.remove(listOf("Work", "home"), "work"))
    }

    @Test
    fun tagsAreCountedAcrossChatsMostUsedFirst() {
        val chats = listOf(chat("1", "Work", "ideas"), chat("2", "work"), chat("3", "home", "WORK"), chat("4"))
        assertEquals(listOf("Work" to 3, "home" to 1, "ideas" to 1), ChatTags.counts(chats))
    }

    @Test
    fun aChatMatchesATagIgnoringCapitals() {
        assertTrue(ChatTags.has(chat("1", "Work"), "work"))
        assertFalse(ChatTags.has(chat("1", "Work"), "home"))
        assertFalse(ChatTags.has(chat("1"), "work"))
    }

    @Test
    fun suggestionsLeaveOutTagsAlreadyOnTheChatAndFollowWhatIsTyped() {
        val all = listOf("work" to 5, "workout" to 2, "home" to 1)
        assertEquals(listOf("workout", "home"), ChatTags.suggestions(all, listOf("work"), ""))
        assertEquals(listOf("work", "workout"), ChatTags.suggestions(all, emptyList(), "#WO"))
        assertTrue(ChatTags.suggestions(all, emptyList(), "zzz").isEmpty())
    }
}

class ChatCodecTest {

    @Test
    fun aChatSurvivesBeingSavedAndLoaded() {
        val chat = SavedChat(
            id = "abc-123", title = "Trip plan", updatedAt = 1234, modelFile = "m.gguf",
            messages = listOf(
                StoredMessage("hello", true, imagePath = "/img/a.jpg"),
                StoredMessage(
                    "answer", false, stats = GenStats(12, 3.5f, 400), summary = false,
                    others = listOf(Reply("older answer", GenStats(5, null, 100))), position = 1,
                ),
                StoredMessage("so far we...", false, summary = true),
            ),
            attachments = listOf(Attachment("doc-1", "notes.txt", 99)),
            pinned = true, customTitle = true, presetId = "p1", jsonMode = true, jsonSchema = "{\"type\":\"object\"}",
            tags = listOf("travel", "Ideas"),
        )
        assertEquals(chat, ChatCodec.decode(ChatCodec.encode(chat).toString()))
    }

    @Test
    fun aChatWithoutTagsHasNoTagsKeyAndOldFilesStillLoad() {
        val chat = SavedChat("x", "t", 1, null, listOf(StoredMessage("hi", true)))
        assertFalse(ChatCodec.encode(chat).has("tags"))
        val old = """{"id":"x","title":"t","updatedAt":1,"modelFile":null,"messages":[{"text":"hi","isUser":true}]}"""
        assertEquals(chat, ChatCodec.decode(old))
    }

    @Test
    fun tagsFromAnotherDeviceAreTidiedOnLoad() {
        val json = JSONObject(ChatCodec.encode(SavedChat("x", "t", 1, null, emptyList())).toString())
            .put("tags", org.json.JSONArray(listOf("Work", "work", "  ", "#home")))
        assertEquals(listOf("Work", "home"), ChatCodec.decode(json.toString()).tags)
    }
}
