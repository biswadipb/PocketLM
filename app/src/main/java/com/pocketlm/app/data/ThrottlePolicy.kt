package com.pocketlm.app.data

enum class ThrottleLevel { None, Light, Heavy }

/**
 * Decides how much to hold the model back when the phone is hot or low on battery: fewer threads, so it makes less
 * heat, and shorter replies, so it runs for less time. Pure, so the rules can be tested.
 */
object ThrottlePolicy {
    private const val LOW_BATTERY = 15
    private const val LOW_BATTERY_RECOVERED = 18
    private const val CRITICAL_BATTERY = 7
    private const val CRITICAL_BATTERY_RECOVERED = 10

    /**
     * The level for [status]. A level that is already in force is kept until the battery has recovered a few percent
     * past the limit that triggered it, so it does not flip on and off around the limit.
     */
    fun level(status: DeviceStatus, previous: ThrottleLevel = ThrottleLevel.None): ThrottleLevel {
        val heat = when {
            status.thermal >= 3 -> ThrottleLevel.Heavy
            status.thermal == 2 -> ThrottleLevel.Light
            else -> ThrottleLevel.None
        }
        val battery = when {
            status.charging -> ThrottleLevel.None
            status.batteryPercent <= (if (previous == ThrottleLevel.Heavy) CRITICAL_BATTERY_RECOVERED - 1 else CRITICAL_BATTERY) -> ThrottleLevel.Heavy
            status.batteryPercent <= (if (previous != ThrottleLevel.None) LOW_BATTERY_RECOVERED - 1 else LOW_BATTERY) -> ThrottleLevel.Light
            else -> ThrottleLevel.None
        }
        return maxOf(heat, battery)
    }

    /** How much of the usual thread count to use. */
    fun threadPercent(level: ThrottleLevel): Int = when (level) {
        ThrottleLevel.None -> 100
        ThrottleLevel.Light -> 70
        ThrottleLevel.Heavy -> 40
    }

    /** The longest reply to allow: the user's own limit, or less while throttled. */
    fun replyCap(requested: Int, level: ThrottleLevel): Int = when (level) {
        ThrottleLevel.None -> requested
        ThrottleLevel.Light -> minOf(requested, 768)
        ThrottleLevel.Heavy -> minOf(requested, 384)
    }

    /** What to tell the user, or null when nothing is held back. */
    fun note(level: ThrottleLevel, status: DeviceStatus): String? = when {
        level == ThrottleLevel.None -> null
        status.thermal >= 2 -> "Running slower with shorter replies while the phone cools down."
        else -> "Running slower with shorter replies to save battery."
    }
}
