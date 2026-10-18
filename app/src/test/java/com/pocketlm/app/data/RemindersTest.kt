package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class RemindersTest {
    private val utc = ZoneId.of("UTC")

    /** Friday 16 October 2026, 09:05. */
    private val now = ZonedDateTime.of(2026, 10, 16, 9, 5, 0, 0, utc)

    private fun parse(input: String) = ReminderParser.parse(input, now)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, utc)

    private fun failure(input: String): String = try {
        parse(input)
        fail("expected an error for: $input")
        ""
    } catch (e: IllegalArgumentException) {
        e.message!!
    }

    @Test
    fun readsHowLongToWait() {
        assertEquals(ParsedReminder(now.plusMinutes(10), "take the pasta off"), parse("in 10 minutes: take the pasta off"))
        assertEquals(ParsedReminder(now.plusMinutes(90), "stretch"), parse("in 1h30m to stretch"))
        assertEquals(ParsedReminder(now.plusMinutes(65), "tea"), parse("in an hour and 5 minutes: tea"))
        assertEquals(ParsedReminder(now.plusMinutes(30), "call Sam"), parse("in half an hour - call Sam"))
        assertEquals(ParsedReminder(now.plusDays(2), "renew"), parse("in 2 days: renew"))
        assertEquals(ParsedReminder(now.plusSeconds(45), "ping"), parse("in 45 seconds: ping"))
    }

    @Test
    fun aWaitWithoutTheWordInIsStillAWait() {
        assertEquals(ParsedReminder(now.plusMinutes(10), "tea"), parse("10 minutes: tea"))
        assertEquals(ParsedReminder(now.plusMinutes(5), "drink water"), parse("remind me in 5 minutes to drink water"))
        assertEquals(ParsedReminder(now.plusMinutes(5), "tea"), parse("timer 5 min: tea"))
    }

    @Test
    fun readsAClockTime() {
        assertEquals(ParsedReminder(at(2026, 10, 16, 18, 30), "call Sam"), parse("at 18:30: call Sam"))
        assertEquals(ParsedReminder(at(2026, 10, 16, 18, 0), "dinner"), parse("at 6pm: dinner"))
        assertEquals(ParsedReminder(at(2026, 10, 16, 12, 0), "lunch"), parse("at 12pm lunch"))
        assertEquals(ParsedReminder(at(2026, 10, 17, 0, 15), "night"), parse("at 12:15 am: night"))
    }

    @Test
    fun aClockTimeThatHasPassedMeansTomorrow() {
        assertEquals(ParsedReminder(at(2026, 10, 17, 8, 0), "gym"), parse("at 8:00: gym"))
    }

    @Test
    fun readsADayAndATime() {
        assertEquals(ParsedReminder(at(2026, 10, 17, 9, 0), "dentist"), parse("tomorrow at 9:00: dentist"))
        assertEquals(ParsedReminder(at(2026, 10, 17, 9, 0), "dentist"), parse("tomorrow 9am dentist"))
        assertEquals(ParsedReminder(at(2026, 10, 16, 21, 0), "film"), parse("today at 21:00: film"))
    }

    @Test
    fun readsAWeekday() {
        // It is Friday 09:05: 09:00 has gone, so that is next Friday; 10:00 is still to come.
        assertEquals(ParsedReminder(at(2026, 10, 23, 9, 0), "report"), parse("friday at 9:00: report"))
        assertEquals(ParsedReminder(at(2026, 10, 16, 10, 0), "report"), parse("friday at 10:00: report"))
        assertEquals(ParsedReminder(at(2026, 10, 19, 8, 0), "bins"), parse("monday 8am: bins"))
        assertEquals(ParsedReminder(at(2026, 10, 21, 18, 0), "yoga"), parse("on wed at 18:00: yoga"))
    }

    @Test
    fun readsADate() {
        assertEquals(ParsedReminder(at(2026, 10, 20, 9, 0), "renew passport"), parse("2026-10-20 09:00: renew passport"))
        assertEquals(ParsedReminder(at(2026, 10, 20, 9, 0), "renew"), parse("2026-10-20: renew"))
        assertEquals(ParsedReminder(at(2026, 12, 1, 7, 30), "x"), parse("2026-12-01T07:30 x"))
    }

    @Test
    fun explainsWhatItCouldNotRead() {
        assertTrue(failure("").contains("for example"))
        assertTrue(failure("sometime soon: x").contains("Could not read the time"))
        assertTrue(failure("in 10 minutes").contains("What should the reminder say"))
        assertTrue(failure("in 10 minutes:   ").contains("What should the reminder say"))
        assertTrue(failure("at 25:00: x").contains("not an hour"))
        assertTrue(failure("at 13pm: x").contains("not a time"))
        assertTrue(failure("2026-02-30 10:00: x").contains("not a real date"))
        assertTrue(failure("today at 8:00: x").contains("already passed"))
        assertTrue(failure("2020-01-01: x").contains("already passed"))
        assertTrue(failure("in 400 days: x").contains("a year"))
        assertTrue(failure("10: x").contains("Could not read the time"))
    }

    @Test
    fun longTextIsCutShort() {
        val parsed = parse("in 5 minutes: " + "word ".repeat(100))
        assertTrue(parsed.text.length <= ReminderParser.MAX_TEXT_CHARS)
    }

    @Test
    fun theListKeepsItsOrderAndLimit() {
        var list = emptyList<Reminder>()
        val (l1, a) = Reminders.add(list, 3000, "later")
        list = l1
        val (l2, b) = Reminders.add(list, 1000, "sooner")
        list = l2
        assertEquals(listOf("sooner", "later"), list.map { it.text })
        assertTrue(a.id != b.id)
        assertEquals(listOf("later"), Reminders.remove(list, b.id).map { it.text })

        var full = emptyList<Reminder>()
        repeat(ReminderParser.MAX_PENDING) { full = Reminders.add(full, it.toLong(), "r$it").first }
        try {
            Reminders.add(full, 99, "one too many")
            fail("expected the limit")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("already"))
        }
    }

    @Test
    fun theListSurvivesBeingSaved() {
        val list = listOf(Reminder(2, 2000, "b \"quoted\""), Reminder(1, 1000, "a"))
        val back = Reminders.fromJson(Reminders.toJson(list))
        assertEquals(listOf(Reminder(1, 1000, "a"), Reminder(2, 2000, "b \"quoted\"")), back)
        assertEquals(emptyList<Reminder>(), Reminders.fromJson(null))
        assertEquals(emptyList<Reminder>(), Reminders.fromJson("not json"))
    }

    @Test
    fun describesWhen() {
        fun d(t: ZonedDateTime) = Reminders.describe(t.toInstant(), now.toInstant(), utc)
        assertEquals("today at 18:30", d(at(2026, 10, 16, 18, 30)))
        assertEquals("tomorrow at 09:00", d(at(2026, 10, 17, 9, 0)))
        assertEquals("Tue 20 Oct at 09:00", d(at(2026, 10, 20, 9, 0)))
        assertEquals("Fri 1 Jan 2027 at 07:30", d(at(2027, 1, 1, 7, 30)))
        assertEquals(Instant.parse("2026-10-16T09:05:00Z"), now.toInstant())
    }
}
