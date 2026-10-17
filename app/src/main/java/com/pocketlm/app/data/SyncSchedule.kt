package com.pocketlm.app.data

/** What the system's background job should look like: how often, and whether it needs an unmetered network. */
data class SyncJobSpec(val periodMs: Long, val unmetered: Boolean)

/** Decides when the background sync should run. Pure, so the choices can be tested. */
object SyncSchedule {
    /** Android never runs a periodic job more often than this. */
    const val MIN_MINUTES = 15
    const val DEFAULT_MINUTES = 60

    /** The choices offered in Settings; 0 means no background sync. */
    val choices = listOf(0, 15, 60, 360, 1440)

    fun label(minutes: Int): String = when (minutes) {
        0 -> "Off"
        15 -> "15 minutes"
        60 -> "Hourly"
        360 -> "6 hours"
        1440 -> "Daily"
        else -> "$minutes minutes"
    }

    /** The job that should be queued, or null when none should. */
    fun wanted(configured: Boolean, automatic: Boolean, minutes: Int, wifiOnly: Boolean): SyncJobSpec? =
        if (!configured || !automatic || minutes <= 0) null else SyncJobSpec(maxOf(minutes, MIN_MINUTES) * 60_000L, wifiOnly)

    sealed interface Change {
        data object None : Change
        data object Cancel : Change
        data class Schedule(val spec: SyncJobSpec) : Change
    }

    /**
     * Queueing the job again restarts its timer, so it is only touched when what is wanted differs from what is
     * already queued. Otherwise opening the app often would keep pushing the next background sync back.
     */
    fun change(queued: SyncJobSpec?, wanted: SyncJobSpec?): Change = when {
        wanted == null -> if (queued == null) Change.None else Change.Cancel
        queued == wanted -> Change.None
        else -> Change.Schedule(wanted)
    }
}
