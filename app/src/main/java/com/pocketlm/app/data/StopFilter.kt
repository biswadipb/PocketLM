package com.pocketlm.app.data

/**
 * Cuts a streamed reply at the first stop sequence. Text that might be the start of a stop sequence
 * is held back until the next piece shows whether it is, so a stop word is never partly sent.
 */
class StopFilter(stops: List<String>) {
    private val stops = stops.filter { it.isNotEmpty() }
    private val pending = StringBuilder()

    /** True once a stop sequence has been found; everything after it is dropped. */
    var finished = false
        private set

    /** Takes the next piece and returns the text that is safe to send now. */
    fun feed(piece: String): String {
        if (finished) return ""
        pending.append(piece)
        val text = pending.toString()

        val hit = stops.map { text.indexOf(it) }.filter { it >= 0 }.minOrNull()
        if (hit != null) {
            finished = true
            pending.clear()
            return text.substring(0, hit)
        }

        // The longest tail of the text that could be the beginning of some stop sequence stays pending.
        val hold = stops.maxOfOrNull { stop ->
            (stop.length - 1 downTo 1).firstOrNull { k -> text.endsWith(stop.substring(0, k)) } ?: 0
        } ?: 0
        val send = text.length - hold
        pending.delete(0, send)
        return text.substring(0, send)
    }

    /** At the end of the reply, whatever was held back turned out not to be a stop sequence. */
    fun flush(): String = if (finished) "" else pending.toString().also { pending.clear() }
}
