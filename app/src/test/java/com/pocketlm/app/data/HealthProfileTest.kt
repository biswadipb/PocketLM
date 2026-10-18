package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthProfileTest {

    private val man = HealthProfile(age = 30, sex = Sex.Male, heightCm = 180, weightKg = 80f, activity = Activity.Light, goal = Goal.Maintain)

    @Test
    fun restingEnergyUsesTheMifflinStJeorEquation() {
        assertEquals(1780.0, HealthMath.bmr(man), 0.01) // 10*80 + 6.25*180 - 5*30 + 5
        val woman = HealthProfile(age = 28, sex = Sex.Female, heightCm = 165, weightKg = 55f)
        assertEquals(1280.25, HealthMath.bmr(woman), 0.01)
        assertEquals(1780.0 - 83, HealthMath.bmr(man.copy(sex = Sex.Other)), 0.01) // halfway between 5 and -161
        assertEquals(1780.0 * 1.375, HealthMath.maintenance(man), 0.01)
    }

    @Test
    fun maintainingGivesMaintenanceAndSensibleMacros() {
        val t = HealthMath.targets(man)!!
        assertEquals(2450, t.calories)
        assertEquals(96, t.proteinG) // 1.2 g per kg
        assertEquals(74, t.fatG) // 27% of the calories
        assertEquals(2650, t.waterMl) // 33 ml per kg
        // The macros add up to roughly the calories.
        assertTrue(Math.abs(t.proteinG * 4 + t.carbsG * 4 + t.fatG * 9 - t.calories) <= 20)
        assertNull(t.note)
    }

    @Test
    fun losingIsAGentleDeficitAndBuildingIsAGentleSurplus() {
        val lose = HealthMath.targets(man.copy(goal = Goal.Lose))!!
        assertEquals(1960, lose.calories) // a fifth below 2447
        assertEquals(128, lose.proteinG) // 1.6 g per kg
        val gain = HealthMath.targets(man.copy(goal = Goal.Gain))!!
        assertEquals(2690, gain.calories) // a tenth above, 245 more
        assertEquals(144, gain.proteinG) // 1.8 g per kg
        assertTrue(lose.calories < 2450 && 2450 < gain.calories)
    }

    @Test
    fun theDeficitIsNeverMoreThan500AndNeverBelowTheFloor() {
        val big = man.copy(weightKg = 140f, heightCm = 190, goal = Goal.Lose, activity = Activity.Active)
        val maintenance = HealthMath.maintenance(big)
        val t = HealthMath.targets(big)!!
        assertTrue(maintenance - t.calories <= 510)

        val small = HealthProfile(age = 40, sex = Sex.Female, heightCm = 150, weightKg = 45f, activity = Activity.Sedentary, goal = Goal.Lose)
        val floorCase = HealthMath.targets(small)!!
        assertEquals(1200, floorCase.calories)
        assertTrue(floorCase.calories <= HealthMath.maintenance(small))
        assertNotNull(floorCase.note)

        // A man whose maintenance is below his floor is not set above what he uses.
        val tiny = HealthProfile(age = 70, sex = Sex.Male, heightCm = 150, weightKg = 45f, activity = Activity.Sedentary, goal = Goal.Lose)
        assertTrue(HealthMath.targets(tiny)!!.calories <= HealthMath.maintenance(tiny) + 5)
    }

    @Test
    fun aLowBmiNeverGetsALosingTarget() {
        val thin = HealthProfile(age = 25, sex = Sex.Female, heightCm = 170, weightKg = 48f, goal = Goal.Lose)
        assertTrue(HealthMath.bmi(thin) < 18.5)
        val t = HealthMath.targets(thin)!!
        assertEquals(Math.round(HealthMath.maintenance(thin) / 10.0) * 10, t.calories.toLong())
        assertTrue(t.note!!.contains("under the healthy range"))
        assertTrue(HealthSafety.cautions(thin).any { it.contains("losing weight is not suggested") })
    }

    @Test
    fun noNumbersAreSetForChildrenOrWhenTheyCouldHarm() {
        assertNull(HealthMath.targets(man.copy(age = 16)))
        assertTrue(HealthMath.noTargetsReason(man.copy(age = 16))!!.contains("adults"))
        assertNull(HealthMath.targets(man.copy(cautions = setOf(Caution.Eating))))
        assertTrue(HealthMath.noTargetsReason(man.copy(cautions = setOf(Caution.Eating)))!!.contains("dietitian"))
        assertNull(HealthMath.targets(man.copy(cautions = setOf(Caution.Pregnant))))
        // A condition or an injury still allows numbers, with a warning to see a professional.
        assertNotNull(HealthMath.targets(man.copy(cautions = setOf(Caution.Condition, Caution.Injury))))
        assertTrue(HealthSafety.cautions(man.copy(cautions = setOf(Caution.Condition))).any { it.contains("doctor") })
        assertTrue(HealthSafety.cautions(man.copy(age = 15)).any { it.contains("under 18") })
    }

    @Test
    fun bmiAndItsBand() {
        assertEquals(24.69, HealthMath.bmi(man), 0.01)
        assertEquals("Healthy range", HealthMath.bmiBand(24.69))
        assertEquals("Under the healthy range", HealthMath.bmiBand(18.4))
        assertEquals("Over the healthy range", HealthMath.bmiBand(27.0))
        assertEquals("Well over the healthy range", HealthMath.bmiBand(31.0))
    }

    @Test
    fun theWaterTargetStaysBetweenOneAndAHalfAndFourLitres() {
        assertEquals(1500, HealthMath.targets(man.copy(weightKg = 40f))!!.waterMl)
        assertEquals(4000, HealthMath.targets(man.copy(weightKg = 200f))!!.waterMl)
    }

    @Test
    fun proteinIsKeptWithinBounds() {
        // A tiny calorie target cannot be mostly protein.
        val t = HealthMath.targets(HealthProfile(age = 40, sex = Sex.Female, heightCm = 150, weightKg = 45f, activity = Activity.Sedentary, goal = Goal.Lose))!!
        assertTrue(t.proteinG * 4 <= t.calories * 0.36)
        assertTrue(t.carbsG >= 0)
    }

    @Test
    fun numbersThatCannotBeRightAreReported() {
        assertTrue(HealthSafety.problems(man).isEmpty())
        assertEquals(1, HealthSafety.problems(man.copy(age = 5)).size)
        assertTrue(HealthSafety.problems(man.copy(heightCm = 80, weightKg = 5f)).size >= 2)
        assertTrue(HealthSafety.problems(man.copy(daysPerWeek = 9)).isNotEmpty())
        assertTrue(HealthSafety.problems(man.copy(notes = "x".repeat(700))).isNotEmpty())
    }

    @Test
    fun aProfileSurvivesBeingSaved() {
        val p = man.copy(
            diet = DietKind.Eggetarian, avoid = "peanuts", equipment = Equipment.Home, experience = Experience.Advanced, daysPerWeek = 5,
            cautions = setOf(Caution.Injury, Caution.Condition), notes = "I travel on Mondays.", goal = Goal.Gain,
        )
        assertEquals(p, HealthProfiles.fromJson(HealthProfiles.toJson(p)))
        assertNull(HealthProfiles.fromJson(null))
        assertNull(HealthProfiles.fromJson("nonsense"))
        assertNull(HealthProfiles.fromJson(HealthProfiles.toJson(man.copy(age = 3)))) // impossible numbers are not loaded
    }

    @Test
    fun theSummaryForTheModelIsPlainAndComplete() {
        val text = HealthProfiles.summary(man.copy(avoid = "peanuts", cautions = setOf(Caution.Injury)))
        assertTrue(text, text.startsWith("About me: 30 years old, man, 180 cm, 80 kg (BMI 24.7)."))
        assertTrue(text, text.contains("I avoid or am allergic to: peanuts."))
        assertTrue(text, text.contains("Take care: an injury or pain."))
        assertTrue(text, text.contains("about 2450 kcal, 96 g protein"))
        // No targets are quoted when none are set.
        assertTrue(!HealthProfiles.summary(man.copy(age = 15)).contains("kcal"))
    }
}
