package com.pocketlm.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.math.MathContext
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** The helpers the model may use while answering. All are off until the user turns them on. */
enum class ToolId(val id: String, val label: String, val blurb: String, val describe: String) {
    Calculator(
        "calculator", "Calculator", "Does maths exactly, so the model does not have to guess.",
        "evaluates a maths expression such as (12+8)*3 or sqrt(16)",
    ),
    DateTime(
        "datetime", "Date and time", "Tells the model today's date and the time on your phone.",
        "tells today's date and the current time. The input can be empty",
    ),
    Wikipedia(
        "wikipedia", "Wikipedia", "Looks up facts on Wikipedia. Sends the search words to wikipedia.org.",
        "looks up a topic on Wikipedia and returns a short summary. The input is the topic",
    ),
    WebSearch(
        "websearch", "Web search", "Searches the web with the provider you choose below. Sends the search words to that provider.",
        "searches the web and returns the top results. The input is the search query",
    ),
}

data class ToolCall(val name: String, val input: String)

/** [modelText] goes back to the model; [shownText] is the short form shown in the chat. */
data class ToolOutcome(val modelText: String, val shownText: String)

object Tools {
    const val OPEN_TAG = "<tool>"
    const val CLOSE_TAG = "</tool>"
    const val MAX_ROUNDS = 3

    fun parseEnabled(csv: String?): Set<ToolId> =
        csv.orEmpty().split(',').mapNotNull { id -> ToolId.entries.firstOrNull { it.id == id.trim() } }.toSet()

    fun serialise(enabled: Set<ToolId>): String = enabled.joinToString(",") { it.id }

    /** Tells the model how to ask for a tool. Small models need an exact, short format. */
    fun instructions(enabled: Set<ToolId>): String {
        if (enabled.isEmpty()) return ""
        val list = ToolId.entries.filter { it in enabled }.joinToString("\n") { "- ${it.id}: ${it.describe}" }
        return "You can use tools. To use one, reply with only this one line and then stop:\n" +
            "$OPEN_TAG{\"name\":\"TOOL\",\"input\":\"INPUT\"}$CLOSE_TAG\n" +
            "Available tools:\n$list\n" +
            "When the tool result arrives, answer the user using it. Only use a tool when you need it."
    }

    fun withInstructions(system: String, enabled: Set<ToolId>): String =
        instructions(enabled).let { if (it.isEmpty()) system else "$system\n\n$it" }

    /** Finds the last tool request in [text], or null when there is none or it is not valid. */
    fun parseCall(text: String): ToolCall? {
        val start = text.lastIndexOf(OPEN_TAG)
        if (start < 0) return null
        val body = text.substring(start + OPEN_TAG.length).substringBefore(CLOSE_TAG)
        val json = firstJsonObject(body) ?: return null
        return runCatching {
            val o = JSONObject(json)
            val name = o.optString("name").trim().lowercase()
            if (name.isEmpty()) return null
            val input = when (val v = o.opt("input")) {
                is String -> v
                null, JSONObject.NULL -> o.optJSONObject("arguments")?.let { a -> a.keys().asSequence().map { a.optString(it) }.firstOrNull() }.orEmpty()
                else -> v.toString()
            }
            ToolCall(name, input.trim())
        }.getOrNull()
    }

    /** The first complete `{...}` in [s], respecting quotes. */
    private fun firstJsonObject(s: String): String? {
        val begin = s.indexOf('{')
        if (begin < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in begin until s.length) {
            val c = s[i]
            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return s.substring(begin, i + 1)
            }
        }
        return null
    }

    suspend fun run(
        call: ToolCall,
        enabled: Set<ToolId>,
        now: ZonedDateTime = ZonedDateTime.now(),
        search: SearchConfig = SearchConfig(SearchProvider.SearXNG),
    ): ToolOutcome {
        val tool = ToolId.entries.firstOrNull { it.id == call.name }
        if (tool == null || tool !in enabled) {
            return ToolOutcome("The tool '${call.name}' is not available.", "not available")
        }
        return try {
            when (tool) {
                ToolId.Calculator -> {
                    val value = Calculator.format(Calculator.evaluate(call.input))
                    ToolOutcome("The result of ${call.input} is $value.", value)
                }
                ToolId.DateTime -> {
                    val text = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm (VV)", Locale.ENGLISH))
                    ToolOutcome("The current date and time is $text.", text)
                }
                ToolId.WebSearch -> {
                    search.problem()?.let { return ToolOutcome("Web search is not set up: $it.", it) }
                    if (call.input.isBlank()) throw IllegalArgumentException("Nothing to search for")
                    val results = WebSearch.query(search, call.input)
                        ?: return ToolOutcome("The search found nothing for '${call.input}'.", "no results")
                    ToolOutcome("Search results for '${call.input}':\n$results", "${results.lines().count { it.isNotBlank() && it[0].isDigit() }} results")
                }
                ToolId.Wikipedia -> {
                    if (call.input.isBlank()) throw IllegalArgumentException("Nothing to look up")
                    val summary = Wikipedia.lookup(call.input)
                        ?: return ToolOutcome("Wikipedia has no article matching '${call.input}'.", "no article found")
                    ToolOutcome("Wikipedia says: $summary", summary.take(80).trimEnd() + if (summary.length > 80) "…" else "")
                }
            }
        } catch (e: Exception) {
            ToolOutcome("The tool failed: ${e.message ?: e::class.simpleName}.", "failed: ${e.message ?: "error"}")
        }
    }

    /** What to tell the model once a tool has answered. */
    fun followUp(call: ToolCall, outcome: ToolOutcome): String =
        "Tool result from ${call.name}: ${outcome.modelText}\n\nNow answer my earlier message using this result."

    /** The line that records the tool use in the chat. */
    fun statusLine(call: ToolCall, outcome: ToolOutcome): String {
        val label = ToolId.entries.firstOrNull { it.id == call.name }?.label ?: call.name
        val input = call.input.take(60).replace('`', '\'')
        return if (input.isBlank()) "*Used $label → ${outcome.shownText}*" else "*Used $label on `$input` → ${outcome.shownText}*"
    }
}

/** A small, safe maths evaluator: numbers, + - * / % ^, brackets, a few functions, pi and e. */
object Calculator {

    fun evaluate(expression: String): Double {
        val text = expression.lowercase()
            .replace('×', '*').replace('÷', '/').replace("**", "^").replace(" ", "")
        if (text.isEmpty()) throw IllegalArgumentException("Empty expression")
        val parser = Parser(text)
        val value = parser.parseExpression()
        parser.expectEnd()
        if (value.isNaN() || value.isInfinite()) throw IllegalArgumentException("The result is not a number")
        return value
    }

    /** Whole numbers without a decimal point, others to ten significant digits. */
    fun format(value: Double): String {
        if (value == floor(value) && abs(value) < 1e15) return value.roundToLong().toString()
        return BigDecimal(value).round(MathContext(10)).stripTrailingZeros().toPlainString()
    }

    private class Parser(private val s: String) {
        private var pos = 0
        private var depth = 0

        fun expectEnd() {
            if (pos != s.length) throw IllegalArgumentException("Unexpected '${s[pos]}'")
        }

        fun parseExpression(): Double {
            var left = parseTerm()
            while (pos < s.length && (s[pos] == '+' || s[pos] == '-')) {
                val op = s[pos++]
                val right = parseTerm()
                left = if (op == '+') left + right else left - right
            }
            return left
        }

        private fun parseTerm(): Double {
            var left = parsePower()
            while (pos < s.length && (s[pos] == '*' || s[pos] == '/' || s[pos] == '%')) {
                val op = s[pos++]
                val right = parsePower()
                left = when (op) {
                    '*' -> left * right
                    '/' -> if (right == 0.0) throw IllegalArgumentException("Division by zero") else left / right
                    else -> if (right == 0.0) throw IllegalArgumentException("Division by zero") else left % right
                }
            }
            return left
        }

        private fun parsePower(): Double {
            val base = parseUnary()
            if (pos < s.length && s[pos] == '^') {
                pos++
                return base.pow(parsePower()) // right to left: 2^3^2 = 2^(3^2)
            }
            return base
        }

        private fun parseUnary(): Double {
            if (pos < s.length && s[pos] == '-') { pos++; return -parseUnary() }
            if (pos < s.length && s[pos] == '+') { pos++; return parseUnary() }
            return parsePrimary()
        }

        private fun parsePrimary(): Double {
            if (++depth > MAX_DEPTH) throw IllegalArgumentException("Expression is too deeply nested")
            try {
                if (pos >= s.length) throw IllegalArgumentException("The expression ends too soon")
                val c = s[pos]
                if (c == '(') {
                    pos++
                    val inner = parseExpression()
                    if (pos >= s.length || s[pos] != ')') throw IllegalArgumentException("Missing closing bracket")
                    pos++
                    return inner
                }
                if (c.isDigit() || c == '.') return parseNumber()
                if (c.isLetter()) return parseNameOrFunction()
                throw IllegalArgumentException("Unexpected '$c'")
            } finally {
                depth--
            }
        }

        private fun parseNumber(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (pos < s.length && s[pos] == 'e' && pos + 1 < s.length && (s[pos + 1].isDigit() || s[pos + 1] in "+-")) {
                pos++
                if (s[pos] in "+-") pos++
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            return s.substring(start, pos).toDoubleOrNull() ?: throw IllegalArgumentException("Bad number '${s.substring(start, pos)}'")
        }

        private fun parseNameOrFunction(): Double {
            val start = pos
            while (pos < s.length && s[pos].isLetter()) pos++
            val name = s.substring(start, pos)
            if (pos < s.length && s[pos] == '(') {
                pos++
                val arg = parseExpression()
                if (pos >= s.length || s[pos] != ')') throw IllegalArgumentException("Missing closing bracket")
                pos++
                return when (name) {
                    "sqrt" -> if (arg < 0) throw IllegalArgumentException("Square root of a negative number") else sqrt(arg)
                    "abs" -> abs(arg)
                    "sin" -> sin(arg)
                    "cos" -> cos(arg)
                    "tan" -> tan(arg)
                    "ln" -> ln(arg)
                    "log" -> log10(arg)
                    "exp" -> exp(arg)
                    "floor" -> floor(arg)
                    "ceil" -> ceil(arg)
                    "round" -> arg.roundToLong().toDouble()
                    else -> throw IllegalArgumentException("Unknown function '$name'")
                }
            }
            return when (name) {
                "pi" -> PI
                "e" -> Math.E
                else -> throw IllegalArgumentException("Unknown name '$name'")
            }
        }
    }

    private const val MAX_DEPTH = 200
}

/** Wikipedia's public search API: the intro of the best matching article. */
object Wikipedia {
    private const val BASE = "https://en.wikipedia.org/w/api.php"

    suspend fun lookup(query: String): String? = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        parse(
            Http.request(
                "$BASE?action=query&format=json&generator=search&gsrsearch=$q&gsrlimit=1" +
                    "&prop=extracts&exintro=1&explaintext=1&redirects=1"
            )
        )
    }

    /** "Title: intro text", cut to a length a small model can use, or null when nothing matched. */
    fun parse(json: String, maxChars: Int = 1500): String? {
        val pages = JSONObject(json).optJSONObject("query")?.optJSONObject("pages") ?: return null
        val best = pages.keys().asSequence().map { pages.getJSONObject(it) }.minByOrNull { it.optInt("index", Int.MAX_VALUE) }
            ?: return null
        val extract = best.optString("extract").trim()
        if (extract.isEmpty()) return null
        val text = "${best.optString("title")}: $extract"
        return if (text.length <= maxChars) text else text.take(maxChars).trimEnd() + "…"
    }
}

/** Web search through a SearXNG server (https://docs.searxng.org), which must have the JSON format enabled. */
