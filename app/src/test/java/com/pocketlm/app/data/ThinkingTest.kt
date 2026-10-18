package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingTest {
    @Test
    fun aFinishedThoughtIsSeparatedFromTheAnswer() {
        val p = Thinking.split("<think>\nLet me work it out.\n</think>\n\nThe answer is 4.")
        assertEquals("Let me work it out.", p.thinking)
        assertEquals("The answer is 4.", p.answer)
        assertFalse(p.thinkingOpen)
    }

    @Test
    fun aThoughtStillBeingWrittenIsOpenWithNoAnswerYet() {
        val p = Thinking.split("<think>Hmm, first I should")
        assertEquals("Hmm, first I should", p.thinking)
        assertEquals("", p.answer)
        assertTrue(p.thinkingOpen)
    }

    @Test
    fun aReplyThatOnlyClosesTheTagIsStillSplit() {
        val p = Thinking.split("I think so.\n</think>\nYes.")
        assertEquals("I think so.", p.thinking)
        assertEquals("Yes.", p.answer)
    }

    @Test
    fun ordinaryRepliesAreLeftAlone() {
        val p = Thinking.split("Just an answer, mentioning <think> tags in text.")
        assertNull(p.thinking)
        assertEquals("Just an answer, mentioning <think> tags in text.", p.answer)
        assertEquals("plain", Thinking.answerOnly("plain"))
    }

    @Test
    fun leadingWhitespaceBeforeTheTagIsIgnored() {
        assertEquals("ok", Thinking.split("\n  <think>x</think>ok").answer)
    }

    @Test
    fun answerOnlyDropsTheReasoning() {
        assertEquals("42", Thinking.answerOnly("<think>long reasoning</think>42"))
        assertEquals("", Thinking.answerOnly("<think>unfinished"))
    }

    @Test
    fun exportKeepsTheReasoningInAFoldedSection() {
        val md = ChatExport.toMarkdown("T", listOf(StoredMessage("<think>why</think>because", isUser = false)), null, null)
        assertTrue(md.contains("<details>") && md.contains("why") && md.contains("because"))
        assertTrue(md.indexOf("why") < md.indexOf("because"))
    }
}
