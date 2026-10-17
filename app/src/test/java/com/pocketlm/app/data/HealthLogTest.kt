package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthLogTest {

    private val today = 20_000L

    private fun food(day: Long, kcal: Int, p: Float = 10f) = FoodEntry(HealthLogs.newId(), day, "Meal", kcal, p, 20f, 5f)

    @Test
    fun aDayIsAddedUp() {
        var log = HealthLog()
        log = HealthLogs.withFood(log, food(today, 500, 30f))
        log = HealthLogs.withFood(log, food(today, 300, 10f))
        log = HealthLogs.withFood(log, food(today - 1, 999))
        log = HealthLogs.withWater(log, WaterEntry("a", today, 250))
        log = HealthLogs.withWater(log, WaterEntry("b", today, 500))
        log = HealthLogs.withWorkout(log, WorkoutEntry("w", today, "Run", 30))
        val s = HealthLogs.daySummary(log, today)
        assertEquals(800, s.kcal)
        assertEquals(40f, s.proteinG, 0.001f)
        assertEquals(750, s.waterMl)
        assertEquals(30, s.workoutMinutes)
        assertEquals(2, s.meals)
        assertTrue(s.any)
        assertFalse(HealthLogs.daySummary(log, today + 1).any)
    }

    @Test
    fun theWeekEndsToday() {
        val log = HealthLogs.withFood(HealthLog(), food(today - 2, 400))
        val week = HealthLogs.lastDays(log, today)
        assertEquals(7, week.size)
        assertEquals(today, week.last().day)
        assertEquals(400, week[4].kcal)
        assertEquals(today - 6, week.first().day)
    }

    @Test
    fun aStreakCountsDaysInARowAndForgivesTodayNotYetWritten() {
        var log = HealthLog()
        for (d in today - 3..today - 1) log = HealthLogs.withFood(log, food(d, 300))
        assertEquals(3, HealthLogs.streak(log, today)) // nothing yet today: yesterday still counts
        log = HealthLogs.withWater(log, WaterEntry("x", today, 250))
        assertEquals(4, HealthLogs.streak(log, today))
        log = HealthLogs.withoutFood(log, log.foods[1].id) // a gap
        assertEquals(2, HealthLogs.streak(log, today))
        assertEquals(0, HealthLogs.streak(HealthLog(), today))
    }

    @Test
    fun aWeighInReplacesTheEarlierOneThatDay() {
        var log = HealthLogs.withWeight(HealthLog(), WeightEntry(today, 80f))
        log = HealthLogs.withWeight(log, WeightEntry(today, 79.5f))
        log = HealthLogs.withWeight(log, WeightEntry(today - 10, 81f))
        assertEquals(listOf(today - 10, today), log.weights.map { it.day })
        assertEquals(79.5f, log.weights.last().kg, 0f)
        val trend = HealthLogs.weights(log, today)
        assertEquals(-1.5f, HealthLogs.weightChange(trend)!!, 0.001f)
        assertNull(HealthLogs.weightChange(trend.take(1)))
        assertEquals(1, HealthLogs.weights(log, today, days = 5).size)
    }

    @Test
    fun takingBackTheLastGlassOnlyAffectsToday() {
        var log = HealthLogs.withWater(HealthLog(), WaterEntry("a", today - 1, 250))
        log = HealthLogs.withWater(log, WaterEntry("b", today, 250))
        log = HealthLogs.withWater(log, WaterEntry("c", today, 500))
        val after = HealthLogs.withoutLastWater(log, today)
        assertEquals(listOf("a", "b"), after.water.map { it.id })
        assertEquals(log, HealthLogs.withoutLastWater(HealthLog(), today).let { log })
    }

    @Test
    fun oldEntriesAreDroppedAndTheLogHasALimit() {
        var log = HealthLogs.withFood(HealthLog(), food(today - HealthLogs.KEEP_DAYS - 1, 100))
        log = HealthLogs.withFood(log, food(today - 5, 200))
        assertEquals(1, HealthLogs.pruned(log, today).foods.size)
        var big = HealthLog()
        repeat(HealthLogs.MAX_PER_KIND + 10) { big = HealthLogs.withWater(big, WaterEntry("w$it", today, 250)) }
        assertEquals(HealthLogs.MAX_PER_KIND, big.water.size)
        assertEquals("w${HealthLogs.MAX_PER_KIND + 9}", big.water.last().id)
    }

    @Test
    fun aLogSurvivesBeingSavedAndBadEntriesAreSkipped() {
        var log = HealthLogs.withFood(HealthLog(), FoodEntry("f1", today, "Dal \"home\"", 250, 12.5f, 30f, 5f))
        log = HealthLogs.withWorkout(log, WorkoutEntry("w1", today, "Legs", 45, "heavy day"))
        log = HealthLogs.withWater(log, WaterEntry("a1", today, 250))
        log = HealthLogs.withWeight(log, WeightEntry(today, 70.5f))
        assertEquals(log, HealthLogs.fromJson(HealthLogs.toJson(log)))
        assertEquals(HealthLog(), HealthLogs.fromJson(null))
        assertEquals(HealthLog(), HealthLogs.fromJson("garbage"))
        val mixed = """{"foods":[{"id":"ok","day":5,"name":"x","kcal":10},{"day":5},"nope"],"water":[{"id":"w","day":5,"ml":100}]}"""
        val read = HealthLogs.fromJson(mixed)
        assertEquals(listOf("ok"), read.foods.map { it.id })
        assertEquals(1, read.water.size)
    }
}
