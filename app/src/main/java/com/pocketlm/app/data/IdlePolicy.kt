package com.pocketlm.app.data

/** Decides when a loaded model has been idle long enough to free its memory. */
object IdlePolicy {

    /** The choices offered in Settings, in minutes. 0 means never. */
    val choices = listOf(0, 5, 15, 30, 60)

    fun label(minutes: Int) = when (minutes) {
        0 -> "Never"
        60 -> "After 1 hour"
        else -> "After $minutes minutes"
    }

    fun shouldSleep(
        nowMs: Long,
        lastActiveMs: Long,
        timeoutMs: Long,
        busy: Boolean,
        hasModel: Boolean,
        asleep: Boolean,
    ): Boolean = timeoutMs > 0 && hasModel && !asleep && !busy && nowMs - lastActiveMs >= timeoutMs
}
