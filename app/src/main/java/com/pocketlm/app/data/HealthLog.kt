package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Days are counted as whole days since 1970 (`LocalDate.toEpochDay()`), so time zones never split an entry. */
data class FoodEntry(val id: String, val day: Long, val name: String, val kcal: Int, val proteinG: Float, val carbsG: Float, val fatG: Float)

data class WorkoutEntry(val id: String, val day: Long, val name: String, val minutes: Int, val note: String = "")

/** Water drunk, in millilitres. */
data class WaterEntry(val id: String, val day: Long, val ml: Int)

data class WeightEntry(val day: Long, val kg: Float)

/** What the person has written down. Kept on the phone only: it is not synced and not in backups. */
data class HealthLog(
    val foods: List<FoodEntry> = emptyList(),
    val workouts: List<WorkoutEntry> = emptyList(),
    val water: List<WaterEntry> = emptyList(),
    val weights: List<WeightEntry> = emptyList(),
)

/** One day added up. */
data class DaySummary(
    val day: Long, val kcal: Int, val proteinG: Float, val carbsG: Float, val fatG: Float, val waterMl: Int,
    val workoutMinutes: Int, val meals: Int, val workouts: Int,
) {
    val any: Boolean get() = meals > 0 || waterMl > 0 || workouts > 0
}

object HealthLogs {
    /** Entries older than this many days are dropped, so the file stays small. */
    const val KEEP_DAYS = 400L
    const val MAX_PER_KIND = 4000

    fun newId(): String = UUID.randomUUID().toString().take(12)

    fun daySummary(log: HealthLog, day: Long): DaySummary {
        val foods = log.foods.filter { it.day == day }
        val workouts = log.workouts.filter { it.day == day }
        return DaySummary(
            day = day,
            kcal = foods.sumOf { it.kcal },
            proteinG = foods.sumOf { it.proteinG.toDouble() }.toFloat(),
            carbsG = foods.sumOf { it.carbsG.toDouble() }.toFloat(),
            fatG = foods.sumOf { it.fatG.toDouble() }.toFloat(),
            waterMl = log.water.filter { it.day == day }.sumOf { it.ml },
            workoutMinutes = workouts.sumOf { it.minutes },
            meals = foods.size,
            workouts = workouts.size,
        )
    }

    /** The [days] days ending with [today], oldest first. */
    fun lastDays(log: HealthLog, today: Long, days: Int = 7): List<DaySummary> =
        (today - days + 1..today).map { daySummary(log, it) }

    /** How many days in a row, ending today (or yesterday, if nothing is written yet today), have something written down. */
    fun streak(log: HealthLog, today: Long): Int {
        var day = if (daySummary(log, today).any) today else today - 1
        var count = 0
        while (daySummary(log, day).any) {
            count++
            day--
            if (count > KEEP_DAYS) break
        }
        return count
    }

    /** Weigh-ins from the last [days] days, oldest first. */
    fun weights(log: HealthLog, today: Long, days: Int = 90): List<WeightEntry> =
        log.weights.filter { it.day in today - days + 1..today }.sortedBy { it.day }

    /** The change in weight between the earliest and latest of [entries], or null with fewer than two. */
    fun weightChange(entries: List<WeightEntry>): Float? =
        if (entries.size < 2) null else entries.last().kg - entries.first().kg

    /** [log] with [food] added. */
    fun withFood(log: HealthLog, food: FoodEntry) = log.copy(foods = (log.foods + food).takeLast(MAX_PER_KIND))

    fun withWorkout(log: HealthLog, workout: WorkoutEntry) = log.copy(workouts = (log.workouts + workout).takeLast(MAX_PER_KIND))

    fun withWater(log: HealthLog, water: WaterEntry) = log.copy(water = (log.water + water).takeLast(MAX_PER_KIND))

    /** A weigh-in replaces any earlier one on the same day. */
    fun withWeight(log: HealthLog, weight: WeightEntry) =
        log.copy(weights = (log.weights.filterNot { it.day == weight.day } + weight).sortedBy { it.day }.takeLast(MAX_PER_KIND))

    fun withoutFood(log: HealthLog, id: String) = log.copy(foods = log.foods.filterNot { it.id == id })

    fun withoutWorkout(log: HealthLog, id: String) = log.copy(workouts = log.workouts.filterNot { it.id == id })

    /** Takes back the last glass or bottle written today. */
    fun withoutLastWater(log: HealthLog, day: Long): HealthLog {
        val last = log.water.lastOrNull { it.day == day } ?: return log
        return log.copy(water = log.water.filterNot { it.id == last.id })
    }

    /** [log] without what is older than [KEEP_DAYS] before [today]. */
    fun pruned(log: HealthLog, today: Long): HealthLog {
        val oldest = today - KEEP_DAYS
        return HealthLog(
            log.foods.filter { it.day >= oldest }, log.workouts.filter { it.day >= oldest },
            log.water.filter { it.day >= oldest }, log.weights.filter { it.day >= oldest },
        )
    }

    fun toJson(log: HealthLog): String = JSONObject()
        .put("foods", JSONArray(log.foods.map {
            JSONObject().put("id", it.id).put("day", it.day).put("name", it.name).put("kcal", it.kcal)
                .put("p", it.proteinG.toDouble()).put("c", it.carbsG.toDouble()).put("f", it.fatG.toDouble())
        }))
        .put("workouts", JSONArray(log.workouts.map {
            JSONObject().put("id", it.id).put("day", it.day).put("name", it.name).put("min", it.minutes).put("note", it.note)
        }))
        .put("water", JSONArray(log.water.map { JSONObject().put("id", it.id).put("day", it.day).put("ml", it.ml) }))
        .put("weights", JSONArray(log.weights.map { JSONObject().put("day", it.day).put("kg", it.kg.toDouble()) }))
        .toString()

    fun fromJson(text: String?): HealthLog {
        if (text.isNullOrBlank()) return HealthLog()
        return runCatching {
            val o = JSONObject(text)
            fun <T> read(name: String, make: (JSONObject) -> T?): List<T> {
                val a = o.optJSONArray(name) ?: return emptyList()
                return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { runCatching { make(it) }.getOrNull() } }
            }
            HealthLog(
                foods = read("foods") {
                    FoodEntry(it.getString("id"), it.getLong("day"), it.getString("name"), it.getInt("kcal"),
                        it.optDouble("p", 0.0).toFloat(), it.optDouble("c", 0.0).toFloat(), it.optDouble("f", 0.0).toFloat())
                },
                workouts = read("workouts") { WorkoutEntry(it.getString("id"), it.getLong("day"), it.getString("name"), it.getInt("min"), it.optString("note", "")) },
                water = read("water") { WaterEntry(it.getString("id"), it.getLong("day"), it.getInt("ml")) },
                weights = read("weights") { WeightEntry(it.getLong("day"), it.getDouble("kg").toFloat()) },
            )
        }.getOrDefault(HealthLog())
    }
}
