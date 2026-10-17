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

    private val sentenceBreak = Regex("""(?<=[.!?…。！？])\s+|\n+""")
    private val softBreak = Regex("""(?<=[,;:—–])\s+""")

    /**
     * Sentences for a voice that speaks one at a time: none longer than [maxChars] (a long one is cut at a comma, or
     * between words), and a very short one (under [minChars]) joined to the next so it is not spoken on its own.
     */
    fun sentences(text: String, maxChars: Int = 200, minChars: Int = 24): List<String> {
        val pieces = ArrayList<String>()
        for (sentence in text.split(sentenceBreak).map { it.trim() }.filter { it.isNotEmpty() }) {
            if (sentence.length <= maxChars) {
                pieces += sentence
                continue
            }
            val current = StringBuilder()
            for (part in sentence.split(softBreak)) {
                for (word in part.split(' ').filter { it.isNotEmpty() }.flatMap { it.chunked(maxChars) }) {
                    if (current.isNotEmpty() && current.length + 1 + word.length > maxChars) {
                        pieces += current.toString()
                        current.setLength(0)
                    }
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(word)
                }
                // Prefer to end a piece at a comma if it is already long enough to stand alone.
                if (current.length >= maxChars / 2) {
                    pieces += current.toString()
                    current.setLength(0)
                }
            }
            if (current.isNotEmpty()) pieces += current.toString()
        }
        val joined = ArrayList<String>()
        var carry = ""
        for (piece in pieces) {
            val candidate = if (carry.isEmpty()) piece else "$carry $piece"
            if (candidate.length < minChars) {
                carry = candidate
            } else {
                joined += candidate
                carry = ""
            }
        }
        if (carry.isNotEmpty()) {
            if (joined.isNotEmpty() && joined.last().length + 1 + carry.length <= maxChars) joined[joined.lastIndex] = joined.last() + " " + carry
            else joined += carry
        }
        return joined
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
