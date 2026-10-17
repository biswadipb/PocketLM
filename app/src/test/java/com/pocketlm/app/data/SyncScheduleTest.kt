package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncScheduleTest {

    @Test
    fun noJobIsWantedUnlessSyncIsSetUpAutomaticAndOn() {
        assertNull(SyncSchedule.wanted(configured = false, automatic = true, minutes = 60, wifiOnly = true))
        assertNull(SyncSchedule.wanted(configured = true, automatic = false, minutes = 60, wifiOnly = true))
        assertNull(SyncSchedule.wanted(configured = true, automatic = true, minutes = 0, wifiOnly = true))
    }

    @Test
    fun theJobFollowsTheChosenIntervalAndNetwork() {
        assertEquals(SyncJobSpec(3_600_000, true), SyncSchedule.wanted(true, true, 60, true))
        assertEquals(SyncJobSpec(21_600_000, false), SyncSchedule.wanted(true, true, 360, false))
        assertEquals(SyncJobSpec(86_400_000, true), SyncSchedule.wanted(true, true, 1440, true))
    }

    @Test
    fun androidNeverRunsAPeriodicJobMoreOftenThanEveryFifteenMinutes() {
        assertEquals(SyncJobSpec(15 * 60_000L, true), SyncSchedule.wanted(true, true, 5, true))
        assertTrue(SyncSchedule.choices.filter { it > 0 }.all { it >= SyncSchedule.MIN_MINUTES })
    }

    @Test
    fun theJobIsOnlyTouchedWhenWhatIsWantedDiffersFromWhatIsQueued() {
        val hourly = SyncJobSpec(3_600_000, true)
        assertEquals(SyncSchedule.Change.None, SyncSchedule.change(null, null))
        assertEquals(SyncSchedule.Change.None, SyncSchedule.change(hourly, hourly))
        assertEquals(SyncSchedule.Change.Cancel, SyncSchedule.change(hourly, null))
        assertEquals(SyncSchedule.Change.Schedule(hourly), SyncSchedule.change(null, hourly))
        assertEquals(SyncSchedule.Change.Schedule(hourly.copy(unmetered = false)), SyncSchedule.change(hourly, hourly.copy(unmetered = false)))
    }

    @Test
    fun everyChoiceHasALabel() {
        assertEquals(listOf("Off", "15 minutes", "Hourly", "6 hours", "Daily"), SyncSchedule.choices.map(SyncSchedule::label))
    }

    @Test
    fun theSyncedSettingsListHasNoSecretsOrPerPhoneChoices() {
        for (key in listOf("api_key", "app_lock", "gen_threads", "gen_context_size", "theme_mode", "hub_source", "hub_mirror_url", "model_settings", "search_server", "api_enabled")) {
            assertTrue(key, key !in SettingKeys.SYNCED)
        }
        assertEquals(15, SettingKeys.SYNCED.size)
    }
}
