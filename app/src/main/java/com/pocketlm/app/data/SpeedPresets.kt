package com.pocketlm.app.data

import com.arm.aichat.GenerationParams

/** One-tap bundles of the settings that trade speed, battery and memory against each other. */
enum class SpeedPreset(val id: String, val label: String, val blurb: String) {
    BatterySaver("battery", "Battery saver", "Two threads, a short memory and short replies. Cooler and gentler on the battery, but slower."),
    Balanced("balanced", "Balanced", "The usual settings: a good middle for most phones."),
    Fast("fast", "Fast", "A smaller memory held in compact form, so each step has less to read. Quicker replies, but chats are cut off sooner."),
    LongChats("long", "Long chats", "A big memory held in compact form, and long replies. For documents and long conversations; needs more RAM.");
}

/** The settings a preset sets. Anything not listed here (temperature, GPU and so on) is left alone. */
data class SpeedValues(val threads: Int, val contextSize: Int, val cacheBits: Int, val maxReply: Int)

object SpeedPresets {

    fun values(preset: SpeedPreset): SpeedValues = when (preset) {
        SpeedPreset.BatterySaver -> SpeedValues(threads = 2, contextSize = 2048, cacheBits = 8, maxReply = 512)
        SpeedPreset.Balanced -> SpeedValues(threads = 0, contextSize = 4096, cacheBits = 16, maxReply = 1024)
        SpeedPreset.Fast -> SpeedValues(threads = 0, contextSize = 2048, cacheBits = 8, maxReply = 1024)
        SpeedPreset.LongChats -> SpeedValues(threads = 0, contextSize = 8192, cacheBits = 8, maxReply = 2048)
    }

    fun apply(params: GenerationParams, preset: SpeedPreset): GenerationParams {
        val v = values(preset)
        return params.copy(threads = v.threads, contextSize = v.contextSize, cacheBits = v.cacheBits)
    }

    /** The preset the current settings match exactly, or null when they are a custom mix. */
    fun detect(params: GenerationParams, maxReply: Int): SpeedPreset? =
        SpeedPreset.entries.firstOrNull { preset ->
            val v = values(preset)
            params.threads == v.threads && params.contextSize == v.contextSize && params.cacheBits == v.cacheBits && maxReply == v.maxReply
        }

    fun maxReplyFor(preset: SpeedPreset): Int = values(preset).maxReply
}
