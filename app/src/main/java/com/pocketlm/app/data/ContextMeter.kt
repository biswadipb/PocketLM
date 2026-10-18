package com.pocketlm.app.data

import com.arm.aichat.ContextUsage

/** Turns the engine's memory use into a bar and a sentence. */
object ContextMeter {

    enum class Level { Fine, Filling, Full }

    fun fraction(usage: ContextUsage): Float =
        if (usage.total <= 0) 0f else (usage.used.toFloat() / usage.total).coerceIn(0f, 1f)

    fun level(usage: ContextUsage): Level = when {
        fraction(usage) >= 0.9f -> Level.Full
        fraction(usage) >= 0.7f -> Level.Filling
        else -> Level.Fine
    }

    fun label(usage: ContextUsage): String = "%,d of %,d tokens of memory used".format(usage.used, usage.total)

    fun warning(usage: ContextUsage): String? = when (level(usage)) {
        Level.Full -> "Nearly full: the oldest messages will be dropped to make room. Start a new chat for a fresh start."
        Level.Filling -> "Filling up. Long chats slow down and eventually lose their oldest messages."
        Level.Fine -> null
    }
}
