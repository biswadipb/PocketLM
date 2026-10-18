package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopFilterTest {

    private fun run(stops: List<String>, vararg pieces: String): Pair<String, Boolean> {
        val f = StopFilter(stops)
        val out = StringBuilder()
        for (p in pieces) {
            out.append(f.feed(p))
            if (f.finished) break
        }
        out.append(f.flush())
        return out.toString() to f.finished
    }

    @Test
    fun withNoStopSequencesEverythingPassesThrough() {
        assertEquals("Hello there" to false, run(emptyList(), "Hel", "lo", " there"))
    }

    @Test
    fun aStopSequenceInsideOnePieceCutsTheReply() {
        assertEquals("Hello " to true, run(listOf("###"), "Hello ###bye"))
    }

    @Test
    fun aStopSequenceSplitAcrossPiecesIsNeverPartlySent() {
        assertEquals("Hi " to true, run(listOf("User:"), "Hi Us", "er", ": more"))
    }

    @Test
    fun heldBackTextIsReleasedWhenItTurnsOutNotToBeAStop() {
        assertEquals("Use the User tab" to false, run(listOf("User:"), "Use the Us", "er tab"))
    }

    @Test
    fun heldBackTextIsFlushedAtTheEndOfTheReply() {
        assertEquals("ends with Us" to false, run(listOf("User:"), "ends with Us"))
    }

    @Test
    fun theEarliestOfSeveralStopSequencesWins() {
        assertEquals("a" to true, run(listOf("c", "b"), "abc"))
    }

    @Test
    fun nothingIsSentAfterTheStop() {
        val f = StopFilter(listOf("x"))
        assertEquals("ab", f.feed("abxcd"))
        assertTrue(f.finished)
        assertEquals("", f.feed("more"))
        assertEquals("", f.flush())
    }

    @Test
    fun emptyStopStringsAreIgnored() {
        val f = StopFilter(listOf(""))
        assertEquals("text", f.feed("text"))
        assertFalse(f.finished)
    }

    @Test
    fun aRepeatedPrefixDoesNotConfuseTheHoldBack() {
        // "aab" with stop "ab": the first 'a' can be sent, the second is held, then 'b' completes the stop.
        assertEquals("a" to true, run(listOf("ab"), "a", "a", "b"))
    }
}
