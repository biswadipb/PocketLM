package com.pocketlm.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillsTest {
    private fun skill(name: String, id: String = name, on: Boolean = true, description: String = "Does $name.") =
        Skill(id, name, description, "Steps for $name.", on)

    @Test
    fun namesAreMadeIntoHyphenatedWords() {
        assertEquals("prep-for-meetings", Skills.slug("Prep for Meetings"))
        assertEquals("a-b-c", Skills.slug("  a__b!!c--"))
        assertEquals("", Skills.slug("???"))
        assertEquals(Skills.MAX_NAME_CHARS, Skills.slug("x".repeat(100)).length)
    }

    @Test
    fun explainsWhatIsWrongWithASkill() {
        fun problem(s: Skill, others: List<Skill> = emptyList()) = Skills.problem(s, others)
        assertNull(problem(skill("good-name")))
        assertTrue(problem(skill(""))!!.contains("name"))
        assertTrue(problem(skill("Bad Name"))!!.contains("lower case"))
        assertTrue(problem(skill("x"))!!.contains("too short"))
        assertTrue(problem(skill("dup", id = "2"), listOf(skill("dup", id = "1")))!!.contains("already have"))
        assertNull(problem(skill("dup", id = "1"), listOf(skill("dup", id = "1"))))
        assertTrue(problem(skill("ok", description = " "))!!.contains("description"))
        assertTrue(problem(skill("ok", description = "d".repeat(201)))!!.contains("200"))
        assertTrue(problem(skill("ok").copy(instructions = ""))!!.contains("instructions"))
        assertTrue(problem(skill("ok").copy(instructions = "i".repeat(4001)))!!.contains("4000"))
    }

    @Test
    fun skillsSurviveBeingSaved() {
        val list = listOf(skill("one"), skill("two", on = false).copy(source = "two"))
        assertEquals(list, Skills.fromJson(Skills.toJson(list)))
        assertEquals(emptyList<Skill>(), Skills.fromJson(null))
        assertEquals(emptyList<Skill>(), Skills.fromJson("not json"))
    }

    @Test
    fun badOrRepeatedEntriesAreLeftOutWhenLoading() {
        val json = Skills.toJson(listOf(skill("one"), skill("one", id = "other"), skill("two").copy(instructions = "")))
        assertEquals(listOf("one"), Skills.fromJson(json).map { it.name })
    }

    @Test
    fun theModelMayWriteANameLooselyAndOnlySwitchedOnSkillsAreFound() {
        val list = listOf(skill("prep-for-meetings"), skill("quiz-me", on = false))
        assertEquals("prep-for-meetings", Skills.find(list, "Prep For Meetings")?.name)
        assertEquals("prep-for-meetings", Skills.find(list, "prep_for_meetings")?.name)
        assertNull(Skills.find(list, "quiz-me"))
        assertNull(Skills.find(list, "nothing"))
        assertNull(Skills.find(list, " "))
    }

    @Test
    fun theListShownToTheModelHasOneLinePerSwitchedOnSkill() {
        val list = listOf(skill("a-skill", description = "First  thing."), skill("b-skill", on = false), skill("c-skill"))
        assertEquals("- a-skill: First thing.\n- c-skill: Does c-skill.", Skills.listing(list))
        assertEquals("", Skills.listing(emptyList()))
        val many = List(30) { skill("skill-$it", description = "d".repeat(150)) }
        assertTrue(Skills.listing(many).length <= 1500)
    }

    @Test
    fun theSkillToolIsOfferedOnlyWhileASkillIsOn() {
        val on = setOf(ToolId.Calculator)
        assertEquals(on, Tools.active(on, emptyList()))
        assertEquals(on, Tools.active(on, listOf(skill("off", on = false))))
        assertEquals(on + ToolId.Skill, Tools.active(on, listOf(skill("on"))))
        assertEquals(setOf(ToolId.Skill), Tools.active(emptySet(), listOf(skill("on"))))
        assertEquals(on, Tools.active(on + ToolId.Skill, emptyList()))
    }

    @Test
    fun theInstructionsListTheSkills() {
        val list = listOf(skill("prep-for-meetings", description = "Prep for meetings."))
        val text = Tools.instructions(Tools.active(emptySet(), list), list)
        assertTrue(text.contains("- skill: loads the full instructions"))
        assertTrue(text.contains("- prep-for-meetings: Prep for meetings."))
        assertFalse(Tools.instructions(setOf(ToolId.Calculator), list).contains("prep-for-meetings"))
        assertEquals("system\n\n$text", Tools.withInstructions("system", Tools.active(emptySet(), list), list))
        assertEquals("system", Tools.withInstructions("system", emptySet(), list))
    }

    private fun run(input: String, skills: List<Skill>) = runBlocking {
        Tools.run(ToolCall("skill", input), setOf(ToolId.Skill), context = ToolContext(skills = { skills }))
    }

    @Test
    fun loadingASkillReturnsItsInstructions() {
        val out = run("Prep-for-meetings", listOf(skill("prep-for-meetings")))
        assertTrue(out.modelText, out.modelText.startsWith("Instructions of the skill prep-for-meetings."))
        assertTrue(out.modelText.contains("Steps for prep-for-meetings."))
        assertEquals("prep-for-meetings", out.shownText)
    }

    @Test
    fun askingForASkillThatIsNotThereListsWhatIs() {
        val out = run("nothing", listOf(skill("one"), skill("two", on = false)))
        assertTrue(out.modelText.contains("no skill called 'nothing'"))
        assertTrue(out.modelText.endsWith("The skills are: one."))
        assertEquals("no such skill", out.shownText)
    }

    @Test
    fun everyReadyMadeSkillIsValid() {
        for (entry in SkillCatalog.all) {
            assertNull("${entry.id}: ${Skills.problem(entry.skill, emptyList())}", Skills.problem(entry.skill, emptyList()))
            assertTrue(entry.category in SkillCatalog.categories)
            assertEquals(entry.id, entry.skill.source)
            assertTrue("${entry.id} is too long for a small model", entry.skill.instructions.length < 1500)
        }
        assertEquals(SkillCatalog.all.size, SkillCatalog.all.map { it.id }.toSet().size)
        assertEquals(SkillCatalog.categories.toSet(), SkillCatalog.all.map { it.category }.toSet())
    }

    @Test
    fun theCatalogCanBeSearchedAndShowsWhatIsAdded() {
        assertEquals(listOf("prep-for-meetings"), SkillCatalog.search("MEETINGS", SkillCatalog.EVERYDAY).map { it.id })
        assertTrue(SkillCatalog.search("", SkillCatalog.STUDY).size >= 3)
        assertTrue(SkillCatalog.search("zzzz").isEmpty())
        val added = SkillCatalog.byId("quiz-me")!!.skill.copy(id = "uuid")
        assertTrue(SkillCatalog.isAdded("quiz-me", listOf(added)))
        assertFalse(SkillCatalog.isAdded("flashcards", listOf(added)))
        // One the person renamed still counts as added, because it remembers where it came from.
        assertTrue(SkillCatalog.isAdded("quiz-me", listOf(added.copy(name = "my-quiz"))))
    }
}
