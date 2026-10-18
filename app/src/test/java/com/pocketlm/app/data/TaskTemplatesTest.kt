package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskTemplatesTest {

    @Test
    fun everyTemplateCanBeSavedAsItIs() {
        for (t in TaskTemplates.all) {
            assertNull("${t.id}: ${Schedules.problem(t.toSchedule("x"))}", Schedules.problem(t.toSchedule("x")))
            assertTrue(t.title.isNotBlank() && t.blurb.isNotBlank())
        }
    }

    @Test
    fun templatesAreDistinct() {
        assertEquals(TaskTemplates.all.size, TaskTemplates.all.map { it.id }.toSet().size)
        assertEquals(TaskTemplates.all.size, TaskTemplates.all.map { it.title }.toSet().size)
    }

    @Test
    fun aTemplateThatNeedsToolsAsksForThemWhenRunning() {
        for (t in TaskTemplates.all) assertEquals(t.id, t.needs.isNotEmpty(), t.toSchedule("x").tools)
        val news = TaskTemplates.all.first { it.id == "news-digest" }
        assertEquals(setOf(ToolId.WebSearch), TaskTemplates.missing(news, emptySet()))
        assertEquals(emptySet<ToolId>(), TaskTemplates.missing(news, setOf(ToolId.WebSearch, ToolId.Calculator)))
    }

    @Test
    fun nothingNeedsAToolThatAScheduledRunCannotUse() {
        val allowed = ToolLoop.allowed(ToolId.entries.toSet())
        for (t in TaskTemplates.all) assertTrue(t.id, allowed.containsAll(t.needs))
    }

    @Test
    fun aTemplateThatReadsNotesSaysSo() {
        assertEquals(NotesUse.Recent, TaskTemplates.all.first { it.id == "notes-recap" }.toSchedule("x").notes)
    }

    @Test
    fun tasksKeepTheirToolsAndLastChatWhenSaved() {
        val s = ScheduledPrompt("1", "Digest", "Search the web", 8, 0, tools = true, lastChat = "chat-9")
        val back = Schedules.fromJson(Schedules.toJson(listOf(s))).single()
        assertTrue(back.tools)
        assertEquals("chat-9", back.lastChat)
        // A task saved before these existed still loads.
        val old = Schedules.fromJson("""[{"id":"a","title":"t","prompt":"p","hour":8,"minute":0,"days":127}]""").single()
        assertEquals(false, old.tools)
        assertNull(old.lastChat)
    }

    @Test
    fun jobsKeepTheirToolsFlag() {
        val job = PromptJob("j", PromptJob.Kind.Scheduled, "p", tools = true)
        assertTrue(PromptJobs.fromJson(PromptJobs.toJson(job))!!.tools)
        assertEquals(false, PromptJobs.fromJson(PromptJobs.toJson(job.copy(tools = false)))!!.tools)
    }
}
