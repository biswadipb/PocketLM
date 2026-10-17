package com.pocketlm.app.data

/**
 * Preference names that are shared between the app screens and the background sync, which can run without any
 * screen. Only the settings that sync are listed here; the rest stay where they are used.
 */
object SettingKeys {
    const val PRESETS = "custom_presets"
    const val PRESET_ID = "selected_preset"
    const val TEMP = "gen_temperature"
    const val TOP_P = "gen_top_p"
    const val TOP_K = "gen_top_k"
    const val MIN_P = "gen_min_p"
    const val REPEAT = "gen_repeat_penalty"
    const val SEED = "gen_seed"
    const val STOP = "stop_words"
    const val PROMPTS = "saved_prompts"
    const val READ_ALOUD = "read_aloud"
    const val TOOLS = "enabled_tools"
    const val MAX_REPLY = "max_reply_tokens"
    const val NOTES = "memory_notes"
    const val SAVE_STATES = "save_chat_states"

    /**
     * The settings that sync when the user turns that on: what a person would want on every device. Per-phone
     * choices (theme, threads, context size, background behaviour), model settings and everything secret (API key,
     * app lock, hub and search addresses) stay out.
     */
    val SYNCED: Set<String> = setOf(
        TEMP, TOP_P, TOP_K, MIN_P, REPEAT, SEED, STOP, PRESETS, PRESET_ID, PROMPTS, TOOLS, READ_ALOUD, MAX_REPLY, NOTES, SAVE_STATES,
    )
}
