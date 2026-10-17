package com.pocketlm.app.data

/** The user's text size choice and the screen width at which the two-pane layout appears. Pure, so it can be tested. */
object DisplayScale {
    const val MIN_TEXT = 0.85f
    const val MAX_TEXT = 1.5f
    const val STEP = 0.05f

    /** A text size in range and rounded to a step, so a slider and a saved value always agree. */
    fun snapText(value: Float): Float {
        val clamped = value.coerceIn(MIN_TEXT, MAX_TEXT)
        return (Math.round(clamped / STEP) * STEP).coerceIn(MIN_TEXT, MAX_TEXT)
    }

    fun label(scale: Float): String = "${Math.round(scale * 100)}%"

    /** Windows at least this wide (tablets, foldables, a phone on its side) show History beside the chat. */
    const val WIDE_DP = 840

    fun isWide(widthDp: Int): Boolean = widthDp >= WIDE_DP
}
