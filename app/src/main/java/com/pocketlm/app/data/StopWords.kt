package com.pocketlm.app.data

object StopWords {
    /** Parses the settings text (one stop word per line), ignoring blank lines. */
    fun parse(text: String): List<String> =
        text.split('\n').map { it.trimEnd('\r') }.filter { it.isNotBlank() }

    /** Index where the earliest stop word starts in [text], or -1 when none appears. */
    fun cutIndex(text: String, stops: List<String>): Int =
        stops.map { text.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1
}
