package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

enum class Sex(val label: String) { Female("Female"), Male("Male"), Other("Other / prefer not to say") }

enum class Activity(val label: String, val detail: String, val factor: Double) {
    Sedentary("Mostly sitting", "Little or no exercise", 1.2),
    Light("Lightly active", "Exercise 1 to 3 days a week", 1.375),
    Moderate("Moderately active", "Exercise 3 to 5 days a week", 1.55),
    Active("Very active", "Hard exercise 6 to 7 days a week", 1.725),
    Athlete("Extremely active", "Hard training twice a day, or a physical job", 1.9),
}

enum class Goal(val label: String) {
    Lose("Lose body fat"),
    Maintain("Stay as I am"),
    Gain("Build muscle"),
}

enum class DietKind(val label: String) {
    Anything("No restrictions"),
    Vegetarian("Vegetarian"),
    Eggetarian("Vegetarian, with eggs"),
    Vegan("Vegan"),
    Pescatarian("Fish but no meat"),
}

enum class Equipment(val label: String) {
    Gym("A full gym"),
    Home("Dumbbells or bands at home"),
    Bodyweight("No equipment"),
}

enum class Experience(val label: String) { Beginner("Beginner"), Intermediate("Some experience"), Advanced("Advanced") }

/** Things that mean advice should stay general, and numbers such as calorie targets are not set. */
enum class Caution(val label: String) {
    Pregnant("Pregnant or breastfeeding"),
    Condition("A medical condition, or I take medication"),
    Eating("A history of disordered eating"),
    Injury("An injury or pain"),
}

/** What a person tells PocketLM about themselves so plans and numbers fit them. It stays on the phone. */
data class HealthProfile(
    val age: Int = 30,
    val sex: Sex = Sex.Other,
    val heightCm: Int = 170,
    val weightKg: Float = 70f,
    val activity: Activity = Activity.Light,
    val goal: Goal = Goal.Maintain,
    val diet: DietKind = DietKind.Anything,
    /** Foods to leave out, and allergies, in the person's own words. */
    val avoid: String = "",
    val equipment: Equipment = Equipment.Gym,
    val experience: Experience = Experience.Beginner,
    val daysPerWeek: Int = 3,
    val cautions: Set<Caution> = emptySet(),
    /** Anything else the plans should know: schedule, favourite foods, the injury. */
    val notes: String = "",
)

/** Daily numbers worked out from a profile. They are estimates, and are rounded the way a person would use them. */
data class Targets(val calories: Int, val proteinG: Int, val carbsG: Int, val fatG: Int, val waterMl: Int, val note: String?)

object HealthMath {
    const val ADULT = 18

    /** Basal metabolic rate by the Mifflin–St Jeor equation, in kilocalories a day. */
    fun bmr(p: HealthProfile): Double {
        val offset = when (p.sex) {
            Sex.Male -> 5.0
            Sex.Female -> -161.0
            Sex.Other -> -78.0 // halfway between the two
        }
        return 10 * p.weightKg + 6.25 * p.heightCm - 5 * p.age + offset
    }

    /** What the body uses in a day with this much activity. */
    fun maintenance(p: HealthProfile): Double = bmr(p) * p.activity.factor

    fun bmi(p: HealthProfile): Double {
        val m = p.heightCm / 100.0
        return p.weightKg / (m * m)
    }

    /** The adult BMI band. BMI is a rough screen: it cannot tell muscle from fat. */
    fun bmiBand(bmi: Double): String = when {
        bmi < 18.5 -> "Under the healthy range"
        bmi < 25 -> "Healthy range"
        bmi < 30 -> "Over the healthy range"
        else -> "Well over the healthy range"
    }

    /** The lowest daily calories this app will suggest, whatever the goal. */
    fun floor(sex: Sex): Int = when (sex) {
        Sex.Female -> 1200
        Sex.Other -> 1350
        Sex.Male -> 1500
    }

    /** Why [targets] returns nothing for this profile, or null if it does give numbers. */
    fun noTargetsReason(p: HealthProfile): String? = when {
        p.age < ADULT -> "Calorie and body-weight targets are only worked out for adults. A doctor or a qualified coach can set them for someone who is still growing."
        Caution.Eating in p.cautions -> "Because of your history with eating, PocketLM does not set calorie or weight targets. A doctor or dietitian can help you plan this safely. Habits, strength and enjoyment can still be a good focus."
        Caution.Pregnant in p.cautions -> "Needs change during pregnancy and breastfeeding, so PocketLM does not set calorie targets. Ask your doctor or midwife what is right for you."
        else -> null
    }

    /** Daily calories, protein, carbohydrate, fat and water, or null when [noTargetsReason] says why not. */
    fun targets(p: HealthProfile): Targets? {
        if (noTargetsReason(p) != null) return null
        val maintenance = maintenance(p)
        val bmi = bmi(p)
        var goal = p.goal
        var note: String? = null
        if (goal == Goal.Lose && bmi < 18.5) {
            goal = Goal.Maintain
            note = "Your BMI is under the healthy range, so the target is set to keep your weight rather than lower it."
        }
        val calories = when (goal) {
            // A gentle deficit: at most a fifth of what you use and never more than 500 a day. It is not taken below the
            // floor, and never above what you use (a small person's floor can be higher than their maintenance).
            Goal.Lose -> {
                val wanted = maintenance - minOf(maintenance * 0.2, 500.0)
                if (wanted < floor(p.sex)) note = note ?: "This is as low as PocketLM will suggest."
                minOf(wanted.coerceAtLeast(floor(p.sex).toDouble()), maintenance)
            }
            Goal.Maintain -> maintenance
            Goal.Gain -> maintenance + minOf(maintenance * 0.1, 350.0)
        }.let { (it / 10).roundToInt() * 10 }

        val perKg = when (goal) {
            Goal.Lose -> 1.6
            Goal.Maintain -> 1.2
            Goal.Gain -> 1.8
        }
        // Protein is kept within sensible bounds and never more than 35% of the calories.
        val protein = (p.weightKg * perKg).coerceIn(p.weightKg * 0.8, p.weightKg * 2.2).coerceAtMost(calories * 0.35 / 4).roundToInt()
        val fat = (calories * 0.27 / 9).roundToInt()
        val carbs = ((calories - protein * 4 - fat * 9) / 4.0).roundToInt().coerceAtLeast(0)
        val water = ((p.weightKg * 33 / 50).roundToInt() * 50).coerceIn(1500, 4000)
        return Targets(calories, protein, carbs, fat, water, note)
    }
}

/** A problem with a profile's numbers, or something worth saying, in plain words. */
object HealthSafety {
    fun problems(p: HealthProfile): List<String> = buildList {
        if (p.age !in 10..100) add("Age must be between 10 and 100.")
        if (p.heightCm !in 120..230) add("Height must be between 120 and 230 cm.")
        if (p.weightKg !in 30f..300f) add("Weight must be between 30 and 300 kg.")
        if (p.daysPerWeek !in 1..7) add("Training days must be between 1 and 7.")
        if (p.notes.length > 600 || p.avoid.length > 300) add("Notes are too long.")
    }

    /** Things to show next to a profile: not errors, but worth knowing. */
    fun cautions(p: HealthProfile): List<String> = buildList {
        if (p.age < HealthMath.ADULT) add("You are under 18, so PocketLM only gives general tips. Talk to a doctor or a qualified coach before changing your food or training.")
        HealthMath.noTargetsReason(p)?.takeIf { p.age >= HealthMath.ADULT }?.let(::add)
        if (Caution.Condition in p.cautions) add("With a medical condition or medication, check any change in food or exercise with your doctor first.")
        if (Caution.Injury in p.cautions) add("With an injury or pain, see a professional before training that area. Stop any exercise that hurts.")
        if (p.goal == Goal.Lose && HealthMath.bmi(p) < 18.5 && p.age >= HealthMath.ADULT) add("Your BMI is under the healthy range, so losing weight is not suggested.")
    }
}

object HealthProfiles {
    fun toJson(p: HealthProfile): String = JSONObject()
        .put("age", p.age).put("sex", p.sex.name).put("height", p.heightCm).put("weight", p.weightKg.toDouble())
        .put("activity", p.activity.name).put("goal", p.goal.name).put("diet", p.diet.name).put("avoid", p.avoid)
        .put("equipment", p.equipment.name).put("experience", p.experience.name).put("days", p.daysPerWeek)
        .put("cautions", JSONArray(p.cautions.map { it.name })).put("notes", p.notes)
        .toString()

    fun fromJson(text: String?): HealthProfile? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(text)
            val d = HealthProfile()
            fun <T : Enum<T>> pick(values: Array<T>, name: String, default: T) = values.firstOrNull { it.name == o.optString(name) } ?: default
            HealthProfile(
                age = o.optInt("age", d.age), sex = pick(Sex.entries.toTypedArray(), "sex", d.sex),
                heightCm = o.optInt("height", d.heightCm), weightKg = o.optDouble("weight", d.weightKg.toDouble()).toFloat(),
                activity = pick(Activity.entries.toTypedArray(), "activity", d.activity), goal = pick(Goal.entries.toTypedArray(), "goal", d.goal),
                diet = pick(DietKind.entries.toTypedArray(), "diet", d.diet), avoid = o.optString("avoid", ""),
                equipment = pick(Equipment.entries.toTypedArray(), "equipment", d.equipment),
                experience = pick(Experience.entries.toTypedArray(), "experience", d.experience),
                daysPerWeek = o.optInt("days", d.daysPerWeek),
                cautions = o.optJSONArray("cautions")?.let { a -> (0 until a.length()).mapNotNull { i -> Caution.entries.firstOrNull { it.name == a.optString(i) } }.toSet() }.orEmpty(),
                notes = o.optString("notes", ""),
            ).takeIf { HealthSafety.problems(it).isEmpty() }
        }.getOrNull()
    }

    /** The profile in a few lines for a chat with the model, so its plans fit the person. */
    fun summary(p: HealthProfile, targets: Targets? = HealthMath.targets(p)): String = buildString {
        append("About me: ").append(p.age).append(" years old, ").append(when (p.sex) { Sex.Female -> "woman"; Sex.Male -> "man"; Sex.Other -> "person" }).append(", ")
        append(p.heightCm).append(" cm, ").append(trim(p.weightKg)).append(" kg (BMI ").append("%.1f".format(HealthMath.bmi(p))).append("). ")
        append(p.activity.label.lowercase()).append(". Goal: ").append(p.goal.label.lowercase()).append(". ")
        append("Eating: ").append(p.diet.label.lowercase()).append(". ")
        if (p.avoid.isNotBlank()) append("I avoid or am allergic to: ").append(p.avoid.trim()).append(". ")
        append("Training: ").append(p.daysPerWeek).append(" days a week, ").append(p.equipment.label.lowercase()).append(", ")
        append(p.experience.label.lowercase()).append(". ")
        if (p.cautions.isNotEmpty()) append("Take care: ").append(p.cautions.joinToString("; ") { it.label.lowercase() }).append(". ")
        if (p.notes.isNotBlank()) append(p.notes.trim()).append(" ")
        if (targets != null) {
            append("Daily estimates worked out for me: about ${targets.calories} kcal, ${targets.proteinG} g protein, ")
            append("${targets.carbsG} g carbohydrate, ${targets.fatG} g fat and ${targets.waterMl / 1000.0} litres of water.")
        }
    }.trim()

    private fun trim(v: Float) = if (v % 1f == 0f) v.toInt().toString() else "%.1f".format(v)
}
