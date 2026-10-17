package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class SchedulesTest {

    private val utc = ZoneId.of("UTC")

    private fun at(text: String): Instant = Instant.parse(text)

    private fun schedule(
        hour: Int = 8, minute: Int = 0, days: Int = Schedules.ALL_DAYS, lastRun: Long = 0, enabled: Boolean = true, id: String = "s",
    ) = ScheduledPrompt(id, "Morning", "Summarise my notes", hour, minute, days, lastRun = lastRun, enabled = enabled)

    @Test
    fun theNextRunIsTodayIfTheTimeHasNotCome() {
        val next = Schedules.next(schedule(), at("2026-10-17T07:00:00Z"), utc)!!
        assertEquals(at("2026-10-17T08:00:00Z"), next.toInstant())
    }

    @Test
    fun theNextRunIsStrictlyAfterTheMoment() {
        assertEquals(at("2026-10-18T08:00:00Z"), Schedules.next(schedule(), at("2026-10-17T08:00:00Z"), utc)!!.toInstant())
        assertEquals(at("2026-10-18T08:00:00Z"), Schedules.next(schedule(), at("2026-10-17T09:30:00Z"), utc)!!.toInstant())
    }

    @Test
    fun onlyTheChosenDaysCount() {
        // 17 October 2026 is a Saturday.
        val weekdays = schedule(days = Schedules.WEEKDAYS)
        assertEquals(DayOfWeek.MONDAY, Schedules.next(weekdays, at("2026-10-17T07:00:00Z"), utc)!!.dayOfWeek)
        assertEquals(at("2026-10-19T08:00:00Z"), Schedules.next(weekdays, at("2026-10-17T07:00:00Z"), utc)!!.toInstant())
        val onlyWednesday = schedule(days = Schedules.dayBit(DayOfWeek.WEDNESDAY))
        assertEquals(at("2026-10-21T08:00:00Z"), Schedules.next(onlyWednesday, at("2026-10-17T07:00:00Z"), utc)!!.toInstant())
        // The same weekday, later: a week away.
        assertEquals(at("2026-10-28T08:00:00Z"), Schedules.next(onlyWednesday, at("2026-10-21T08:00:00Z"), utc)!!.toInstant())
        assertNull(Schedules.next(schedule(days = 0), at("2026-10-17T07:00:00Z"), utc))
    }

    @Test
    fun theWallClockTimeIsKeptAcrossAChangeOfSummerTime() {
        val london = ZoneId.of("Europe/London")
        // Clocks go back on 25 October 2026.
        val next = Schedules.next(schedule(), at("2026-10-24T07:30:00Z"), london)!!
        assertEquals(ZonedDateTime.of(2026, 10, 25, 8, 0, 0, 0, london), next)
        assertEquals(at("2026-10-25T08:00:00Z"), next.toInstant())
    }

    @Test
    fun aScheduleIsDueOnceItsTimeHasPassedSinceItLastRan() {
        val lastRun = at("2026-10-16T08:00:05Z").toEpochMilli()
        val now = at("2026-10-17T08:10:00Z").toEpochMilli()
        assertEquals(listOf("s"), Schedules.due(listOf(schedule(lastRun = lastRun)), now, utc).map { it.id })
        // Before the time: not yet.
        assertTrue(Schedules.due(listOf(schedule(lastRun = lastRun)), at("2026-10-17T07:59:00Z").toEpochMilli(), utc).isEmpty())
        // Already ran after today's time.
        assertTrue(Schedules.due(listOf(schedule(lastRun = at("2026-10-17T08:05:00Z").toEpochMilli())), now, utc).isEmpty())
        // Switched off.
        assertTrue(Schedules.due(listOf(schedule(lastRun = lastRun, enabled = false)), now, utc).isEmpty())
    }

    @Test
    fun aPhoneThatWasOffForDaysCatchesUpOnce() {
        val lastRun = at("2026-10-10T08:00:00Z").toEpochMilli()
        val now = at("2026-10-17T12:00:00Z").toEpochMilli()
        assertEquals(1, Schedules.due(listOf(schedule(lastRun = lastRun)), now, utc).size)
    }

    @Test
    fun theEarliestSwitchedOnScheduleDecidesWhenToWake() {
        val early = schedule(hour = 7, id = "early", lastRun = at("2026-10-17T00:00:00Z").toEpochMilli())
        val late = schedule(hour = 20, id = "late", lastRun = at("2026-10-17T00:00:00Z").toEpochMilli())
        val off = schedule(hour = 6, id = "off", enabled = false, lastRun = at("2026-10-17T00:00:00Z").toEpochMilli())
        assertEquals(at("2026-10-17T07:00:00Z").toEpochMilli(), Schedules.nextWanted(listOf(late, off, early), utc))
        assertNull(Schedules.nextWanted(listOf(off), utc))
        assertNull(Schedules.nextWanted(emptyList(), utc))
    }

    @Test
    fun daysAndTimesHaveReadableLabels() {
        assertEquals("Every day", Schedules.dayLabel(Schedules.ALL_DAYS))
        assertEquals("Weekdays", Schedules.dayLabel(Schedules.WEEKDAYS))
        assertEquals("Weekends", Schedules.dayLabel(0b1100000))
        assertEquals("Mon, Wed", Schedules.dayLabel(0b0000101))
        assertEquals("No days", Schedules.dayLabel(0))
        assertEquals("07:05", Schedules.timeLabel(7, 5))
    }

    @Test
    fun aScheduleThatCannotWorkIsExplained() {
        assertNull(Schedules.problem(schedule()))
        assertNotNull(Schedules.problem(schedule().copy(title = " ")))
        assertNotNull(Schedules.problem(schedule().copy(prompt = "")))
        assertNotNull(Schedules.problem(schedule().copy(prompt = "x".repeat(Schedules.MAX_PROMPT_CHARS + 1))))
        assertNotNull(Schedules.problem(schedule(hour = 24)))
        assertNotNull(Schedules.problem(schedule(minute = 60)))
        assertNotNull(Schedules.problem(schedule(hour = -1)))
        assertNotNull(Schedules.problem(schedule(days = 0)))
    }

    @Test
    fun schedulesSurviveBeingSaved() {
        val all = listOf(
            schedule(id = "a").copy(notes = NotesUse.Recent, model = "qwen.gguf", lastRun = 99, lastNote = "Done"),
            schedule(id = "b", hour = 21, minute = 30, days = Schedules.WEEKDAYS, enabled = false),
        )
        assertEquals(all, Schedules.fromJson(Schedules.toJson(all)))
    }

    @Test
    fun damagedEntriesAreDroppedWhenReading() {
        assertTrue(Schedules.fromJson(null).isEmpty())
        assertTrue(Schedules.fromJson("nonsense").isEmpty())
        val json = """[{"id":"ok","title":"T","prompt":"P","hour":8,"minute":0},{"id":"bad","title":"","prompt":"P"},{"title":"no id","prompt":"P"},"text"]"""
        assertEquals(listOf("ok"), Schedules.fromJson(json).map { it.id })
        assertFalse(Schedules.fromJson(json).single().notes != NotesUse.None)
    }

    @Test
    fun noMoreThanTheLimitIsKept() {
        val many = (1..Schedules.MAX_SCHEDULES + 5).map { schedule(id = "s$it") }
        assertEquals(Schedules.MAX_SCHEDULES, Schedules.fromJson(Schedules.toJson(many)).size)
    }
}
