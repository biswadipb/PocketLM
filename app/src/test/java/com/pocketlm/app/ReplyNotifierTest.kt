package com.pocketlm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyNotifierTest {
    @Test
    fun previewIsPlainTextOnOneLine() {
        val p = ReplyNotifier.preview("# Title\n\nThis is **bold**\nand `code`.")
        assertFalse(p.contains("#") || p.contains("*") || p.contains("`") || p.contains("\n"))
        assertEquals("Title This is bold and code.", p)
    }

    @Test
    fun longRepliesAreCutWithAnEllipsis() {
        val p = ReplyNotifier.preview("word ".repeat(100), maxChars = 30)
        assertTrue(p.length <= 30)
        assertTrue(p.endsWith("…"))
    }

    @Test
    fun shortRepliesAreLeftAlone() {
        assertEquals("Hi there.", ReplyNotifier.preview("Hi there."))
    }
}
