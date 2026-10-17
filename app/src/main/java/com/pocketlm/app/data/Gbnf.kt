package com.pocketlm.app.data

/**
 * Checks a grammar typed by the user (llama.cpp's GBNF format) before it is handed to the model's sampler, so a typing
 * mistake is explained in plain words instead of being refused by the engine with a number. It is deliberately about
 * the mistakes people make: a missing `root`, a rule used but never written, a quote or bracket left open. The engine
 * still has the last word on anything subtler, such as a rule that calls itself before it reads anything.
 */
object Gbnf {
    const val MAX_CHARS = 20_000

    /** Longest repeat count llama.cpp accepts. */
    private const val MAX_REPEAT = 2_000

    /** A grammar to start from. */
    data class Example(val name: String, val summary: String, val grammar: String)

    val examples = listOf(
        Example("Yes or no", "One word: yes or no.", """root ::= "yes" | "no""""),
        Example("Pick one", "Exactly one of the words you list.", """root ::= "low" | "medium" | "high""""),
        Example(
            "Bullet list", "Three to six lines, each starting with a dash.",
            "root ::= item{3,6}\n" + """item ::= "- " [^\n]+ "\n"""",
        ),
        Example("Number and unit", "A number, then kg or lb.", """root ::= [0-9]+ ("." [0-9]+)? " " ("kg" | "lb")"""),
        Example("One sentence", "A single sentence of up to about 120 characters.", """root ::= [A-Z] [^.!?\n]{4,120} [.!?]"""),
    )

    private enum class Kind { Name, Define, Text, Set, Open, Close, Bar, Star, Plus, Maybe, Repeat, Any, Token, Not }

    private class Piece(val kind: Kind, val text: String, val line: Int)

    private class Problem(message: String) : Exception(message)

    /** Null when [text] looks like a usable grammar, otherwise a short explanation that names the line. */
    fun problem(text: String): String? {
        if (text.isBlank()) return "Enter a grammar, or turn it off."
        if (text.length > MAX_CHARS) return "The grammar is too long."
        return try {
            check(split(text))
            null
        } catch (e: Problem) {
            e.message
        }
    }

    private fun fail(line: Int, message: String): Nothing = throw Problem("Line $line: $message")

    private fun isNameChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-'

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** Reads one character of a quoted text or a character set starting at [at]; returns the index after it. */
    private fun skipChar(text: String, at: Int, line: Int): Int {
        if (text[at] != '\\') return at + 1
        val code = text.getOrNull(at + 1) ?: fail(line, "A backslash at the end of the grammar.")
        val digits = when (code) {
            'x' -> 2
            'u' -> 4
            'U' -> 8
            't', 'r', 'n', '\\', '"', '[', ']' -> return at + 2
            else -> fail(line, "Unknown escape \\$code. Use \\n, \\t, \\r, \\\\, \\\", \\[, \\], \\xHH, \\uHHHH or \\UHHHHHHHH.")
        }
        for (k in 1..digits) {
            if (!isHex(text.getOrNull(at + 1 + k) ?: ' ')) fail(line, "\\$code must be followed by $digits hexadecimal digits.")
        }
        return at + 2 + digits
    }

    private fun split(text: String): List<Piece> {
        val out = ArrayList<Piece>()
        var i = 0
        var line = 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\n' -> { line++; i++ }
                c == ' ' || c == '\t' || c == '\r' -> i++
                c == '#' -> while (i < text.length && text[i] != '\n') i++
                c == ':' -> {
                    if (!text.startsWith("::=", i)) fail(line, "Expected ::= after a rule name.")
                    out += Piece(Kind.Define, "::=", line); i += 3
                }
                c == '"' -> {
                    val start = line
                    var j = i + 1
                    while (true) {
                        val d = text.getOrNull(j)
                        if (d == null || d == '\n') fail(start, "A quoted text is not closed. Put the closing \" on the same line, and write a new line as \\n.")
                        if (d == '"') break
                        j = skipChar(text, j, line)
                    }
                    out += Piece(Kind.Text, text.substring(i + 1, j), start); i = j + 1
                }
                c == '[' -> {
                    val start = line
                    var j = i + 1
                    if (text.getOrNull(j) == '^') j++
                    var items = 0
                    while (true) {
                        val d = text.getOrNull(j)
                        if (d == null || d == '\n') fail(start, "A character set [ ] is not closed. Put the closing ] on the same line.")
                        if (d == ']') break
                        j = skipChar(text, j, line)
                        // A range such as a-z.
                        if (text.getOrNull(j) == '-' && text.getOrNull(j + 1).let { it != null && it != ']' && it != '\n' }) {
                            j = skipChar(text, j + 1, line)
                        }
                        items++
                    }
                    if (items == 0) fail(start, "This character set [ ] is empty.")
                    out += Piece(Kind.Set, text.substring(i + 1, j), start); i = j + 1
                }
                c == '(' -> { out += Piece(Kind.Open, "(", line); i++ }
                c == ')' -> { out += Piece(Kind.Close, ")", line); i++ }
                c == '|' -> { out += Piece(Kind.Bar, "|", line); i++ }
                c == '*' -> { out += Piece(Kind.Star, "*", line); i++ }
                c == '+' -> { out += Piece(Kind.Plus, "+", line); i++ }
                c == '?' -> { out += Piece(Kind.Maybe, "?", line); i++ }
                c == '.' -> { out += Piece(Kind.Any, ".", line); i++ }
                c == '!' -> { out += Piece(Kind.Not, "!", line); i++ }
                c == '<' -> {
                    val end = text.indexOf('>', i)
                    val newline = text.indexOf('\n', i).let { if (it < 0) Int.MAX_VALUE else it }
                    if (end < 0 || end > newline) fail(line, "A token reference < > is not closed.")
                    out += Piece(Kind.Token, text.substring(i + 1, end), line); i = end + 1
                }
                c == '{' -> {
                    val end = text.indexOf('}', i)
                    val newline = text.indexOf('\n', i).let { if (it < 0) Int.MAX_VALUE else it }
                    if (end < 0 || end > newline) fail(line, "A repeat count { } is not closed. Write it like {3}, {2,5} or {2,}.")
                    val body = text.substring(i + 1, end).replace(" ", "").replace("\t", "")
                    val match = Regex("""(\d+)(,(\d*))?""").matchEntire(body)
                        ?: fail(line, "A repeat count looks like {3}, {2,5} or {2,}.")
                    val numbers = listOf(match.groupValues[1], match.groupValues[3]).filter { it.isNotEmpty() }
                    if (numbers.any { it.length > 5 || it.toInt() > MAX_REPEAT }) fail(line, "A repeat count above $MAX_REPEAT is not allowed.")
                    if (numbers.size == 2 && numbers[0].toInt() > numbers[1].toInt()) fail(line, "The first number of {${body}} is larger than the second.")
                    out += Piece(Kind.Repeat, body, line); i = end + 1
                }
                isNameChar(c) -> {
                    var j = i
                    while (j < text.length && isNameChar(text[j])) j++
                    out += Piece(Kind.Name, text.substring(i, j), line); i = j
                }
                else -> fail(line, "Unexpected '$c'. Rules are written like  root ::= \"yes\" | \"no\"")
            }
        }
        return out
    }

    private fun check(pieces: List<Piece>) {
        if (pieces.isEmpty()) throw Problem("Enter a grammar, or turn it off.")
        if (pieces.size < 2 || pieces[0].kind != Kind.Name || pieces[1].kind != Kind.Define) {
            fail(pieces[0].line, "A grammar is a list of rules, each written like  name ::= what it may contain  (start with root).")
        }

        // A rule starts at a name that is followed by ::=.
        val heads = pieces.indices.filter { pieces[it].kind == Kind.Name && pieces.getOrNull(it + 1)?.kind == Kind.Define }
        val defined = HashMap<String, Int>()
        for (h in heads) {
            val name = pieces[h].text
            defined[name]?.let { fail(pieces[h].line, "The rule $name is already defined on line $it.") }
            defined[name] = pieces[h].line
        }
        if ("root" !in defined) throw Problem("There must be a rule called root, the one the whole reply has to match.")

        for ((n, h) in heads.withIndex()) {
            val end = heads.getOrNull(n + 1) ?: pieces.size
            val body = pieces.subList(h + 2, end)
            val name = pieces[h].text
            if (body.isEmpty()) fail(pieces[h].line, "The rule $name has nothing after ::=.")
            checkBody(name, body, defined)
        }
    }

    private fun checkBody(rule: String, body: List<Piece>, defined: Map<String, Int>) {
        var depth = 0
        var canRepeat = false
        var previous: Piece? = null
        for (p in body) {
            when (p.kind) {
                Kind.Name -> {
                    if (p.text !in defined) fail(p.line, "The rule ${p.text} is used in $rule but never defined.")
                    canRepeat = true
                }
                Kind.Text, Kind.Set, Kind.Any, Kind.Token -> canRepeat = true
                Kind.Open -> { depth++; canRepeat = false }
                Kind.Close -> {
                    if (depth == 0) fail(p.line, "A ) with no ( before it, in $rule.")
                    depth--
                    canRepeat = true
                }
                Kind.Bar -> canRepeat = false
                Kind.Star, Kind.Plus, Kind.Maybe, Kind.Repeat -> {
                    if (!canRepeat) fail(p.line, "Nothing to repeat before '${if (p.kind == Kind.Repeat) "{${p.text}}" else p.text}' in $rule.")
                    // A repeat can be followed by another one ("a+?" is not meant), but do not allow stacking silently.
                    canRepeat = false
                }
                Kind.Not -> canRepeat = false
                Kind.Define -> fail(p.line, "Unexpected ::= in $rule.")
            }
            previous = p
        }
        if (depth != 0) fail(previous?.line ?: 1, "A ( is never closed, in $rule.")
    }
}
