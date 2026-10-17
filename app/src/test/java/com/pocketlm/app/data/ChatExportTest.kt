package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExportTest {

    private val messages = listOf(
        StoredMessage("What is 2+2?", isUser = true),
        StoredMessage("It is **4**.", isUser = false),
        StoredMessage("Describe this", isUser = true, imagePath = "/x/y.jpg"),
        StoredMessage("   ", isUser = false),
    )

    @Test
    fun markdownHasATitleAndLabelledTurns() {
        val md = ChatExport.toMarkdown("Maths", messages, "qwen2.5-0.5b-q4.gguf", exportedAt = null)
        assertTrue(md.startsWith("# Maths\n"))
        assertTrue(md.contains("Model: qwen2.5-0.5b-q4"))
        assertTrue(md.contains("## You\n\nWhat is 2+2?"))
        assertTrue(md.contains("## Piko\n\nIt is **4**."))
        assertTrue(md.endsWith("\n"))
    }

    @Test
    fun photosAreNotedAndEmptyReplyIsSkipped() {
        val md = ChatExport.toMarkdown("T", messages, null, null)
        assertTrue(md.contains("[Photo attached]"))
        assertEquals(1, Regex("## Piko").findAll(md).count())
        assertFalse(md.contains("Model:"))
    }

    @Test
    fun blankTitleGetsADefault() {
        assertTrue(ChatExport.toMarkdown(" ", emptyList(), null, null).startsWith("# PocketLM chat"))
    }

    @Test
    fun fileNamesAreSafe() {
        assertEquals("what-is-2-2.md", ChatExport.fileName("What is 2+2?!"))
        assertEquals("pocketlm-chat.md", ChatExport.fileName("???"))
        assertTrue(ChatExport.fileName("a".repeat(200)).length <= 43)
    }
}

class ChatExportSummaryTest {
    @Test
    fun aSummaryIsQuotedInTheExport() {
        val md = ChatExport.toMarkdown(
            "T",
            listOf(
                StoredMessage("Question", isUser = true),
                StoredMessage("Line one\nLine two", isUser = false, summary = true),
                StoredMessage("Next", isUser = true),
            ),
            null, null,
        )
        assertTrue("> **Summary of the conversation up to here**" in md)
        assertTrue("> Line one\n> Line two" in md)
        assertTrue(md.indexOf("Summary") < md.indexOf("Next"))
    }
}
