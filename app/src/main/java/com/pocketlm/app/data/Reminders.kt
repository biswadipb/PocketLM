package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Something the person asked to be reminded of. [at] is when, in milliseconds since 1970. */
data class Reminder(val id: Int, val at: Long, val text: String)

/** What the phone does with reminders. The model's reminder tool talks to this, so tests can stand in for the phone. */
interface ReminderBook {
    fun pending(): List<Reminder>

    fun add(at: Long, text: String): Reminder

    /** False when Android only lets reminders fire roughly on time (the person has not allowed exact alarms). */
    val exact: Boolean

    /** False when notifications are off for the app, so a reminder would go off without being seen. */
    val notificationsAllowed: Boolean
}

/** When a reminder should fire and what it says, worked out from the words the model wrote. */
data class ParsedReminder(val at: ZonedDateTime, val text: String)

object ReminderParser {
    const val MAX_TEXT_CHARS = 140
    const val MAX_PENDING = 20
    val MAX_AHEAD: Duration = Duration.ofDays(366)

    const val USAGE = "Write when, then a colon, then what to be reminded of, for example " +
        "\"in 10 minutes: take the pasta off\", \"at 18:30: call Sam\", \"tomorrow at 9:00: dentist\" or \"2026-10-20 09:00: renew passport\""

    private val unitSeconds = mapOf('s' to 1L, 'm' to 60L, 'h' to 3600L, 'd' to 86_400L, 'w' to 604_800L)

    private val leadIn = Regex("^(?:remind me|set (?:a )?(?:reminder|timer)|reminder|timer)\\b\\s*(?:for\\s+|:\\s*)?", RegexOption.IGNORE_CASE)
    private val durationStart = Regex("^in\\s+", RegexOption.IGNORE_CASE)
    private val durationPart = Regex(
        "^(half an?|an?|\\d+(?:[.,]\\d+)?)\\s*(seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|hr|h|days?|d|weeks?|w)(?![a-z])(?:\\s*(?:and|,|&)\\s*|\\s+)?",
        RegexOption.IGNORE_CASE,
    )
    private val isoDate = Regex("^(\\d{4})-(\\d{2})-(\\d{2})(?:[ T]+(\\d{1,2}):(\\d{2}))?", RegexOption.IGNORE_CASE)
    private val dayWord = Regex(
        "^(?:on\\s+)?(?:(today|tomorrow)|(?:next\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues|tue|wed|thurs|thur|thu|fri|sat|sun))(?![a-z])\\s*(?:,|at|@)?\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val clock = Regex(
        "^(?:at\\s+|@\\s*)?(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?(?!\\d)",
        RegexOption.IGNORE_CASE,
    )
    private val separator = Regex("^\\s*[:,\\-–—|]?\\s*")
    private val toWord = Regex("^(?:to|that|about)\\s+(?=\\S)", RegexOption.IGNORE_CASE)

    fun parse(input: String, now: ZonedDateTime): ParsedReminder {
        var rest = input.trim()
        if (rest.isEmpty()) throw IllegalArgumentException(USAGE)
        rest = leadIn.replaceFirst(rest, "").trim()

        val startsWithIn = durationStart.find(rest)
        val bareDuration = startsWithIn == null && rest.firstOrNull()?.isDigit() == true && durationPart.containsMatchIn(rest)
        val at: ZonedDateTime
        if (startsWithIn != null || bareDuration) {
            val body = if (startsWithIn != null) rest.substring(startsWithIn.range.last + 1) else rest
            val parsed = takeDuration(body) ?: throw IllegalArgumentException("Could not read how long to wait. $USAGE")
            at = now.plus(parsed.first)
            rest = parsed.second
        } else {
            val iso = isoDate.find(rest)
            if (iso != null) {
                val (y, mo, d) = listOf(1, 2, 3).map { iso.groupValues[it].toInt() }
                val date = try {
                    LocalDate.of(y, mo, d)
                } catch (e: DateTimeException) {
                    throw IllegalArgumentException("${iso.groupValues[0].take(10)} is not a real date.")
                }
                at = atTime(date, iso.groupValues[4].ifEmpty { "9" }.toInt(), iso.groupValues[5].ifEmpty { "0" }.toInt(), now.zone)
                rest = rest.substring(iso.range.last + 1)
            } else {
                val today = now.toLocalDate()
                val word = dayWord.find(rest)
                if (word != null) rest = rest.substring(word.range.last + 1)
                val time = clock.find(rest)
                // A bare number such as "10" is not a time unless something says it is one.
                val explicit = time != null && (word != null || rest.startsWith("at", ignoreCase = true) || rest.startsWith("@") ||
                    time.groupValues[2].isNotEmpty() || time.groupValues[3].isNotEmpty())
                if (time == null || !explicit) throw IllegalArgumentException("Could not read the time. $USAGE")
                val hour = to24(time.groupValues[1].toInt(), time.groupValues[3])
                val minute = time.groupValues[2].ifEmpty { "0" }.toInt()
                rest = rest.substring(time.range.last + 1)

                val keyword = word?.groupValues?.get(1)?.lowercase().orEmpty()
                val weekday = word?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }
                    ?.let { name -> DayOfWeek.entries.first { it.name.startsWith(name.take(3).uppercase()) } }
                at = when {
                    keyword == "today" -> atTime(today, hour, minute, now.zone)
                    keyword == "tomorrow" -> atTime(today.plusDays(1), hour, minute, now.zone)
                    weekday != null -> {
                        // The next such weekday, today included if that time is still to come.
                        var day = today
                        while (day.dayOfWeek != weekday || !atTime(day, hour, minute, now.zone).isAfter(now)) day = day.plusDays(1)
                        atTime(day, hour, minute, now.zone)
                    }
                    // No day given: today if that time is still to come, otherwise tomorrow.
                    else -> atTime(today, hour, minute, now.zone).let {
                        if (it.isAfter(now)) it else atTime(today.plusDays(1), hour, minute, now.zone)
                    }
                }
            }
        }

        val text = tidy(rest)
        if (text.isEmpty()) throw IllegalArgumentException("What should the reminder say? $USAGE")
        if (!at.isAfter(now)) throw IllegalArgumentException("That time has already passed.")
        if (Duration.between(now, at) > MAX_AHEAD) throw IllegalArgumentException("Reminders can be at most a year ahead.")
        return ParsedReminder(at, text)
    }

    /** Reads "10 minutes", "1h30m" or "an hour and 5 minutes" from the start of [text]. Returns the wait and what follows. */
    private fun takeDuration(text: String): Pair<Duration, String>? {
        var rest = text
        var total = Duration.ZERO
        var parts = 0
        while (true) {
            val m = durationPart.find(rest) ?: break
            val amount = when (val n = m.groupValues[1].lowercase()) {
                "a", "an" -> 1.0
                "half a", "half an" -> 0.5
                else -> n.replace(',', '.').toDoubleOrNull() ?: return null
            }
            val unit = unitSeconds[m.groupValues[2].lowercase()[0]] ?: return null
            total = total.plusSeconds((amount * unit).toLong())
            parts++
            rest = rest.substring(m.range.last + 1)
        }
        if (parts == 0 || total.isZero) return null
        return total to rest
    }

    private fun to24(hour: Int, marker: String): Int {
        val m = marker.lowercase().replace(".", "")
        if (m.isEmpty()) {
            if (hour > 23) throw IllegalArgumentException("$hour is not an hour of the day.")
            return hour
        }
        if (hour !in 1..12) throw IllegalArgumentException("$hour $m is not a time.")
        return if (m == "am") hour % 12 else hour % 12 + 12
    }

    private fun atTime(date: LocalDate, hour: Int, minute: Int, zone: ZoneId): ZonedDateTime {
        if (hour !in 0..23 || minute !in 0..59) throw IllegalArgumentException("$hour:${minute.toString().padStart(2, '0')} is not a time.")
        return ZonedDateTime.of(date, LocalTime.of(hour, minute), zone)
    }

    private fun tidy(raw: String): String {
        var text = separator.replaceFirst(raw, "")
        text = toWord.replaceFirst(text, "")
        return text.replace(Regex("\\s+"), " ").trim().take(MAX_TEXT_CHARS).trim()
    }
}

object Reminders {
    fun toJson(list: List<Reminder>): String =
        JSONArray(list.map { JSONObject().put("id", it.id).put("at", it.at).put("text", it.text) }).toString()

    fun fromJson(json: String?): List<Reminder> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                Reminder(o.getInt("id"), o.getLong("at"), o.getString("text"))
            }.sortedBy { it.at }
        }.getOrDefault(emptyList())
    }

    /** [list] with one more reminder. Throws when the limit is reached. */
    fun add(list: List<Reminder>, at: Long, text: String): Pair<List<Reminder>, Reminder> {
        if (list.size >= ReminderParser.MAX_PENDING) {
            throw IllegalArgumentException("There are already ${ReminderParser.MAX_PENDING} reminders waiting. Ask the user to delete some in Settings.")
        }
        val reminder = Reminder((list.maxOfOrNull { it.id } ?: 0) + 1, at, text)
        return (list + reminder).sortedBy { it.at } to reminder
    }

    fun remove(list: List<Reminder>, id: Int): List<Reminder> = list.filter { it.id != id }

    /** "today at 18:30", "tomorrow at 09:00" or "Mon 20 Oct at 09:00". */
    fun describe(at: Instant, now: Instant, zone: ZoneId): String {
        val time = at.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val clock = time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))
        return when (time.toLocalDate()) {
            today -> "today at $clock"
            today.plusDays(1) -> "tomorrow at $clock"
            else -> time.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)) +
                (if (time.year != today.year) " ${time.year}" else "") + " at $clock"
        }
    }
}
