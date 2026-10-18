package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Random

/** A multiple-choice question with one right answer and a short reason. */
data class QuizQuestion(
    val topic: String,
    val text: String,
    val choices: List<String>,
    val answer: Int,
    val explanation: String,
) {
    /** The same question with its choices in a random order. */
    fun shuffled(random: Random): QuizQuestion {
        val order = choices.indices.shuffled(random)
        return copy(choices = order.map { choices[it] }, answer = order.indexOf(answer))
    }
}

/** What the quiz holds when the model is not needed: well-established basics of food, training and rest. */
object QuizBank {
    private fun q(topic: String, text: String, answer: String, wrong: List<String>, why: String): QuizQuestion {
        val choices = listOf(answer) + wrong
        require(choices.size == 4 && choices.toSet().size == 4) { "A question needs four different choices: $text" }
        return QuizQuestion(topic, text, choices, 0, why)
    }

    val all: List<QuizQuestion> = listOf(
        q("Nutrition", "How many calories are in one gram of protein?", "About 4", listOf("About 1", "About 7", "About 9"),
            "Protein and carbohydrate have about 4 kilocalories a gram, fat about 9, and alcohol about 7."),
        q("Nutrition", "Which nutrient has the most calories per gram?", "Fat", listOf("Protein", "Carbohydrate", "Fibre"),
            "Fat has about 9 kilocalories a gram, more than twice protein or carbohydrate."),
        q("Nutrition", "Which of these is NOT a macronutrient?", "Vitamins", listOf("Protein", "Carbohydrate", "Fat"),
            "The macronutrients are protein, carbohydrate and fat. Vitamins and minerals are micronutrients, needed in small amounts."),
        q("Nutrition", "Which food is a source of complete protein, with all the essential amino acids?", "Eggs", listOf("White rice", "Apples", "Olive oil"),
            "Eggs, fish, meat and dairy are complete proteins. Plant foods can be combined, such as pulses with grains."),
        q("Nutrition", "What does fibre mainly help with?", "Digestion and feeling full", listOf("Building muscle quickly", "Giving quick energy", "Storing vitamins"),
            "Fibre, from whole grains, fruit, vegetables and pulses, helps digestion and keeps you feeling full."),
        q("Nutrition", "Which vitamin can your skin make when it is in sunlight?", "Vitamin D", listOf("Vitamin C", "Vitamin B12", "Vitamin K"),
            "Skin makes vitamin D from sunlight. Little sun, covered skin or dark skin can mean you need food or supplements; a doctor can check."),
        q("Nutrition", "The World Health Organization advises keeping added sugar to less than what share of daily calories?", "10%", listOf("25%", "40%", "50%"),
            "Less than 10% of energy from free sugars, and under 5% for extra benefit."),
        q("Nutrition", "Which pair helps a vegetarian absorb more iron from lentils?", "Lentils with a vitamin C food such as lemon or tomato", listOf("Lentils with tea", "Lentils with a calcium supplement", "Lentils with cola"),
            "Vitamin C improves absorption of plant iron. Tea and coffee with a meal can reduce it."),
        q("Nutrition", "What does a calorie deficit mean?", "Eating fewer calories than your body uses", listOf("Eating no carbohydrate", "Eating only once a day", "Eating less protein"),
            "A deficit is simply eating less energy than you use over time. A gentle one is safer and easier to keep up."),
        q("Nutrition", "Does eating late at night make you gain fat by itself?", "No: what matters most is your total intake over time", listOf("Yes, food after 8pm is stored as fat", "Yes, but only carbohydrate", "Only on weekdays"),
            "Timing matters less than the total amount and quality of what you eat. Late snacks can add up, though."),
        q("Nutrition", "A common starting guide for daily water for an adult is about…", "30 to 35 ml per kg of body weight", listOf("5 to 10 ml per kg", "80 to 100 ml per kg", "One litre for everyone"),
            "Needs vary with heat, activity and food. Pale yellow urine is a handy sign you are drinking enough."),
        q("Nutrition", "Which mineral is most important for strong bones?", "Calcium", listOf("Sodium", "Zinc", "Potassium"),
            "Calcium, with vitamin D and weight-bearing exercise, supports bones."),
        q("Nutrition", "Which plate is a good everyday model?", "Half vegetables, a quarter protein, a quarter whole-grain carbohydrate", listOf("Half rice, half meat", "Mostly fruit juice and bread", "All protein"),
            "The plate method gives a simple balance without counting."),
        q("Training", "What is progressive overload?", "Gradually asking more of your muscles over time", listOf("Training until you are exhausted every day", "Changing exercises every session", "Lifting the same weight for a year"),
            "Adding weight, reps, sets or better form a little at a time is what makes you stronger."),
        q("Training", "How often a week do health guidelines say adults should train the main muscle groups?", "At least 2 days", listOf("Once a month", "Every day", "Only on weekends"),
            "The WHO advises muscle-strengthening on 2 or more days a week."),
        q("Training", "How much moderate aerobic activity a week do guidelines suggest for adults?", "150 to 300 minutes", listOf("10 to 20 minutes", "30 minutes in total", "600 to 900 minutes"),
            "That is about 30 to 60 minutes on 5 days, more if you can, and less is still better than nothing."),
        q("Training", "Can you lose fat from just one part of the body by exercising that part?", "No, fat comes off from all over", listOf("Yes, with enough crunches", "Yes, with heavy weights", "Only with cardio"),
            "Spot reduction is a myth. Exercise builds the muscle underneath; fat loss follows your overall energy balance."),
        q("Training", "Which exercise mainly trains the quadriceps and glutes?", "Squat", listOf("Biceps curl", "Lateral raise", "Wrist curl"),
            "Squats work the big muscles of the thighs and hips."),
        q("Training", "What should stay neutral (not rounded) during a deadlift?", "Your spine", listOf("Your knees", "Your wrists", "Your feet"),
            "Hinge at the hips and keep your back flat; light weights while you learn."),
        q("Training", "What is a good warm-up?", "A few minutes of easy cardio, then light versions of the lifts", listOf("Static stretching only", "Nothing, to save energy", "Your heaviest set"),
            "Raise your heart rate and practise the movement lightly before heavy sets."),
        q("Training", "What is a sign you should stop an exercise at once?", "Sharp pain, chest pain or dizziness", listOf("Your muscles feel warm", "You are breathing harder", "You are sweating"),
            "Effort is normal; sharp pain, chest pain or feeling faint is not. Stop, and see a doctor if it continues."),
        q("Training", "What is DOMS?", "Muscle soreness a day or two after unfamiliar exercise", listOf("A kind of protein", "A warm-up routine", "A heart condition"),
            "Delayed onset muscle soreness is common, harmless, and eases with gentle movement."),
        q("Training", "What should a beginner focus on first when lifting?", "Learning good technique with light weights", listOf("The heaviest weight possible", "Training the same muscle every day", "Copying advanced routines"),
            "Good form first; add weight slowly."),
        q("Training", "What does 'reps' mean?", "How many times you repeat a movement in a set", listOf("The weight on the bar", "Minutes of rest", "Days of training"),
            "A set of 10 reps means you did the movement 10 times in a row."),
        q("Training", "What does the 'talk test' tell you?", "At moderate effort you can talk, but not sing", listOf("How much you can lift", "Your resting heart rate", "How much protein you need"),
            "It is an easy way to judge cardio intensity without a gadget."),
        q("Recovery", "How much sleep do most adults need?", "7 to 9 hours", listOf("3 to 4 hours", "5 hours", "12 hours"),
            "Sleep is when your body recovers; short sleep makes training and appetite harder to manage."),
        q("Recovery", "When do your muscles actually grow?", "During recovery after training", listOf("During the workout itself", "Only while you stretch", "Only when you take supplements"),
            "Training is the signal; rest, food and sleep let the muscle adapt."),
        q("Recovery", "What is a rest day for?", "Letting your body recover and adapt", listOf("Losing the progress you made", "Eating less", "Nothing; skip it"),
            "Rest days are part of the plan, not time off from it."),
        q("Recovery", "What is a limit of BMI?", "It cannot tell muscle from fat", listOf("It needs a blood test", "It only works for athletes", "It measures blood pressure"),
            "A muscular person can have a high BMI and little excess fat. It is a rough screen, not a verdict."),
        q("Recovery", "A rough estimate of your maximum heart rate is…", "220 minus your age", listOf("100 plus your age", "Your weight in kilograms", "180 for everyone"),
            "It is only an estimate, and real maximums vary a lot from person to person."),
        q("Supplements", "Which statement about protein shakes is best?", "They can help you reach your protein target but do not replace meals", listOf("They are required to build muscle", "They are always better than food", "They burn fat"),
            "Food comes first; powders are a convenience."),
        q("Supplements", "Creatine monohydrate is best described as…", "One of the most studied supplements for strength training", listOf("A banned drug", "A fat burner", "A replacement for protein"),
            "It has a long research record. Ask a doctor first if you have kidney problems or take medication."),
    )

    /** [count] random questions with their choices in a random order. */
    fun draw(count: Int, random: Random = Random(), topic: String? = null): List<QuizQuestion> {
        val pool = if (topic == null) all else all.filter { it.topic == topic }.ifEmpty { all }
        return pool.shuffled(random).take(count).map { it.shuffled(random) }
    }

    val topics: List<String> get() = all.map { it.topic }.distinct()
}

/** Reading questions the model wrote, which small models do not always format correctly. */
object QuizParser {
    /** The JSON schema the model is held to when it writes questions. */
    val SCHEMA = """{"type":"object","properties":{"questions":{"type":"array","items":{"type":"object","properties":{"question":{"type":"string"},"choices":{"type":"array","items":{"type":"string"}},"answer":{"type":"integer"},"explanation":{"type":"string"}},"required":["question","choices","answer","explanation"]}}},"required":["questions"]}"""

    fun parse(text: String, topic: String): List<QuizQuestion> {
        val array = try {
            val root = JSONObject(text.trim().removePrefix("```json").removeSuffix("```").trim())
            root.optJSONArray("questions") ?: return emptyList()
        } catch (e: JSONException) {
            return emptyList()
        }
        val out = ArrayList<QuizQuestion>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val choices = o.optJSONArray("choices")?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }.orEmpty()
            val question = o.optString("question").trim()
            // "answer" may be a position (0 or 1 based is ambiguous, so only 0..3 is accepted) or a letter.
            val answer = when (val raw = o.opt("answer")) {
                is Number -> raw.toInt()
                is String -> raw.trim().firstOrNull()?.let { c -> if (c.uppercaseChar() in 'A'..'D') c.uppercaseChar() - 'A' else c.digitToIntOrNull() } ?: -1
                else -> -1
            }
            if (question.length < 8 || choices.size != 4 || choices.any { it.isEmpty() } || choices.toSet().size != 4 || answer !in 0..3) continue
            out += QuizQuestion(topic, question, choices, answer, o.optString("explanation").trim())
        }
        return out.distinctBy { it.text.lowercase() }
    }
}

/** A flashcard: something to recall, and its answer. */
data class Flashcard(val id: String, val deck: String, val front: String, val back: String)

object FlashcardDecks {
    const val GYM = "Gym basics"
    const val NUTRITION = "Nutrition basics"
    const val MADE = "Made by Piko"

    private fun card(deck: String, n: Int, front: String, back: String) = Flashcard("$deck#$n".replace(' ', '-').lowercase(), deck, front, back)

    val builtIn: List<Flashcard> = listOf(
        card(GYM, 1, "Squat: key cues", "Feet about shoulder-width, chest up, hips back and down, knees follow the toes, push through the whole foot."),
        card(GYM, 2, "Deadlift: key cues", "Bar over mid-foot, hinge at the hips, flat back, push the floor away, finish by squeezing the glutes without leaning back."),
        card(GYM, 3, "Bench press: key cues", "Shoulder blades pinched, feet planted, bar to the lower chest, wrists over elbows, press up and slightly back."),
        card(GYM, 4, "Overhead press: key cues", "Ribs down, glutes tight, bar rises straight past your face, head moves through at the top."),
        card(GYM, 5, "Barbell row: key cues", "Hinge to about 45 degrees, flat back, pull to the lower ribs, squeeze the shoulder blades."),
        card(GYM, 6, "Push-up: key cues", "Body in one line, hands under shoulders, elbows about 45 degrees from the body, chest to the floor."),
        card(GYM, 7, "Plank: key cues", "Elbows under shoulders, glutes tight, ribs down, do not let the hips sag or rise."),
        card(GYM, 8, "Lunge: key cues", "A long step, front knee over the ankle, back knee toward the floor, upright torso."),
        card(GYM, 9, "Pull-up: key cues", "Start from a dead hang, shoulders down, pull the chest toward the bar, lower slowly."),
        card(GYM, 10, "Hip thrust: key cues", "Upper back on a bench, drive through the heels, squeeze the glutes at the top, chin tucked."),
        card(GYM, 11, "What is progressive overload?", "Gradually doing more over time: more weight, reps, sets or better control."),
        card(GYM, 12, "How long should you rest between sets?", "Heavy strength sets (1 to 5 reps): 2 to 3 minutes. Muscle-building sets (6 to 12 reps): 1 to 2 minutes."),
        card(GYM, 13, "What is RPE?", "Rate of perceived exertion, from 1 to 10. RPE 8 means you could have done about 2 more reps."),
        card(GYM, 14, "A simple warm-up", "5 to 10 minutes of easy cardio, some dynamic moves, then a few lighter sets of the first exercise."),
        card(NUTRITION, 1, "Protein: what does it do?", "About 4 kcal a gram. Builds and repairs muscle and other tissue. Eggs, fish, meat, dairy, pulses, tofu."),
        card(NUTRITION, 2, "Carbohydrate: what does it do?", "About 4 kcal a gram. The body's main fuel for hard exercise. Choose mostly whole grains, fruit, vegetables and pulses."),
        card(NUTRITION, 3, "Fat: what does it do?", "About 9 kcal a gram. Needed for hormones and for absorbing vitamins A, D, E and K. Nuts, seeds, oils, fish."),
        card(NUTRITION, 4, "How much fibre a day?", "Around 25 to 30 g for most adults, from whole grains, fruit, vegetables and pulses."),
        card(NUTRITION, 5, "Rule of thumb for protein when training", "Roughly 1.2 to 2.0 g per kilogram of body weight a day, spread over meals."),
        card(NUTRITION, 6, "How big a calorie deficit is gentle?", "About 300 to 500 kcal a day below what you use, which is roughly 0.25 to 0.5 kg of loss a week."),
        card(NUTRITION, 7, "How do you absorb more iron from plants?", "Eat them with vitamin C (lemon, tomato, citrus); keep tea and coffee away from the meal."),
        card(NUTRITION, 8, "Added sugar limit", "Under 10% of daily energy (under 5% is better), say the WHO."),
        card(NUTRITION, 9, "Salt limit", "Under 5 g of salt (2 g of sodium) a day for adults, say the WHO."),
        card(NUTRITION, 10, "The plate method", "Half vegetables and fruit, a quarter protein, a quarter whole-grain carbohydrate."),
        card(NUTRITION, 11, "Before training", "A meal with carbohydrate and some protein 2 to 3 hours before, or a light snack 30 to 60 minutes before."),
        card(NUTRITION, 12, "After training", "Protein (about 20 to 40 g) with carbohydrate within a few hours, and water."),
        card(NUTRITION, 13, "Who needs vitamin B12?", "Vegans, and many vegetarians and older adults, from fortified foods or a supplement."),
        card(NUTRITION, 14, "Omega-3 sources", "Oily fish; flaxseed, chia and walnuts for plant sources."),
        card(NUTRITION, 15, "Hydration check", "Pale yellow urine means you are probably drinking enough."),
    )

    val decks: List<String> get() = builtIn.map { it.deck }.distinct()

    const val SCHEMA = """{"type":"object","properties":{"cards":{"type":"array","items":{"type":"object","properties":{"front":{"type":"string"},"back":{"type":"string"}},"required":["front","back"]}}},"required":["cards"]}"""

    /** Cards the model wrote; ones that are empty, too long or repeated are dropped. */
    fun parseMade(text: String): List<Flashcard> {
        val array = try {
            JSONObject(text.trim().removePrefix("```json").removeSuffix("```").trim()).optJSONArray("cards") ?: return emptyList()
        } catch (e: JSONException) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val front = o.optString("front").trim()
            val back = o.optString("back").trim()
            if (front.length !in 3..160 || back.length !in 3..500) null
            else Flashcard("made-" + HealthLogs.newId(), MADE, front, back)
        }.distinctBy { it.front.lowercase() }
    }

    fun madeToJson(cards: List<Flashcard>): String =
        JSONArray(cards.map { JSONObject().put("id", it.id).put("front", it.front).put("back", it.back) }).toString()

    fun madeFromJson(text: String?): List<Flashcard> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(text)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                Flashcard(o.getString("id"), MADE, o.getString("front"), o.getString("back"))
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * Spaced repetition with five boxes (the Leitner method): a card you remember moves up a box and comes back later; one you
 * miss goes back to the first. Progress is kept as card id to (box, day it is due).
 */
object Leitner {
    /** Days to wait before a card in box 1 to 5 is shown again. */
    val intervals = listOf(0, 1, 3, 7, 14)

    data class Progress(val box: Int, val due: Long)

    fun dueCards(cards: List<Flashcard>, progress: Map<String, Progress>, today: Long): List<Flashcard> =
        cards.filter { (progress[it.id]?.due ?: Long.MIN_VALUE) <= today }
            .sortedWith(compareBy({ progress[it.id]?.box ?: 0 }, { it.id }))

    fun answered(progress: Map<String, Progress>, card: Flashcard, remembered: Boolean, today: Long): Map<String, Progress> {
        val box = progress[card.id]?.box ?: 1
        val next = if (remembered) (box + 1).coerceAtMost(intervals.size) else 1
        return progress + (card.id to Progress(next, today + intervals[next - 1]))
    }

    /** How many cards are known (box 4 or 5) out of [cards]. */
    fun known(cards: List<Flashcard>, progress: Map<String, Progress>): Int = cards.count { (progress[it.id]?.box ?: 0) >= 4 }

    fun toJson(progress: Map<String, Progress>): String =
        JSONObject(progress.mapValues { JSONArray(listOf(it.value.box, it.value.due)) }).toString()

    fun fromJson(text: String?): Map<String, Progress> {
        if (text.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(text)
            o.keys().asSequence().mapNotNull { key ->
                val a = o.optJSONArray(key) ?: return@mapNotNull null
                key to Progress(a.getInt(0).coerceIn(1, intervals.size), a.getLong(1))
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}
