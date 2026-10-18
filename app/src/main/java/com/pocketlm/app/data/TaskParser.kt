package com.pocketlm.app.data

/** A scheduled task worked out from a sentence such as "Every weekday at 7:30, summarise the tech news". */
data class TaskDraft(
    val title: String,
    val prompt: String,
    val hour: Int,
    val minute: Int,
    val days: Int,
    /** False when the sentence said nothing about when, so the time and days are only the defaults. */
    val whenFound: Boolean,
    /** True when the task sounds like it needs the web or another tool, such as "news" or "weather". */
    val wantsTools: Boolean = false,
) {
    /** "Every weekday at 07:30", for showing back to the person. */
    val whenLabel: String get() = "${Schedules.dayLabel(days)} at ${Schedules.timeLabel(hour, minute)}"
}

/** Turns a described task into a schedule and a prompt, without asking a model. Pure, so it can be tested. */
object TaskParser {
    const val DEFAULT_HOUR = 8
    const val DEFAULT_MINUTE = 0
    private const val MAX_TITLE_CHARS = 40

    private val dayNames = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val dayPattern = dayNames.joinToString("|")
    private val opts = setOf(RegexOption.IGNORE_CASE)

    private val range = Regex("\\b(?:on\\s+)?($dayPattern)s?\\s*(?:-|–|to|through|until)\\s*($dayPattern)s?\\b", opts)
    // "every Monday", "on Fridays" or a plain plural such as "Mondays", then any more days joined with commas or "and".
    private val dayList = Regex(
        "\\b(?:(?:every|each|on)\\s+(?:$dayPattern)s?|(?:$dayPattern)s)(?:\\s*(?:,|and|&)\\s*(?:$dayPattern)s?)*\\b",
        opts,
    )
    private val weekdays = Regex("\\b(?:(?:every|each|on)\\s+)?(?:week\\s?days?|work\\s?days?|working days)\\b", opts)
    private val weekends = Regex("\\b(?:(?:every|each|on)\\s+)?weekends?\\b", opts)
    private val everyDay = Regex("\\b(?:(?:every|each)\\s+(?:single\\s+)?day|daily|everyday|nightly)\\b", opts)
    private val weekly = Regex("\\b(?:weekly|(?:every|each)\\s+week)\\b", opts)
    private val partOfDay = Regex("\\b(?:(?:every|each|in the|during the)\\s+)(morning|afternoon|evening|night|noon|midday|midnight)s?\\b", opts)
    private val bareNoon = Regex("\\bat\\s+(noon|midday|midnight)\\b", opts)

    private val needsTools = Regex(
        "\\b(news|headlines?|search|look up|latest|weather|forecast|temperature|online|web|price|prices|stock|stocks|market|wikipedia|convert|exchange rate)\\b",
        opts,
    )

    private val clockAt = Regex("\\bat\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?(?![\\d:])", opts)
    private val clockColon = Regex("\\b(\\d{1,2}):(\\d{2})\\s*(a\\.?m\\.?|p\\.?m\\.?)?(?![\\d:])", opts)
    private val clockMeridiem = Regex("\\b(\\d{1,2})\\s*(a\\.?m\\.?|p\\.?m\\.?)(?![a-z])", opts)

    fun parse(sentence: String): TaskDraft {
        var text = sentence.trim().replace(Regex("\\s+"), " ")

        // ---- days -------------------------------------------------------------
        var days = 0
        var daysFound = false
        fun take(regex: Regex, bits: (MatchResult) -> Int): Boolean {
            val m = regex.find(text) ?: return false
            days = days or bits(m)
            daysFound = true
            text = text.removeRange(m.range)
            return true
        }
        take(range) { m -> dayRange(m.groupValues[1], m.groupValues[2]) }
        take(weekdays) { Schedules.WEEKDAYS }
        take(weekends) { 0b1100000 }
        while (dayList.find(text) != null) take(dayList) { m -> bitsOfNames(m.value) }
        take(everyDay) { Schedules.ALL_DAYS }
        // "Weekly" with no day named means Mondays. The word itself is not part of the prompt.
        weekly.find(text)?.let { m ->
            if (!daysFound) {
                days = Schedules.dayBit(java.time.DayOfWeek.MONDAY)
                daysFound = true
            }
            text = text.removeRange(m.range)
        }

        // ---- time -------------------------------------------------------------
        var hour = DEFAULT_HOUR
        var minute = DEFAULT_MINUTE
        var timeFound = false
        var part: String? = null

        partOfDay.find(text)?.let { m ->
            part = m.groupValues[1].lowercase()
            val (h, mi) = when (part) {
                "morning" -> 8 to 0
                "afternoon" -> 15 to 0
                "evening" -> 18 to 0
                "night" -> 21 to 0
                "noon", "midday" -> 12 to 0
                else -> 0 to 0
            }
            hour = h; minute = mi; timeFound = true
            // "every morning" also means every day, unless days were named.
            if (!daysFound) { days = Schedules.ALL_DAYS; daysFound = true }
            text = text.removeRange(m.range)
        }
        bareNoon.find(text)?.let { m ->
            val (h, mi) = if (m.groupValues[1].equals("midnight", ignoreCase = true)) 0 to 0 else 12 to 0
            hour = h; minute = mi; timeFound = true
            text = text.removeRange(m.range)
        }

        val clock = clockAt.find(text) ?: clockColon.find(text) ?: clockMeridiem.find(text)
        if (clock != null) {
            val rawHour = clock.groupValues[1].toInt()
            val marker = clock.groupValues.drop(2).firstOrNull { it.startsWith("a", true) || it.startsWith("p", true) }.orEmpty()
                .lowercase().replace(".", "")
            val rawMinute = clock.groupValues.getOrNull(2)?.takeIf { it.length == 2 && it.all(Char::isDigit) }?.toInt() ?: 0
            val resolved = resolveHour(rawHour, marker, part)
            if (resolved != null && rawMinute in 0..59) {
                hour = resolved; minute = rawMinute; timeFound = true
                text = text.removeRange(clock.range)
            }
        }

        if (!daysFound) days = Schedules.ALL_DAYS

        val prompt = tidy(text)
        return TaskDraft(
            title = titleFor(prompt),
            prompt = prompt,
            hour = hour, minute = minute, days = days,
            whenFound = timeFound || daysFound,
            wantsTools = needsTools.containsMatchIn(prompt),
        )
    }

    /** The hour in 24-hour form, or null when it cannot be an hour. "7" after "every evening" means 19:00. */
    private fun resolveHour(hour: Int, marker: String, part: String?): Int? {
        if (marker.isNotEmpty()) {
            if (hour !in 1..12) return null
            return if (marker == "am") hour % 12 else hour % 12 + 12
        }
        if (hour !in 0..23) return null
        if (hour in 1..11 && (part == "evening" || part == "night" || part == "afternoon")) return hour + 12
        return hour
    }

    private fun dayRange(from: String, to: String): Int {
        val a = dayNames.indexOf(from.lowercase())
        val b = dayNames.indexOf(to.lowercase())
        var bits = 0
        var i = a
        while (true) {
            bits = bits or (1 shl i)
            if (i == b) break
            i = (i + 1) % 7
        }
        return bits
    }

    private fun bitsOfNames(text: String): Int {
        var bits = 0
        for ((i, name) in dayNames.withIndex()) if (Regex("\\b$name", opts).containsMatchIn(text)) bits = bits or (1 shl i)
        return bits
    }

    /** What is left of the sentence once the "when" is taken out: stray commas, "and", "please" and the like removed. */
    private fun tidy(raw: String): String {
        var s = raw.replace(Regex("\\s+"), " ").trim()
        val edge = Regex("^(?:[\\s,;:.\\-–—]+|(?:and|then|please|to|that|can you|could you|i want you to|i want to)\\b[\\s,]*)+", opts)
        s = edge.replaceFirst(s, "")
        s = s.replace(Regex("[\\s,;:\\-–—]+$"), "")
        s = s.replace(Regex("\\s+([,.;:!?])"), "$1")
        if (s.isEmpty()) return ""
        return s.replaceFirstChar { it.uppercase() }
    }

    private fun titleFor(prompt: String): String {
        val first = prompt.split(Regex("(?<=[.!?])\\s")).first().trimEnd('.', '!', '?')
        if (first.length <= MAX_TITLE_CHARS) return first
        val cut = first.take(MAX_TITLE_CHARS)
        return (cut.substringBeforeLast(' ', cut)).trimEnd(',', ';', ':', ' ') + "…"
    }
}

/** The schedule to edit and save for a described task or a template. */
fun TaskDraft.toSchedule(id: String) = ScheduledPrompt(
    id = id, title = title, prompt = prompt, hour = hour, minute = minute, days = days, tools = wantsTools,
)

fun TaskTemplate.toSchedule(id: String) = ScheduledPrompt(
    id = id, title = title, prompt = prompt, hour = hour, minute = minute, days = days,
    notes = notes, tools = needs.isNotEmpty(),
)
