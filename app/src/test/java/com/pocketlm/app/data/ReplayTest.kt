package com.pocketlm.app.data

import com.arm.aichat.ContextUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayTest {

    private fun user(text: String) = Turn(true, text)
    private fun bot(text: String) = Turn(false, text)
    private fun summary(text: String) = Turn(false, text, summary = true)

    @Test
    fun nothingToReplayGivesNull() {
        assertNull(Replay.build(emptyList()))
    }

    @Test
    fun plainChatIsReplayedLineByLine() {
        assertEquals("User: hi\nAssistant: hello\nUser: and?", Replay.build(listOf(user("hi"), bot("hello"), user("and?"))))
    }

    @Test
    fun reasoningIsLeftOutOfTheReplay() {
        assertEquals("Assistant: 4", Replay.build(listOf(bot("<think>2+2 is 4</think>4"))))
    }

    @Test
    fun aLongChatKeepsItsMostRecentEnd() {
        val turns = (1..200).map { user("message number $it") }
        val replay = Replay.build(turns, maxChars = 100)!!
        assertEquals(100, replay.length)
        assertTrue(replay.endsWith("message number 200"))
    }

    @Test
    fun aSummaryReplacesEverythingBeforeIt() {
        val replay = Replay.build(listOf(user("old question"), bot("old answer"), summary("They talked about X."), user("new"), bot("reply")))!!
        assertEquals("Summary of the earlier conversation: They talked about X.\nUser: new\nAssistant: reply", replay)
        assertFalse("old question" in replay)
    }

    @Test
    fun onlyTheLatestSummaryCounts() {
        val replay = Replay.build(listOf(summary("first"), user("a"), summary("second"), user("b")))!!
        assertTrue(replay.startsWith("Summary of the earlier conversation: second"))
        assertFalse("first" in replay)
        assertFalse("User: a" in replay)
    }

    @Test
    fun theSummaryIsKeptWholeWhenLaterMessagesAreLong() {
        val replay = Replay.build(listOf(summary("Key facts."), user("x".repeat(1000)), bot("The end")), maxChars = 200)!!
        assertTrue(replay.length <= 200)
        assertTrue(replay.startsWith("Summary of the earlier conversation: Key facts."))
        assertTrue(replay.endsWith("Assistant: The end"))
    }

    @Test
    fun aSummaryOnAloneIsJustTheSummary() {
        assertEquals("Summary of the earlier conversation: Only this.", Replay.build(listOf(summary("Only this."))))
    }
}

class SummariesTest {

    @Test
    fun summarisesInPlaceOnlyWithRoomToWrite() {
        assertTrue(Summaries.canSummariseInPlace(ContextUsage(3000, 4096)))
        assertFalse(Summaries.canSummariseInPlace(ContextUsage(3700, 4096)))
        assertFalse(Summaries.canSummariseInPlace(ContextUsage(0, 0)))
    }

    @Test
    fun theTranscriptLeavesRoomForTheSummary() {
        assertEquals((4096 - 544) * 3, Summaries.transcriptChars(4096))
        assertEquals(1000, Summaries.transcriptChars(200))
    }

    @Test
    fun cleanRemovesReasoningAndStrayToolRequests() {
        assertEquals("Short summary.", Summaries.clean("<think>hmm</think>  Short summary.  "))
        assertEquals("Before", Summaries.clean("Before <tool>{\"name\":\"x\"}</tool>"))
        assertEquals("", Summaries.clean("<think>still thinking"))
    }

    @Test
    fun offeredOnceTheChatIsHalfFull() {
        assertTrue(Summaries.worthOffering(ContextUsage(2500, 4096), messageCount = 6))
        assertFalse(Summaries.worthOffering(ContextUsage(1000, 4096), messageCount = 6))
        assertFalse(Summaries.worthOffering(ContextUsage(3000, 4096), messageCount = 2))
    }

    @Test
    fun theRequestIncludesTheTranscript() {
        assertTrue("User: hi" in Summaries.requestFor("User: hi"))
    }
}
