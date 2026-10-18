package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ChatImportTest {

    // ---- Streaming the list ----------------------------------------------------------

    private fun stream(text: String, max: Int = JsonArrayStream.MAX_ELEMENT_CHARS) =
        JsonArrayStream.elements(StringReader(text), max).toList()

    @Test
    fun anArrayIsReadOneElementAtATime() {
        assertEquals(listOf("""{"a":1}""", """{"b":[1,2,{"c":3}]}""", "[1,2]"), stream("""  [ {"a":1} , {"b":[1,2,{"c":3}]},[1,2] ]  """))
        assertEquals(emptyList<String>(), stream("[]"))
        assertEquals(emptyList<String>(), stream(" [ \n ] "))
    }

    @Test
    fun bracketsQuotesAndEscapesInsideStringsDoNotConfuseIt() {
        val tricky = """{"text":"a } ] \" , [ { \\ end","n":"é\n"}"""
        assertEquals(listOf(tricky, """{"x":"}"}"""), stream("[$tricky,{\"x\":\"}\"}]"))
    }

    @Test
    fun plainValuesInTheListAreKeptToo() {
        assertEquals(listOf("12", "\"s,t\"", "true", "null"), stream("""[12, "s,t", true,null]"""))
    }

    @Test
    fun aByteOrderMarkIsIgnored() {
        assertEquals(listOf("""{"a":1}"""), stream("﻿[{\"a\":1}]"))
    }

    @Test
    fun aFileThatIsNotAListIsRefused() {
        for (text in listOf("""{"a":1}""", "hello", "")) {
            try {
                stream(text)
                fail("accepted: $text")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("not a list"))
            }
        }
    }

    @Test
    fun aCutOffFileIsReportedAfterTheCompleteElementsWereRead() {
        val seq = JsonArrayStream.elements(StringReader("""[{"a":1},{"b":"unfinished"""))
        val it = seq.iterator()
        assertEquals("""{"a":1}""", it.next())
        try {
            it.next()
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("ends in the middle"))
        }
    }

    @Test
    fun anElementThatIsTooLongIsSkippedNotHeld() {
        val big = """{"text":"${"x".repeat(100)}"}"""
        assertEquals(listOf("""{"a":1}""", null, """{"b":2}"""), stream("""[{"a":1},$big,{"b":2}]""", max = 50))
    }

    // ---- ChatGPT ---------------------------------------------------------------------

    private fun node(id: String, parent: String?, role: String?, text: String?, extra: JSONObject.() -> Unit = {}): JSONObject {
        val n = JSONObject().put("id", id).put("parent", parent ?: JSONObject.NULL).put("children", JSONArray())
        if (role == null) n.put("message", JSONObject.NULL) else {
            val m = JSONObject()
                .put("id", "m-$id").put("author", JSONObject().put("role", role)).put("create_time", 1700000000.0 + id.hashCode() % 1000)
                .put("content", JSONObject().put("content_type", "text").put("parts", JSONArray(listOf(text ?: ""))))
                .put("metadata", JSONObject()).put("recipient", "all")
            m.extra()
            n.put("message", m)
        }
        return n
    }

    private fun chatGpt(vararg nodes: JSONObject, current: String? = nodes.last().getString("id")): JSONObject {
        val mapping = JSONObject()
        for (n in nodes) mapping.put(n.getString("id"), n)
        for (n in nodes) {
            val parent = n.optString("parent")
            if (parent.isNotEmpty()) mapping.getJSONObject(parent).getJSONArray("children").put(n.getString("id"))
        }
        return JSONObject().put("title", "Pasta ideas").put("create_time", 1700000000.5).put("update_time", 1700000500.25)
            .put("mapping", mapping).put("current_node", current ?: JSONObject.NULL).put("conversation_id", "conv-1")
    }

    @Test
    fun aChatGptConversationBecomesAChat() {
        val export = chatGpt(
            node("root", null, null, null),
            node("sys", "root", "system", "You are ChatGPT"),
            node("u1", "sys", "user", "What can I cook with pasta?"),
            node("a1", "u1", "assistant", "Try aglio e olio."),
            node("u2", "a1", "user", "Thanks"),
        )
        val chat = ChatImporter.parse(export)!!
        assertEquals(ImportSource.ChatGpt, chat.source)
        assertEquals(listOf("What can I cook with pasta?" to true, "Try aglio e olio." to false, "Thanks" to true), chat.turns)
        assertEquals(1700000500250L, chat.updatedAt)
        val saved = chat.toSavedChat()
        assertEquals("Pasta ideas", saved.title)
        assertTrue(saved.customTitle)
        assertEquals(listOf("Imported", "ChatGPT"), saved.tags)
        assertEquals(3, saved.messages.size)
        assertTrue(saved.messages[0].isUser && !saved.messages[1].isUser)
    }

    @Test
    fun onlyTheBranchThatWasShownIsKept() {
        // The user edited their first question; the old branch (u1, a1) is still in the tree.
        val export = chatGpt(
            node("root", null, null, null),
            node("u1", "root", "user", "Original question"),
            node("a1", "u1", "assistant", "Answer to the original"),
            node("u1b", "root", "user", "Edited question"),
            node("a1b", "u1b", "assistant", "Answer to the edit"),
            current = "a1b",
        )
        assertEquals(listOf("Edited question" to true, "Answer to the edit" to false), ChatImporter.parse(export)!!.turns)
    }

    @Test
    fun withoutACurrentNodeTheNewestEndOfTheTreeIsUsed() {
        val export = chatGpt(
            node("root", null, null, null),
            node("u1", "root", "user", "Hello"),
            node("a1", "u1", "assistant", "Hi there"),
            current = null,
        ).also { it.remove("current_node") }
        assertEquals(listOf("Hello" to true, "Hi there" to false), ChatImporter.parse(export)!!.turns)
    }

    @Test
    fun hiddenToolAndCodeStepsAreLeftOut() {
        val export = chatGpt(
            node("root", null, null, null),
            node("u1", "root", "user", "Plot something"),
            node("a1", "u1", "assistant", "import matplotlib") { put("recipient", "python") },
            node("t1", "a1", "tool", "figure created"),
            node("h1", "t1", "assistant", "hidden note") { put("metadata", JSONObject().put("is_visually_hidden_from_conversation", true)) },
            node("a2", "h1", "assistant", "Here is your plot."),
        )
        assertEquals(listOf("Plot something" to true, "Here is your plot." to false), ChatImporter.parse(export)!!.turns)
    }

    @Test
    fun consecutiveAssistantStepsAreJoined() {
        val export = chatGpt(
            node("root", null, null, null),
            node("u1", "root", "user", "Look it up"),
            node("a1", "u1", "assistant", "Let me check."),
            node("a2", "a1", "assistant", "Found it: 42."),
        )
        assertEquals(listOf("Look it up" to true, "Let me check.\n\nFound it: 42." to false), ChatImporter.parse(export)!!.turns)
    }

    @Test
    fun imagesBecomeAPlaceholderAndCitationMarkersDisappear() {
        val mixed = node("u1", "root", "user", null) {
            put("content", JSONObject().put("content_type", "multimodal_text").put(
                "parts",
                JSONArray().put(JSONObject().put("content_type", "image_asset_pointer").put("asset_pointer", "file-service://x")).put("What is this?"),
            ))
        }
        val cited = node("a1", "u1", "assistant", "It is a catciteturn0search1.")
        val chat = ChatImporter.parse(chatGpt(node("root", null, null, null), mixed, cited))!!
        assertEquals("[image]\n\nWhat is this?", chat.turns[0].first)
        assertEquals("It is a cat.", chat.turns[1].first)
    }

    @Test
    fun aConversationWithNothingToKeepIsSkipped() {
        val export = chatGpt(node("root", null, null, null), node("s", "root", "system", "x"), node("a", "s", "assistant", "Hello?"))
        assertNull(ChatImporter.parse(export))
    }

    @Test
    fun aLoopInTheTreeDoesNotHangTheImport() {
        val a = node("a", "b", "user", "loop a")
        val b = node("b", "a", "assistant", "loop b")
        val export = JSONObject().put("title", "t").put("conversation_id", "c").put("update_time", 1.0)
            .put("mapping", JSONObject().put("a", a).put("b", b)).put("current_node", "a")
        assertEquals(2, ChatImporter.parse(export)!!.turns.size)
    }

    // ---- Claude ----------------------------------------------------------------------

    private fun claude(vararg messages: JSONObject) = JSONObject()
        .put("uuid", "c0ffee-1").put("name", "Trip planning").put("created_at", "2024-05-01T12:00:00.000000Z")
        .put("updated_at", "2024-05-01T12:30:15.500000Z").put("chat_messages", JSONArray(messages.toList()))

    private fun claudeMessage(sender: String, text: String, blocks: List<JSONObject>? = null) =
        JSONObject().put("uuid", "m-${text.hashCode()}").put("sender", sender).put("text", text)
            .apply { if (blocks != null) put("content", JSONArray(blocks)) }

    @Test
    fun aClaudeConversationBecomesAChat() {
        val export = claude(
            claudeMessage("human", "Where should I go in May?"),
            claudeMessage("assistant", "ignored", listOf(
                JSONObject().put("type", "thinking").put("thinking", "hmm"),
                JSONObject().put("type", "text").put("text", "Lisbon is lovely in May."),
            )),
        )
        val chat = ChatImporter.parse(export)!!
        assertEquals(ImportSource.Claude, chat.source)
        assertEquals(listOf("Where should I go in May?" to true, "Lisbon is lovely in May." to false), chat.turns)
        assertEquals(1714566615500L, chat.updatedAt)
        assertEquals("Trip planning", chat.toSavedChat().title)
        assertEquals(listOf("Imported", "Claude"), chat.toSavedChat().tags)
    }

    @Test
    fun attachedFilesAreNotedByName() {
        val withFile = claudeMessage("human", "Summarise this").put(
            "attachments", JSONArray().put(JSONObject().put("file_name", "report.pdf").put("extracted_content", "long text")),
        )
        val chat = ChatImporter.parse(claude(withFile, claudeMessage("assistant", "It says...")))!!
        assertEquals("Summarise this\n\n[Attached file: report.pdf]", chat.turns[0].first)
    }

    @Test
    fun anUnnamedChatTakesItsTitleFromTheFirstQuestion() {
        val export = claude(claudeMessage("human", "How do I bake bread?\nWith detail please"), claudeMessage("assistant", "Mix flour."))
            .put("name", "")
        val saved = ChatImporter.parse(export)!!.toSavedChat()
        assertEquals("How do I bake bread?", saved.title)
        assertFalse(saved.customTitle)
    }

    @Test
    fun timesAreReadInTheFormsTheServicesUse() {
        assertEquals(1714566615500L, ChatImporter.parseTime("2024-05-01T12:30:15.500000Z"))
        assertEquals(1714566615000L, ChatImporter.parseTime("2024-05-01T14:30:15+02:00"))
        assertNull(ChatImporter.parseTime(""))
        assertNull(ChatImporter.parseTime("yesterday"))
    }

    @Test
    fun theSameConversationAlwaysGetsTheSameChatId() {
        val a = ChatImporter.parse(claude(claudeMessage("human", "Hi"), claudeMessage("assistant", "Hello")))!!
        val b = ChatImporter.parse(claude(claudeMessage("human", "Hi"), claudeMessage("assistant", "Hello")))!!
        assertEquals(a.id, b.id)
        assertTrue(SyncNames.validId(a.id))
        // The same id from two services is still two chats.
        assertNotEquals(ImportSource.ChatGpt.chatId("same"), ImportSource.Claude.chatId("same"))
    }

    @Test
    fun somethingThatIsNotAConversationIsRefused() {
        try {
            ChatImporter.parse(JSONObject().put("hello", "world"))
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Not a ChatGPT or Claude"))
        }
    }

    // ---- Whole files ------------------------------------------------------------------

    private fun twoServicesList(): String = JSONArray()
        .put(chatGpt(node("root", null, null, null), node("u1", "root", "user", "Hello GPT"), node("a1", "u1", "assistant", "Hello!")))
        .put(claude(claudeMessage("human", "Hello Claude"), claudeMessage("assistant", "Hi!")).put("uuid", "claude-2"))
        .put(JSONObject().put("title", "just a system message").put("conversation_id", "x").put("mapping", JSONObject()))
        .put(JSONObject().put("nonsense", true))
        .toString()

    @Test
    fun aPlainConversationsFileIsImportedAndCounted() {
        val seen = ArrayList<ImportedChat>()
        val summary = ChatImporter.read(twoServicesList().byteInputStream()) { seen += it; true }
        assertEquals(ImportSummary(imported = 2, alreadyHere = 0, empty = 1, unreadable = 1), summary)
        assertEquals(setOf(ImportSource.ChatGpt, ImportSource.Claude), seen.map { it.source }.toSet())
        assertEquals("Imported 2 chats, 1 empty skipped, 1 could not be read", summary.message())
    }

    @Test
    fun chatsAlreadyHereAreCountedNotImported() {
        val summary = ChatImporter.read(twoServicesList().byteInputStream()) { it.source == ImportSource.Claude }
        assertEquals(1, summary.imported)
        assertEquals(1, summary.alreadyHere)
        assertTrue(summary.message().contains("1 already here"))
    }

    @Test
    fun aNewerLocalCopyIsLeftAlone() {
        val chat = ChatImporter.parse(claude(claudeMessage("human", "Hi"), claudeMessage("assistant", "Hello")))!!
        assertTrue(ChatImporter.alreadyUpToDate(chat.updatedAt, chat))
        assertTrue(ChatImporter.alreadyUpToDate(chat.updatedAt + 1, chat))
        assertFalse(ChatImporter.alreadyUpToDate(chat.updatedAt - 1, chat))
        assertFalse(ChatImporter.alreadyUpToDate(null, chat))
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in entries) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
        }
        return out.toByteArray()
    }

    @Test
    fun aZipExportIsReadIncludingSplitConversationFiles() {
        fun one(id: String) = JSONArray().put(
            claude(claudeMessage("human", "Q $id"), claudeMessage("assistant", "A $id")).put("uuid", id),
        ).toString()
        val bytes = zip(
            "export/user.json" to """{"name":"me"}""",
            "export/conversations-000.json" to one("first"),
            "export/conversations-001.json" to one("second"),
            "export/projects.json" to "[]",
        )
        val ids = ArrayList<String>()
        val summary = ChatImporter.read(bytes.inputStream()) { ids += it.sourceId; true }
        assertEquals(2, summary.imported)
        assertEquals(listOf("first", "second"), ids)
    }

    @Test
    fun aZipWithoutConversationsIsExplained() {
        try {
            ChatImporter.read(zip("readme.txt" to "hi").inputStream()) { true }
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("no conversations.json"))
        }
    }

    @Test
    fun anEmptyExportSaysSo() {
        assertEquals("No conversations were found in that file.", ChatImporter.read("[]".byteInputStream()) { true }.message())
    }

    @Test
    fun aHugeConversationIsCountedAsUnreadableNotImported() {
        val huge = claude(claudeMessage("human", "x".repeat(2_100_000)), claudeMessage("assistant", "ok"))
        val summary = ChatImporter.read(JSONArray().put(huge).toString().byteInputStream()) { true }
        assertEquals(1, summary.unreadable)
        assertEquals(0, summary.imported)
    }
}
