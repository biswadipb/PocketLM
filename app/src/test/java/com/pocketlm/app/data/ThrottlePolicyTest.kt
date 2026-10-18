package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThrottlePolicyTest {

    private fun status(thermal: Int = 0, battery: Int = 80, charging: Boolean = false) = DeviceStatus(battery, charging, thermal)

    @Test
    fun aCoolPhoneWithPowerIsNotHeldBack() {
        assertEquals(ThrottleLevel.None, ThrottlePolicy.level(status()))
        assertEquals(ThrottleLevel.None, ThrottlePolicy.level(status(thermal = 1)))
    }

    @Test
    fun aWarmPhoneIsSlowedALittleAndAHotOneALot() {
        assertEquals(ThrottleLevel.Light, ThrottlePolicy.level(status(thermal = 2)))
        assertEquals(ThrottleLevel.Heavy, ThrottlePolicy.level(status(thermal = 3)))
        assertEquals(ThrottleLevel.Heavy, ThrottlePolicy.level(status(thermal = 5)))
    }

    @Test
    fun lowBatteryOnlyCountsWhenNotCharging() {
        assertEquals(ThrottleLevel.Light, ThrottlePolicy.level(status(battery = 15)))
        assertEquals(ThrottleLevel.None, ThrottlePolicy.level(status(battery = 16)))
        assertEquals(ThrottleLevel.Heavy, ThrottlePolicy.level(status(battery = 7)))
        assertEquals(ThrottleLevel.None, ThrottlePolicy.level(status(battery = 5, charging = true)))
    }

    @Test
    fun theWorseOfHeatAndBatteryWins() {
        assertEquals(ThrottleLevel.Heavy, ThrottlePolicy.level(status(thermal = 2, battery = 5)))
        assertEquals(ThrottleLevel.Heavy, ThrottlePolicy.level(status(thermal = 3, battery = 90)))
        assertEquals(ThrottleLevel.Light, ThrottlePolicy.level(status(thermal = 2, battery = 90)))
    }

    @Test
    fun theBatteryLevelDoesNotFlickerAroundItsLimit() {
        assertEquals(ThrottleLevel.Light, ThrottlePolicy.level(status(battery = 15)))
        // Once held back, it stays so until the battery is a few percent past the limit.
        assertEquals(ThrottleLevel.Light, ThrottlePolicy.level(status(battery = 16), previous = ThrottleLevel.Light))
        assertEquals(ThrottleLevel.Light, ThrottlePolicy.level(status(battery = 17), previous = ThrottleLevel.Light))
        assertEquals(ThrottleLevel.None, ThrottlePolicy.level(status(battery = 18), previous = ThrottleLevel.Light))
        assertEquals(ThrottleLevel.Heavy, ThrottlePolicy.level(status(battery = 9), previous = ThrottleLevel.Heavy))
        assertEquals(ThrottleLevel.Light, ThrottlePolicy.level(status(battery = 10), previous = ThrottleLevel.Heavy))
    }

    @Test
    fun threadsAndReplyLengthShrinkWithTheLevel() {
        assertEquals(listOf(100, 70, 40), ThrottleLevel.entries.map(ThrottlePolicy::threadPercent))
        assertEquals(1024, ThrottlePolicy.replyCap(1024, ThrottleLevel.None))
        assertEquals(768, ThrottlePolicy.replyCap(1024, ThrottleLevel.Light))
        assertEquals(384, ThrottlePolicy.replyCap(1024, ThrottleLevel.Heavy))
    }

    @Test
    fun aShortLimitTheUserChoseIsNeverRaised() {
        assertEquals(200, ThrottlePolicy.replyCap(200, ThrottleLevel.Light))
        assertEquals(200, ThrottlePolicy.replyCap(200, ThrottleLevel.Heavy))
    }

    @Test
    fun theUserIsToldWhyThingsAreSlower() {
        assertNull(ThrottlePolicy.note(ThrottleLevel.None, status()))
        assertTrue(ThrottlePolicy.note(ThrottleLevel.Light, status(thermal = 2))!!.contains("cools"))
        assertTrue(ThrottlePolicy.note(ThrottleLevel.Light, status(battery = 10))!!.contains("battery"))
        assertNotNull(ThrottlePolicy.note(ThrottleLevel.Heavy, status(thermal = 4)))
    }
}
