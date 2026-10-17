package com.pocketlm.app.data

import com.arm.aichat.GpuDevice

/** Which graphics processor, if any, a model runs on. */
enum class GpuChoice(val id: String, val label: String) {
    Off("", "Off (CPU only)"),
    Auto("auto", "Automatic"),
    Vulkan("vulkan", "Vulkan (most phones)"),
    OpenCl("opencl", "OpenCL (Qualcomm Adreno)");

    companion object {
        fun fromId(id: String?): GpuChoice = entries.firstOrNull { it.id == id.orEmpty() } ?: Off
    }
}

/**
 * A faulty graphics driver can bring the whole app down with no chance to say so. So before a GPU is tried a flag is
 * saved, and it is cleared once the GPU has worked. If the app starts and finds the flag still set, the last attempt
 * ended the app, and the GPU is switched off until the user allows it again.
 */
class GpuGuard(private val flags: Flags) {

    /** A few saved yes/no values. Writes must reach storage before the call returns. */
    interface Flags {
        fun get(key: String): Boolean
        fun set(key: String, value: Boolean)
    }

    /** True while the GPU is switched off because of a crash. */
    val disabled: Boolean get() = flags.get(DISABLED)

    fun attemptStarting() = flags.set(PENDING, true)

    fun attemptFinished() = flags.set(PENDING, false)

    /** Call when the app starts. True if the previous run ended while a GPU was being tried; the GPU is then switched off. */
    fun recoverFromCrash(): Boolean {
        if (!flags.get(PENDING)) return false
        flags.set(PENDING, false)
        flags.set(DISABLED, true)
        return true
    }

    fun allowAgain() {
        flags.set(DISABLED, false)
        flags.set(PENDING, false)
    }

    private companion object {
        const val PENDING = "gpu_attempt_pending"
        const val DISABLED = "gpu_disabled_after_crash"
    }
}

/** "Vulkan: Mali-G615 (7.4 GB, shared with the rest of the phone)". */
fun GpuDevice.summary(): String =
    buildString {
        append(backend).append(": ").append(description.ifBlank { name })
        if (totalBytes > 0) append(" (").append(String.format("%.1f", totalBytes / 1_073_741_824.0)).append(" GB)")
    }
