package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayScaleTest {

    @Test
    fun theTextSizeIsKeptInRangeAndOnAStep() {
        assertEquals(1.0f, DisplayScale.snapText(1.0f), 1e-6f)
        assertEquals(0.85f, DisplayScale.snapText(0.1f), 1e-6f)
        assertEquals(1.5f, DisplayScale.snapText(9f), 1e-6f)
        assertEquals(1.25f, DisplayScale.snapText(1.249f), 1e-6f)
        assertEquals(1.1f, DisplayScale.snapText(1.12f), 1e-6f)
    }

    @Test
    fun aSavedValueFromAnOlderRunOrAnOddOneIsSafe() {
        assertEquals(0.85f, DisplayScale.snapText(Float.NEGATIVE_INFINITY), 1e-6f)
        assertEquals(1.5f, DisplayScale.snapText(Float.POSITIVE_INFINITY), 1e-6f)
    }

    @Test
    fun theLabelIsAPercentage() {
        assertEquals("100%", DisplayScale.label(1.0f))
        assertEquals("85%", DisplayScale.label(0.85f))
        assertEquals("150%", DisplayScale.label(1.5f))
    }

    @Test
    fun onlyWideWindowsGetTwoPanes() {
        assertFalse(DisplayScale.isWide(360))
        assertFalse(DisplayScale.isWide(839))
        assertTrue(DisplayScale.isWide(840))
        assertTrue(DisplayScale.isWide(1280))
    }
}
