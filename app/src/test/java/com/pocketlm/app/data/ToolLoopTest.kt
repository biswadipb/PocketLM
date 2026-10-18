package com.pocketlm.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLoopTest {
    private val search = setOf(ToolId.WebSearch)

    private fun call(name: String, input: String) = "Let me check. ${Tools.OPEN_TAG}{\"name\":\"$name\",\"input\":\"$input\"}"

    @Test
    fun withoutToolsTheModelIsAskedOnce() = runBlocking {
        val asked = mutableListOf<String>()
        val r = ToolLoop.run("hello", emptySet(), ask = { asked += it; "hi there " }, runTool = { error("no tools") })
        assertEquals(listOf("hello"), asked)
        assertEquals("hi there", r.text)
        assertTrue(r.used.isEmpty())
    }

    @Test
    fun aToolRequestIsRunAndItsResultGoesBack() = runBlocking {
        val asked = mutableListOf<String>()
        val replies = ArrayDeque(listOf(call("websearch", "ai news"), "Here is the digest."))
        val r = ToolLoop.run(
            "digest please", search,
            ask = { asked += it; replies.removeFirst() },
            runTool = { c -> ToolOutcome("results for ${c.input}", "1 results") },
        )
        assertEquals("Here is the digest.", r.text)
        assertEquals(2, asked.size)
        assertTrue(asked[1].contains("Tool result from websearch: results for ai news"))
        assertEquals(listOf("websearch"), r.used.map { it.first.name })
    }

    @Test
    fun aModelThatNeverStopsAskingIsCutOff() = runBlocking {
        var runs = 0
        val r = ToolLoop.run(
            "go", search,
            ask = { call("websearch", "again") },
            runTool = { runs++; ToolOutcome("x", "x") },
        )
        assertEquals(Tools.MAX_ROUNDS, runs)
        assertEquals("Let me check.", r.text)
    }

    @Test
    fun aToolThatIsNotSwitchedOnIsAnswerableToo() = runBlocking {
        val replies = ArrayDeque(listOf(call("weather", "Paris"), "I could not check."))
        val r = ToolLoop.run(
            "weather?", search,
            ask = { replies.removeFirst() },
            runTool = { c -> Tools.run(c, search) },
        )
        assertEquals("I could not check.", r.text)
        assertTrue(r.used.single().second.modelText.contains("not available"))
    }

    @Test
    fun promptsThatRunByThemselvesNeverGetToolsThatNeedAnAnswer() {
        val allowed = ToolLoop.allowed(ToolId.entries.toSet())
        assertTrue(ToolId.SaveNote !in allowed && ToolId.Reminder !in allowed)
        assertTrue(ToolId.WebSearch in allowed && ToolId.Weather in allowed && ToolId.ReadPage in allowed)
        assertEquals(setOf(ToolId.Calculator), ToolLoop.allowed(setOf(ToolId.Calculator, ToolId.SaveNote)))
    }
}
