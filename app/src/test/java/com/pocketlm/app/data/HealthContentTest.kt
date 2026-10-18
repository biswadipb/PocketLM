package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class HealthContentTest {

    // ---- foods -----------------------------------------------------------------------------

    @Test
    fun everyFoodHasSensibleNumbers() {
        assertTrue(FoodTable.all.size >= 60)
        assertEquals(FoodTable.all.size, FoodTable.all.map { it.name }.toSet().size)
        for (food in FoodTable.all) {
            assertTrue(food.name, food.servingG in 1..400 && food.serving.isNotBlank())
            assertTrue(food.name, food.proteinG >= 0 && food.carbsG >= 0 && food.fatG >= 0)
            assertTrue(food.name, food.proteinG + food.carbsG + food.fatG <= 101f) // per 100 g
            // The calories roughly match the macros (4, 4 and 9 per gram; fibre and water explain small gaps).
            val fromMacros = food.proteinG * 4 + food.carbsG * 4 + food.fatG * 9
            assertTrue("${food.name}: ${food.kcal} vs $fromMacros", Math.abs(food.kcal - fromMacros) <= maxOf(15f, food.kcal * 0.2f))
        }
    }

    @Test
    fun aPortionIsScaledFromPer100g() {
        val chicken = FoodTable.all.first { it.name.startsWith("Chicken breast") }
        val entry = chicken.portion(120, day = 5, id = "x")
        assertEquals(198, entry.kcal)
        assertEquals(37.2f, entry.proteinG, 0.01f)
        assertEquals(5L, entry.day)
        assertEquals("Chicken breast, cooked", entry.name)
        assertEquals(0, chicken.portion(0, 1).kcal)
    }

    @Test
    fun foodsAreFoundByNameOrByOtherNames() {
        assertTrue(FoodTable.search("chapati").first().name.startsWith("Roti"))
        assertTrue(FoodTable.search("rice").map { it.name }.containsAll(listOf("White rice, cooked", "Brown rice, cooked")))
        assertEquals("Egg, boiled", FoodTable.search("egg").first().name)
        assertTrue(FoodTable.search("DAHI").any { it.name.startsWith("Curd") })
        assertTrue(FoodTable.search("chicken thigh").all { "thigh" in it.name.lowercase() })
        assertTrue(FoodTable.search("zzzz").isEmpty())
        assertTrue(FoodTable.search("").isEmpty())
        assertTrue(FoodTable.search("a").isEmpty())
        assertEquals(3, FoodTable.search("rice", limit = 3).size)
    }

    // ---- quiz --------------------------------------------------------------------------------

    @Test
    fun everyQuestionIsWellFormed() {
        assertTrue(QuizBank.all.size >= 30)
        for (q in QuizBank.all) {
            assertEquals(q.text, 4, q.choices.size)
            assertEquals(q.text, 4, q.choices.toSet().size)
            assertTrue(q.text, q.answer in 0..3)
            assertTrue(q.text, q.explanation.length > 20)
        }
        assertEquals(QuizBank.all.size, QuizBank.all.map { it.text }.toSet().size)
    }

    @Test
    fun shufflingKeepsTheRightAnswerRight() {
        val random = Random(3)
        for (q in QuizBank.all) {
            val s = q.shuffled(random)
            assertEquals(q.choices[q.answer], s.choices[s.answer])
            assertEquals(q.choices.toSet(), s.choices.toSet())
        }
    }

    @Test
    fun aQuizDrawsDifferentQuestions() {
        val round = QuizBank.draw(8, Random(1))
        assertEquals(8, round.size)
        assertEquals(8, round.map { it.text }.toSet().size)
        assertTrue(QuizBank.draw(5, Random(2), topic = "Training").all { it.topic == "Training" })
        assertEquals(QuizBank.all.size, QuizBank.draw(500, Random(1)).size)
        assertTrue("Nutrition" in QuizBank.topics)
    }

    @Test
    fun questionsFromTheModelAreReadAndBadOnesDropped() {
        val json = """{"questions":[
            {"question":"Which vitamin helps absorb calcium?","choices":["Vitamin D","Vitamin C","Vitamin K","Vitamin B6"],"answer":0,"explanation":"Vitamin D."},
            {"question":"Which of these builds muscle?","choices":["Rest","Sleep","Protein","Heat"],"answer":"C","explanation":"Protein."},
            {"question":"Too few choices here?","choices":["a","b"],"answer":0,"explanation":""},
            {"question":"Same choices twice here?","choices":["a","a","b","c"],"answer":0,"explanation":""},
            {"question":"Answer outside range here?","choices":["a","b","c","d"],"answer":7,"explanation":""},
            {"question":"Short","choices":["a","b","c","d"],"answer":1,"explanation":""},
            {"question":"which vitamin helps absorb calcium?","choices":["w","x","y","z"],"answer":1,"explanation":""}
        ]}"""
        val read = QuizParser.parse(json, "Nutrition")
        assertEquals(2, read.size)
        assertEquals("Vitamin D", read[0].choices[read[0].answer])
        assertEquals("Protein", read[1].choices[read[1].answer])
        assertEquals("Nutrition", read[0].topic)
        assertTrue(QuizParser.parse("not json", "x").isEmpty())
        assertTrue(QuizParser.parse("{}", "x").isEmpty())
        val one = """{"questions":[{"question":"Which food is rich in protein?","choices":["Lentils","Sugar","Butter","Salt"],"answer":0,"explanation":"Pulses are."}]}"""
        assertEquals(1, QuizParser.parse("```json\n$one\n```", "x").size)
    }

    // ---- flashcards ----------------------------------------------------------------------------

    @Test
    fun theBuiltInDecksAreWellFormed() {
        assertEquals(setOf(FlashcardDecks.GYM, FlashcardDecks.NUTRITION), FlashcardDecks.decks.toSet())
        assertEquals(FlashcardDecks.builtIn.size, FlashcardDecks.builtIn.map { it.id }.toSet().size)
        assertTrue(FlashcardDecks.builtIn.all { it.front.length > 5 && it.back.length > 15 })
    }

    @Test
    fun aCardYouRememberComesBackLaterAndOneYouMissStartsOver() {
        val card = FlashcardDecks.builtIn.first()
        var progress = emptyMap<String, Leitner.Progress>()
        assertEquals(listOf(card.id), Leitner.dueCards(listOf(card), progress, today = 100).map { it.id })
        progress = Leitner.answered(progress, card, remembered = true, today = 100)
        assertEquals(Leitner.Progress(2, 101), progress[card.id])
        assertTrue(Leitner.dueCards(listOf(card), progress, 100).isEmpty())
        assertEquals(1, Leitner.dueCards(listOf(card), progress, 101).size)
        progress = Leitner.answered(progress, card, true, 101)
        progress = Leitner.answered(progress, card, true, 104)
        progress = Leitner.answered(progress, card, true, 111)
        assertEquals(Leitner.Progress(5, 125), progress[card.id]) // 14 days
        assertEquals(Leitner.Progress(5, 139), Leitner.answered(progress, card, true, 125)[card.id])
        assertEquals(Leitner.Progress(1, 130), Leitner.answered(progress, card, false, 130)[card.id])
        assertEquals(1, Leitner.known(listOf(card), progress))
        assertEquals(0, Leitner.known(listOf(card), Leitner.answered(progress, card, false, 130)))
    }

    @Test
    fun newCardsComeBeforeOnesAlreadyKnown() {
        val a = FlashcardDecks.builtIn[0]
        val b = FlashcardDecks.builtIn[1]
        val c = FlashcardDecks.builtIn[2]
        val progress = mapOf(a.id to Leitner.Progress(3, 0), b.id to Leitner.Progress(1, 0))
        assertEquals(listOf(c.id, b.id, a.id), Leitner.dueCards(listOf(a, b, c), progress, 5).map { it.id })
    }

    @Test
    fun progressAndMadeCardsSurviveBeingSaved() {
        val progress = mapOf("a" to Leitner.Progress(2, 50), "b" to Leitner.Progress(5, 99))
        assertEquals(progress, Leitner.fromJson(Leitner.toJson(progress)))
        assertTrue(Leitner.fromJson("junk").isEmpty())
        val cards = listOf(Flashcard("made-1", FlashcardDecks.MADE, "Front?", "Back."))
        assertEquals(cards, FlashcardDecks.madeFromJson(FlashcardDecks.madeToJson(cards)))
        assertTrue(FlashcardDecks.madeFromJson(null).isEmpty())
    }

    @Test
    fun cardsFromTheModelAreCheckedForSize() {
        val json = """{"cards":[
            {"front":"What is a rep?","back":"One full movement of an exercise."},
            {"front":"","back":"Empty front"},
            {"front":"What is a set?","back":""},
            {"front":"what is a rep?","back":"A repeat that should be dropped as a duplicate."},
            {"front":"${"x".repeat(200)}","back":"too long a front"}
        ]}"""
        val cards = FlashcardDecks.parseMade(json)
        assertEquals(1, cards.size)
        assertEquals(FlashcardDecks.MADE, cards[0].deck)
        assertTrue(cards[0].id.startsWith("made-"))
        assertTrue(FlashcardDecks.parseMade("nonsense").isEmpty())
    }

    // ---- prompts and what comes back ----------------------------------------------------------

    private val profile = HealthProfile(age = 30, sex = Sex.Male, heightCm = 180, weightKg = 80f, goal = Goal.Gain, avoid = "peanuts", diet = DietKind.Vegetarian)

    @Test
    fun thePlansAskForWhatThePersonHas() {
        val workout = HealthPrompts.workoutPlan(profile, days = 4, minutes = 45, focus = "upper body")
        assertTrue(workout.startsWith("About me: 30 years old"))
        assertTrue(workout.contains("4 training days, about 45 minutes"))
        assertTrue(workout.contains("Focus: upper body"))
        assertTrue(workout.contains("a full gym"))
        assertFalse(workout.contains("injury"))
        assertTrue(HealthPrompts.workoutPlan(profile.copy(cautions = setOf(Caution.Injury)), 3, 30, "").contains("injury"))

        val targets = HealthMath.targets(profile)
        val meals = HealthPrompts.mealPlan(profile, targets, days = 7, extra = "quick breakfasts")
        assertTrue(meals.contains("7-day meal plan"))
        assertTrue(meals.contains("about ${targets!!.calories} kcal and ${targets.proteinG} g protein"))
        assertTrue(meals.contains("vegetarian") && meals.contains("leave out: peanuts") && meals.contains("quick breakfasts"))
        assertTrue(meals.endsWith("End with a shopping list grouped by aisle."))
        assertTrue(HealthPrompts.mealPlan(profile.copy(age = 15), null, 7, "").contains("Do not set calorie numbers"))
    }

    @Test
    fun theCoachAlwaysCarriesTheSafetyRules() {
        assertTrue(HealthPrompts.COACH.startsWith(HealthPrompts.SAFETY))
        for (word in listOf("not a doctor", "Never diagnose", "crash diets", "pregnancy", "under 18")) assertTrue(word, HealthPrompts.SAFETY.contains(word))
        assertTrue(HealthPrompts.coachStart(profile, "sleep").contains("on: sleep"))
        assertFalse(HealthPrompts.coachStart(null, "").contains("About me"))
    }

    @Test
    fun aMealEstimateIsReadOnlyIfItIsPlausible() {
        val ok = MealEstimate.parse("""{"name":"Dal and rice","kcal":520,"protein_g":18.5,"carbs_g":88,"fat_g":8}""")!!
        assertEquals("Dal and rice", ok.name)
        assertEquals(520, ok.kcal)
        assertEquals(18.5f, ok.proteinG, 0.001f)
        assertNotNull(MealEstimate.parse("```json\n{\"name\":\"x\",\"kcal\":100,\"protein_g\":1,\"carbs_g\":1,\"fat_g\":1}\n```"))
        assertNull(MealEstimate.parse("not json"))
        assertNull(MealEstimate.parse("""{"name":"","kcal":100,"protein_g":1,"carbs_g":1,"fat_g":1}"""))
        assertNull(MealEstimate.parse("""{"name":"x","kcal":0,"protein_g":1,"carbs_g":1,"fat_g":1}"""))
        assertNull(MealEstimate.parse("""{"name":"x","kcal":99999,"protein_g":1,"carbs_g":1,"fat_g":1}"""))
        assertNull(MealEstimate.parse("""{"name":"x","kcal":100,"protein_g":-5,"carbs_g":1,"fat_g":1}"""))
        assertNull(MealEstimate.parse("""{"name":"x","kcal":100,"protein_g":9999,"carbs_g":1,"fat_g":1}"""))
        assertNull(MealEstimate.parse("""{"name":"x","kcal":100}"""))
    }
}
