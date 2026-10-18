package com.pocketlm.app.data

/** Battery and heat state of the phone, used to warn before a long reply drains or overheats it. */
data class DeviceStatus(
    val batteryPercent: Int = 100,
    val charging: Boolean = true,
    /** Android's thermal status: 0 none, 1 light, 2 moderate, 3 severe, 4 and up critical. */
    val thermal: Int = 0,
) {
    val warning: String?
        get() = when {
            thermal >= 3 -> "Your phone is very hot. Pause for a while; replies will be slow and it may shut apps down."
            thermal == 2 -> "Your phone is getting warm. Replies may slow down; a short break helps."
            batteryPercent <= LOW_BATTERY && !charging ->
                "Battery is at $batteryPercent%. Generating replies uses a lot of power."
            else -> null
        }

    companion object {
        const val LOW_BATTERY = 15
    }
}
