package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** A prompt the person asked PocketLM to run by itself at a set time, such as a morning summary of their notes. */
data class ScheduledPrompt(
    val id: String,
    val title: String,
    val prompt: String,
    val hour: Int,
    val minute: Int,
    /** Days it runs on: bit 0 is Monday, bit 6 is Sunday. See [Schedules.ALL_DAYS]. */
    val days: Int = Schedules.ALL_DAYS,
    val notes: NotesUse = NotesUse.None,
    val enabled: Boolean = true,
    /** The model to use, or null for the one used last. */
    val model: String? = null,
    /** When it last ran (or was made, if it has not run yet). The next run is the first time after this. */
    val lastRun: Long = 0,
    /** How the last run went, shown in Settings. */
    val lastNote: String = "",
)

object Schedules {
    const val ALL_DAYS = 0b1111111
    const val WEEKDAYS = 0b0011111
    const val MAX_SCHEDULES = 10
    const val MAX_PROMPT_CHARS = 4_000

    /** Android runs a job somewhere inside a window; this is how long after the chosen time it may start. */
    const val WINDOW_MS = 20 * 60_000L

    private val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun dayBit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    fun runsOn(days: Int, day: DayOfWeek): Boolean = (days and dayBit(day)) != 0

    /** The first moment strictly after [after] at which [schedule] should run, or null if no day is chosen. */
    fun next(schedule: ScheduledPrompt, after: Instant, zone: ZoneId): ZonedDateTime? {
        if ((schedule.days and ALL_DAYS) == 0) return null
        val start = after.atZone(zone)
        for (offset in 0L..7L) {
            val date = start.toLocalDate().plusDays(offset)
            if (!runsOn(schedule.days, date.dayOfWeek)) continue
            // Resolving in the zone keeps the wall-clock time right across a change of summer time.
            val candidate = date.atTime(schedule.hour, schedule.minute).atZone(zone)
            if (candidate.toInstant().isAfter(after)) return candidate
        }
        return null
    }

    /** The schedules whose time has come: switched on, and a run time has passed since they last ran. */
    fun due(all: List<ScheduledPrompt>, now: Long, zone: ZoneId): List<ScheduledPrompt> = all.filter { s ->
        s.enabled && next(s, Instant.ofEpochMilli(s.lastRun), zone)?.toInstant()?.toEpochMilli()?.let { it <= now } == true
    }

    /** When the next run is wanted, as milliseconds since 1970, or null if nothing is switched on. */
    fun nextWanted(all: List<ScheduledPrompt>, zone: ZoneId): Long? =
        all.filter { it.enabled }.mapNotNull { s -> next(s, Instant.ofEpochMilli(s.lastRun), zone)?.toInstant()?.toEpochMilli() }.minOrNull()

    fun timeLabel(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

    fun dayLabel(days: Int): String = when (days and ALL_DAYS) {
        ALL_DAYS -> "Every day"
        WEEKDAYS -> "Weekdays"
        0b1100000 -> "Weekends"
        0 -> "No days"
        else -> names.filterIndexed { i, _ -> (days and (1 shl i)) != 0 }.joinToString(", ")
    }

    /** Why a schedule cannot be saved as it is, or null. */
    fun problem(s: ScheduledPrompt): String? = when {
        s.title.isBlank() -> "Give it a name."
        s.prompt.isBlank() -> "Write the prompt to run."
        s.prompt.length > MAX_PROMPT_CHARS -> "The prompt is longer than $MAX_PROMPT_CHARS characters."
        s.hour !in 0..23 || s.minute !in 0..59 -> "The time must be between 00:00 and 23:59."
        (s.days and ALL_DAYS) == 0 -> "Choose at least one day."
        else -> null
    }

    fun toJson(all: List<ScheduledPrompt>): String = JSONArray(all.map {
        JSONObject()
            .put("id", it.id).put("title", it.title).put("prompt", it.prompt)
            .put("hour", it.hour).put("minute", it.minute).put("days", it.days)
            .put("notes", it.notes.key).put("enabled", it.enabled)
            .put("model", it.model ?: JSONObject.NULL)
            .put("lastRun", it.lastRun).put("lastNote", it.lastNote)
    }).toString()

    fun fromJson(text: String?): List<ScheduledPrompt> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val schedule = ScheduledPrompt(
                    id = o.optString("id").ifEmpty { return@mapNotNull null },
                    title = o.optString("title"),
                    prompt = o.optString("prompt"),
                    hour = o.optInt("hour", 8),
                    minute = o.optInt("minute", 0),
                    days = o.optInt("days", ALL_DAYS),
                    notes = NotesUse.of(o.optString("notes", "none")),
                    enabled = o.optBoolean("enabled", true),
                    model = o.optString("model", "").ifEmpty { null },
                    lastRun = o.optLong("lastRun", 0),
                    lastNote = o.optString("lastNote", ""),
                )
                schedule.takeIf { problem(it) == null }
            }.take(MAX_SCHEDULES)
        }.getOrDefault(emptyList())
    }
}
