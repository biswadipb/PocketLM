package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class TaskParserTest {
    private fun bit(vararg d: DayOfWeek) = d.fold(0) { acc, x -> acc or Schedules.dayBit(x) }

    @Test
    fun readsAPartOfTheDayAndAClockTime() {
        val d = TaskParser.parse("Every morning at 7, give me a news digest about AI")
        assertEquals(7, d.hour)
        assertEquals(0, d.minute)
        assertEquals(Schedules.ALL_DAYS, d.days)
        assertEquals("Give me a news digest about AI", d.prompt)
        assertEquals("Give me a news digest about AI", d.title)
        assertTrue(d.whenFound)
        assertTrue(d.wantsTools)
        assertEquals("Every day at 07:00", d.whenLabel)
    }

    @Test
    fun aPartOfTheDayAloneGivesATypicalTime() {
        assertEquals(8, TaskParser.parse("every morning summarise my notes").hour)
        assertEquals(18, TaskParser.parse("every evening summarise my notes").hour)
        assertEquals(21, TaskParser.parse("every night write a journal prompt").hour)
        assertEquals(12, TaskParser.parse("every day at noon tell me a fact").hour)
        assertEquals(0, TaskParser.parse("every night at midnight check the logs").hour)
    }

    @Test
    fun anEveningHourWithoutAmOrPmMeansTheEvening() {
        assertEquals(19, TaskParser.parse("every evening at 7 write a journal prompt").hour)
        assertEquals(7, TaskParser.parse("every morning at 7 write a journal prompt").hour)
        assertEquals(19, TaskParser.parse("every day at 7pm write a journal prompt").hour)
        assertEquals(7, TaskParser.parse("every day at 7 am write a journal prompt").hour)
    }

    @Test
    fun readsWeekdaysAndWeekends() {
        val weekdays = TaskParser.parse("Every weekday at 7:30 summarise the tech news")
        assertEquals(Schedules.WEEKDAYS, weekdays.days)
        assertEquals(7, weekdays.hour)
        assertEquals(30, weekdays.minute)
        assertEquals("Summarise the tech news", weekdays.prompt)
        assertEquals(0b1100000, TaskParser.parse("on weekends at 10am plan the day").days)
        assertEquals(Schedules.WEEKDAYS, TaskParser.parse("Monday to Friday at 9 check my notes").days)
    }

    @Test
    fun readsNamedDays() {
        val d = TaskParser.parse("On Mondays and Thursdays at 6pm, write me a study quiz")
        assertEquals(bit(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), d.days)
        assertEquals(18, d.hour)
        assertEquals("Write me a study quiz", d.prompt)
        assertEquals(bit(DayOfWeek.FRIDAY), TaskParser.parse("every Friday at 5pm remind me to submit the report").days)
        assertEquals("Remind me to submit the report", TaskParser.parse("every Friday at 5pm remind me to submit the report").prompt)
        assertEquals(bit(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY), TaskParser.parse("Wednesdays, Saturdays and Sundays at 8 plan meals").days)
    }

    @Test
    fun weeklyWithoutADayMeansMonday() {
        val d = TaskParser.parse("Weekly at 9am give me an industry digest")
        assertEquals(bit(DayOfWeek.MONDAY), d.days)
        assertEquals("Give me an industry digest", d.prompt)
        assertEquals(bit(DayOfWeek.SUNDAY), TaskParser.parse("Weekly on Sunday at 10am plan my meals").days)
        assertEquals("Plan my meals", TaskParser.parse("Weekly on Sunday at 10am plan my meals").prompt)
    }

    @Test
    fun dailyAndTwentyFourHourTimes() {
        val d = TaskParser.parse("Daily at 21:15 summarise the notes that changed today")
        assertEquals(21, d.hour)
        assertEquals(15, d.minute)
        assertEquals(Schedules.ALL_DAYS, d.days)
        assertEquals("Summarise the notes that changed today", d.prompt)
        assertFalse(d.wantsTools)
    }

    @Test
    fun aSentenceWithoutAWhenKeepsTheDefaults() {
        val d = TaskParser.parse("Summarise my notes")
        assertFalse(d.whenFound)
        assertEquals(TaskParser.DEFAULT_HOUR, d.hour)
        assertEquals(Schedules.ALL_DAYS, d.days)
        assertEquals("Summarise my notes", d.prompt)
    }

    @Test
    fun theFillerAroundTheWhenIsDropped() {
        assertEquals("Check the weather", TaskParser.parse("Please, every day at 6:45 and then check the weather").prompt)
        assertEquals("Tell me a joke", TaskParser.parse("every morning I want you to tell me a joke").prompt)
        assertEquals("Tell me a joke.", TaskParser.parse("tell me a joke. Every day at 6").prompt)
    }

    @Test
    fun longTitlesAreCutAtAWord() {
        val d = TaskParser.parse("Every day at 8 write a very long and detailed summary of everything that happened in the world of physics")
        assertTrue(d.title.length <= 41)
        assertTrue(d.title.endsWith("…"))
        assertTrue(d.prompt.endsWith("physics"))
    }

    @Test
    fun aSentenceWithNothingButTheWhenLeavesAnEmptyPrompt() {
        val d = TaskParser.parse("every day at 6:30 pm")
        assertEquals("", d.prompt)
        assertEquals(18, d.hour)
        assertEquals(30, d.minute)
    }

    @Test
    fun aDraftBecomesASchedule() {
        val s = TaskParser.parse("Every weekday at 7:30 search the web for tech news").toSchedule("abc")
        assertEquals("abc", s.id)
        assertEquals(Schedules.WEEKDAYS, s.days)
        assertTrue(s.tools)
        assertEquals(null, Schedules.problem(s))
    }
}
