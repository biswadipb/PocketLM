package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {

    @Test
    fun markdownSymbolsAreRemoved() {
        val spoken = SpeechText.clean("# Title\n\nThis is **bold**, *italic* and `code`.\n\n- one\n- two\n\nSee [the docs](https://x.y).")
        assertFalse(spoken.contains("#"))
        assertFalse(spoken.contains("*"))
        assertFalse(spoken.contains("`"))
        assertFalse(spoken.contains("https"))
        assertTrue(spoken.contains("This is bold, italic and code."))
        assertTrue(spoken.contains("See the docs."))
        assertTrue(spoken.contains("one\ntwo"))
    }

    @Test
    fun codeBlocksAreSkippedNotRead() {
        val spoken = SpeechText.clean("Try this:\n```kotlin\nval x = 1\n```\nDone.")
        assertFalse(spoken.contains("val x"))
        assertTrue(spoken.contains("Code block skipped"))
        assertTrue(spoken.endsWith("Done."))
    }

    @Test
    fun anUnfinishedCodeBlockWhileStreamingIsSkippedToo() {
        assertFalse(SpeechText.clean("Here:\n```py\nprint(1)").contains("print"))
    }

    @Test
    fun multiplicationStarsAreLeftAlone() {
        assertTrue(SpeechText.clean("2 * 3 * 4 is 24").contains("2 * 3 * 4"))
    }

    @Test
    fun chunksStayUnderTheLimitAndKeepAllText() {
        val text = (1..200).joinToString(" ") { "Sentence number $it is here." }
        val chunks = SpeechText.chunks(text, maxChars = 300)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 300 })
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun blankTextGivesNoChunks() {
        assertTrue(SpeechText.chunks("  \n ").isEmpty())
    }
}
