package com.pocketlm.app.data

import org.json.JSONException
import org.json.JSONObject
import kotlin.math.roundToInt

/** What to ask the model for the health section, and how to read what comes back. */
object HealthPrompts {

    /** The rules every health chat follows. It is the system prompt of the coach, and is repeated in side questions. */
    const val SAFETY = "You are Piko, a friendly fitness and nutrition coach. You are not a doctor or a dietitian and you give general " +
        "information, not medical advice. Never diagnose. Do not recommend crash diets, extreme fasting, very low calorie intakes, " +
        "laxatives, diuretics, steroids or risky supplements, and never go below the calorie numbers I give you. If I mention pain, an " +
        "injury, illness, medication, pregnancy, an eating disorder, or being under 18, keep advice gentle and tell me to ask a doctor " +
        "or a qualified professional. Use the numbers I give you instead of working out your own. Be concise, practical and encouraging."

    /** Coach persona, for the built-in character. */
    const val COACH = "$SAFETY Coach me one step at a time: ask a single short question, wait for my answer, then give a small, " +
        "specific next step. Keep each reply under 120 words unless I ask for a full plan."

    const val COACH_GREETING = "Hi, I'm Piko, your fitness and food coach. Tell me what you'd like to work on (strength, " +
        "fat loss, a meal plan, or just getting started) and I'll take it one step at a time. I give general tips, not medical advice."

    /** A week of training. */
    fun workoutPlan(profile: HealthProfile, days: Int, minutes: Int, focus: String): String = buildString {
        append(HealthProfiles.summary(profile)).append("\n\n")
        append("Make me a weekly workout plan for $days training days, about $minutes minutes each. ")
        if (focus.isNotBlank()) append("Focus: ${focus.trim()}. ")
        append("Use only what is available to me (${profile.equipment.label.lowercase()}) and suit my level (${profile.experience.label.lowercase()}). ")
        append("For each day give a name, a short warm-up, the exercises with sets x reps and rest times, and a cool-down. ")
        append("Mark the rest days. End with how to add weight or reps over the next weeks, and one safety tip. ")
        if (Caution.Injury in profile.cautions) append("I have an injury or pain, so avoid anything that loads it and remind me to see a professional. ")
    }

    /** A week of meals. */
    fun mealPlan(profile: HealthProfile, targets: Targets?, days: Int, extra: String): String = buildString {
        append(HealthProfiles.summary(profile, targets)).append("\n\n")
        append("Make me a $days-day meal plan with breakfast, lunch, dinner and two snacks each day. ")
        if (targets != null) append("Aim for about ${targets.calories} kcal and ${targets.proteinG} g protein a day, and show the rough calories of each meal. ")
        else append("Do not set calorie numbers; focus on balanced, regular, enjoyable meals. ")
        append("Follow my eating style (${profile.diet.label.lowercase()})")
        if (profile.avoid.isNotBlank()) append(" and leave out: ${profile.avoid.trim()}")
        append(". Use ordinary, affordable foods that are easy to find. ")
        if (extra.isNotBlank()) append("Also: ${extra.trim()}. ")
        append("End with a shopping list grouped by aisle.")
    }

    /** The first message of a guided coaching chat. */
    fun coachStart(profile: HealthProfile?, topic: String): String = buildString {
        if (profile != null) append(HealthProfiles.summary(profile)).append("\n\n")
        append("Please coach me step by step")
        if (topic.isNotBlank()) append(" on: ${topic.trim()}")
        append(". Ask me one short question at a time.")
    }

    /** Asking the model to estimate a meal; held to JSON by a schema. */
    fun estimateMeal(description: String): String =
        "Separate task: ignore everything said above. Estimate the nutrition of this meal as a typical portion: ${description.trim()}. " +
            "Answer only as JSON with the fields name (short), kcal, protein_g, carbs_g, fat_g."

    val ESTIMATE_SCHEMA = """{"type":"object","properties":{"name":{"type":"string"},"kcal":{"type":"integer"},"protein_g":{"type":"number"},"carbs_g":{"type":"number"},"fat_g":{"type":"number"}},"required":["name","kcal","protein_g","carbs_g","fat_g"]}"""

    fun quiz(topic: String, count: Int): String =
        "Separate task: ignore everything said above. Write $count multiple-choice questions to test someone on $topic. " +
            "Each has exactly 4 different choices, the position (0 to 3) of the right one in \"answer\", and a one-sentence explanation. " +
            "Only well-established facts. Answer only as JSON."

    fun flashcards(topic: String, count: Int): String =
        "Separate task: ignore everything said above. Write $count flashcards to learn $topic. Each has a short \"front\" (a question or term) " +
            "and a clear \"back\" (the answer in one or two sentences). Only well-established facts. Answer only as JSON."
}

/** The model's guess at a meal's nutrition. It is a guess: the person checks it before it is saved. */
data class MealEstimate(val name: String, val kcal: Int, val proteinG: Float, val carbsG: Float, val fatG: Float) {
    companion object {
        /** Reads the model's JSON; null if it is missing, nonsense or absurd. */
        fun parse(text: String): MealEstimate? {
            val o = try {
                JSONObject(text.trim().removePrefix("```json").removeSuffix("```").trim())
            } catch (e: JSONException) {
                return null
            }
            val kcal = o.optDouble("kcal", -1.0).roundToInt()
            val p = o.optDouble("protein_g", -1.0).toFloat()
            val c = o.optDouble("carbs_g", -1.0).toFloat()
            val f = o.optDouble("fat_g", -1.0).toFloat()
            val name = o.optString("name").trim()
            if (name.isEmpty() || kcal !in 1..5000 || p < 0 || c < 0 || f < 0 || p > 400 || c > 800 || f > 400) return null
            return MealEstimate(name.take(60), kcal, p, c, f)
        }
    }
}
