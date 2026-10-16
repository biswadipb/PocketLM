package com.pocketlm.app.data

import com.arm.aichat.ContextUsage
import com.pocketlm.app.data.ContextMeter.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextMeterTest {
    @Test
    fun theBarIsTheShareOfMemoryUsed() {
        assertEquals(0.25f, ContextMeter.fraction(ContextUsage(1024, 4096)), 0.0001f)
        assertEquals(0f, ContextMeter.fraction(ContextUsage(0, 0)), 0f)
        assertEquals(1f, ContextMeter.fraction(ContextUsage(5000, 4096)), 0f)
    }

    @Test
    fun warningsAppearAsMemoryFillsUp() {
        assertEquals(Level.Fine, ContextMeter.level(ContextUsage(2000, 4096)))
        assertNull(ContextMeter.warning(ContextUsage(2000, 4096)))
        assertEquals(Level.Filling, ContextMeter.level(ContextUsage(3000, 4096)))
        assertTrue(ContextMeter.warning(ContextUsage(3000, 4096))!!.contains("Filling"))
        assertEquals(Level.Full, ContextMeter.level(ContextUsage(3800, 4096)))
        assertTrue(ContextMeter.warning(ContextUsage(3800, 4096))!!.contains("oldest messages"))
    }

    @Test
    fun theLabelUsesThousandsSeparators() {
        assertEquals("1,230 of 4,096 tokens of memory used", ContextMeter.label(ContextUsage(1230, 4096)))
    }
}
