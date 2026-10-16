package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdlePolicyTest {
    private val minute = 60_000L

    private fun sleeps(idleFor: Long, timeout: Long = 5 * minute, busy: Boolean = false, hasModel: Boolean = true, asleep: Boolean = false) =
        IdlePolicy.shouldSleep(nowMs = 1_000_000 + idleFor, lastActiveMs = 1_000_000, timeoutMs = timeout, busy = busy, hasModel = hasModel, asleep = asleep)

    @Test
    fun sleepsOnlyOnceTheTimeoutHasPassed() {
        assertFalse(sleeps(4 * minute))
        assertTrue(sleeps(5 * minute))
        assertTrue(sleeps(30 * minute))
    }

    @Test
    fun neverSleepsWhenTheSettingIsNever() {
        assertFalse(sleeps(100 * minute, timeout = 0))
    }

    @Test
    fun neverSleepsWhileBusyOrWithNothingLoadedOrAlreadyAsleep() {
        assertFalse(sleeps(10 * minute, busy = true))
        assertFalse(sleeps(10 * minute, hasModel = false))
        assertFalse(sleeps(10 * minute, asleep = true))
    }

    @Test
    fun labelsReadNaturally() {
        assertEquals(listOf("Never", "After 5 minutes", "After 15 minutes", "After 30 minutes", "After 1 hour"), IdlePolicy.choices.map(IdlePolicy::label))
    }
}
