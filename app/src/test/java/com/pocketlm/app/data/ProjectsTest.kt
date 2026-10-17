package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectsTest {

    private val project = Project("p1", "Thesis", "Answer in British English.", listOf(Attachment("d1", "notes.txt", 1200), Attachment("d2", "paper.pdf", 90000)))

    @Test
    fun projectsSurviveSavingAndLoading() {
        val all = listOf(project, Project("p2", "Empty"))
        assertEquals(all, Projects.fromJson(Projects.toJson(all)))
    }

    @Test
    fun brokenOrMissingDataGivesNoProjects() {
        assertTrue(Projects.fromJson(null).isEmpty())
        assertTrue(Projects.fromJson("").isEmpty())
        assertTrue(Projects.fromJson("not json").isEmpty())
    }

    @Test
    fun theInstructionsFollowTheSystemPrompt() {
        val text = Projects.withInstructions("You help.\n", project)
        assertTrue(text.startsWith("You help.\n\nThis chat belongs to the project \"Thesis\""))
        assertTrue(text.endsWith("Answer in British English."))
    }

    @Test
    fun aChatWithoutAProjectOrWithoutInstructionsIsUnchanged() {
        assertEquals("You help.", Projects.withInstructions("You help.", null))
        assertEquals("You help.", Projects.withInstructions("You help.", Project("p", "No words", "  ")))
    }

    @Test
    fun veryLongInstructionsAreCut() {
        val long = Project("p", "Long", "x".repeat(Projects.MAX_INSTRUCTIONS * 2))
        val text = Projects.withInstructions("S", long)
        assertTrue(text.length < Projects.MAX_INSTRUCTIONS + 200)
    }

    @Test
    fun namesAreTidiedOrRefused() {
        assertEquals("My thesis", Projects.cleanName("  My   thesis \n"))
        assertNull(Projects.cleanName("   "))
        assertEquals(Projects.MAX_NAME, Projects.cleanName("n".repeat(200))!!.length)
    }

    @Test
    fun theProjectOfAChatIsKeptWithTheChat() {
        val chat = SavedChat(id = "c", title = "t", updatedAt = 1, modelFile = null, messages = emptyList(), projectId = "p1")
        val json = ChatCodec.encode(chat)
        assertEquals("p1", json.getString("projectId"))
        assertEquals("p1", ChatCodec.decode(json.toString()).projectId)
        assertNull(ChatCodec.decode(ChatCodec.encode(chat.copy(projectId = null)).toString()).projectId)
    }
}
