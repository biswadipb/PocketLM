package com.pocketlm.app.data

import java.text.DateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFormatTest {

    private fun line(level: String, tag: String, message: String = "something happened") =
        "10-16 12:00:01.123  4242  4243 $level $tag: $message"

    @Test
    fun warningsAndErrorsFromAnywhereAreKept() {
        assertTrue(LogFormat.keep(line("E", "AndroidRuntime", "FATAL EXCEPTION")))
        assertTrue(LogFormat.keep(line("W", "SomeLibrary")))
        assertTrue(LogFormat.keep(line("F", "libc", "Fatal signal 11")))
    }

    @Test
    fun routineMessagesAreKeptOnlyFromPocketLmItself() {
        assertTrue(LogFormat.keep(line("I", "ai-chat", "model loaded")))
        assertTrue(LogFormat.keep(line("D", "InferenceEngineImpl")))
        assertFalse(LogFormat.keep(line("I", "SurfaceFlinger")))
        assertFalse(LogFormat.keep(line("V", "ai-chat")))
    }

    @Test
    fun linesThatAreNotLogEntriesAreDropped() {
        assertFalse(LogFormat.keep("--------- beginning of main"))
        assertFalse(LogFormat.keep(""))
    }

    @Test
    fun onlyTheNewestLinesAreReturned() {
        val lines = (1..10).map { line("E", "Tag", "n$it") }
        val kept = LogFormat.filter(lines, max = 3)
        assertEquals(3, kept.size)
        assertTrue(kept.last().endsWith("n10"))
    }

    @Test
    fun exitReasonsReadAsSentences() {
        assertEquals("Closed by Android: low memory", LogFormat.exitReasonName(android.app.ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("Native crash", LogFormat.exitReasonName(android.app.ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("Reason 999", LogFormat.exitReasonName(999))
    }

    @Test
    fun anExitShowsWhatHappenedAndHowMuchMemoryItUsed() {
        val text = LogFormat.formatExit(
            ExitRecord(timeMs = 0, reason = android.app.ApplicationExitInfo.REASON_LOW_MEMORY, description = "lmkd killed it", rssKb = 3_000_000),
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.US),
        )
        assertTrue("low memory" in text)
        assertTrue("2929 MB" in text)
        assertTrue("lmkd killed it" in text)
    }
}
