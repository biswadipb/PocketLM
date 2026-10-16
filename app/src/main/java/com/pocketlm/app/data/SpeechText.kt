package com.pocketlm.app.data

/** Prepares a Markdown reply for a text-to-speech voice, which would otherwise read out symbols. */
object SpeechText {

    fun clean(markdown: String): String {
        var t = markdown
        t = t.replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " Code block skipped. ")
        t = t.replace(Regex("```.*$", RegexOption.DOT_MATCHES_ALL), " Code block skipped. ") // unfinished fence
        t = t.replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        t = t.replace(Regex("""(?m)^\s{0,3}#{1,6}\s+"""), "")
        t = t.replace(Regex("""(?m)^\s*[-*+]\s+"""), "")
        t = t.replace(Regex("""(?m)^\s*(\d+)[.)]\s+"""), "$1. ")
        t = t.replace("**", "").replace("__", "").replace("`", "")
        t = t.replace(Regex("""(?<![\w*])\*(?=\S)([^*\n]+)\*(?![\w*])"""), "$1")
        return t.replace(Regex("""[ \t]+"""), " ").replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    /** Splits [text] into pieces of at most [maxChars], preferring paragraph and sentence breaks. */
    fun chunks(text: String, maxChars: Int = 3500): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) out += current.toString().trim()
            current.setLength(0)
        }
        for (piece in text.split(Regex("""(?<=[.!?])\s+|\n+"""))) {
            var p = piece
            while (p.length > maxChars) {
                flush()
                out += p.take(maxChars)
                p = p.drop(maxChars)
            }
            if (current.length + p.length + 1 > maxChars) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(p)
        }
        flush()
        return out
    }
}
