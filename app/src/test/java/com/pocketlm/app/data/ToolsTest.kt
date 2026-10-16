package com.pocketlm.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ToolsTest {

    private fun calc(e: String) = Calculator.format(Calculator.evaluate(e))

    @Test
    fun calculatorFollowsOperatorPrecedenceAndBrackets() {
        assertEquals("26", calc("2 + 3 * 8"))
        assertEquals("60", calc("(12+8)*3"))
        assertEquals("512", calc("2^3^2"))
        assertEquals("-3", calc("-(1+2)"))
        assertEquals("1", calc("10 % 3"))
        assertEquals("6", calc("12 × 1 ÷ 2"))
    }

    @Test
    fun calculatorKnowsFunctionsAndConstants() {
        assertEquals("4", calc("sqrt(16)"))
        assertEquals("3.141592654", calc("pi"))
        assertEquals("1", calc("log(10)"))
        assertEquals("2", calc("round(1.6)"))
        assertEquals("0.3333333333", calc("1/3"))
        assertEquals("0.3", calc("0.1+0.2"))
    }

    @Test
    fun calculatorRefusesNonsenseWithAClearMessage() {
        fun message(e: String) = try { Calculator.evaluate(e); "no error" } catch (x: IllegalArgumentException) { x.message!! }
        assertEquals("Division by zero", message("1/0"))
        assertTrue(message("2+").contains("ends too soon"))
        assertTrue(message("2+*3").contains("Unexpected"))
        assertTrue(message("(1+2").contains("closing bracket"))
        assertTrue(message("foo(2)").contains("Unknown function"))
        assertTrue(message("sqrt(-4)").contains("negative"))
        assertTrue(message("").contains("Empty"))
    }

    @Test
    fun calculatorCannotBeCrashedByDeepNesting() {
        try {
            Calculator.evaluate("(".repeat(5000) + "1" + ")".repeat(5000))
            fail("expected an error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("nested"))
        }
    }

    @Test
    fun aToolRequestIsFoundInTheModelsText() {
        val call = Tools.parseCall("""Let me check.<tool>{"name":"Calculator","input":"2+2"}""")
        assertEquals(ToolCall("calculator", "2+2"), call)
    }

    @Test
    fun parsingToleratesAClosingTagArgumentsObjectsAndTrailingText() {
        assertEquals("sqrt(9)", Tools.parseCall("""<tool>{"name":"calculator","arguments":{"expression":"sqrt(9)"}}</tool> ok""")?.input)
        assertEquals("", Tools.parseCall("""<tool>{"name":"datetime"}</tool>""")?.input)
        assertEquals("a } b", Tools.parseCall("""<tool>{"name":"wikipedia","input":"a } b"}""")?.input)
    }

    @Test
    fun badOrMissingRequestsAreIgnored() {
        assertNull(Tools.parseCall("No tool here."))
        assertNull(Tools.parseCall("<tool>not json</tool>"))
        assertNull(Tools.parseCall("""<tool>{"input":"x"}</tool>"""))
        assertNull(Tools.parseCall("""<tool>{"name":"calculator","input":"2+"""))
    }

    @Test
    fun instructionsListOnlyTheEnabledTools() {
        assertEquals("", Tools.instructions(emptySet()))
        val text = Tools.instructions(setOf(ToolId.Calculator, ToolId.Wikipedia))
        assertTrue(text.contains("- calculator:") && text.contains("- wikipedia:"))
        assertFalse(text.contains("- datetime:"))
        assertTrue(text.contains(Tools.OPEN_TAG) && text.contains(Tools.CLOSE_TAG))
        assertEquals("Be brief.", Tools.withInstructions("Be brief.", emptySet()))
        assertTrue(Tools.withInstructions("Be brief.", setOf(ToolId.DateTime)).startsWith("Be brief.\n\n"))
    }

    @Test
    fun enabledToolsSurviveSerialising() {
        val set = setOf(ToolId.Calculator, ToolId.DateTime)
        assertEquals(set, Tools.parseEnabled(Tools.serialise(set)))
        assertEquals(emptySet<ToolId>(), Tools.parseEnabled(null))
        assertEquals(setOf(ToolId.Wikipedia), Tools.parseEnabled("nonsense, wikipedia"))
    }

    @Test
    fun runningToolsGivesTextForTheModelAndForTheChat() = runBlocking {
        val all = ToolId.entries.toSet()
        val sum = Tools.run(ToolCall("calculator", "6*7"), all)
        assertEquals("42", sum.shownText)
        assertTrue(sum.modelText.contains("6*7") && sum.modelText.contains("42"))

        val clock = ZonedDateTime.of(2026, 10, 16, 9, 5, 0, 0, ZoneId.of("UTC"))
        assertEquals("Friday 16 October 2026, 09:05 (UTC)", Tools.run(ToolCall("datetime", ""), all, clock).shownText)

        assertTrue(Tools.run(ToolCall("calculator", "1/0"), all).modelText.contains("Division by zero"))
    }

    @Test
    fun aDisabledOrUnknownToolIsRefused() = runBlocking {
        assertTrue(Tools.run(ToolCall("calculator", "1+1"), emptySet()).modelText.contains("not available"))
        assertTrue(Tools.run(ToolCall("shell", "rm -rf /"), ToolId.entries.toSet()).modelText.contains("not available"))
    }

    @Test
    fun theChatLineAndFollowUpMentionTheToolAndResult() {
        val call = ToolCall("calculator", "6*7")
        val outcome = ToolOutcome("The result of 6*7 is 42.", "42")
        assertEquals("*Used Calculator on `6*7` → 42*", Tools.statusLine(call, outcome))
        assertTrue(Tools.followUp(call, outcome).contains("42"))
        assertEquals("*Used Date and time → soon*", Tools.statusLine(ToolCall("datetime", ""), ToolOutcome("x", "soon")))
    }

    @Test
    fun wikipediaPicksTheBestRankedArticleAndTrimsLongText() {
        val json = """{"query":{"pages":{
            "111":{"title":"Second","index":2,"extract":"not this"},
            "222":{"title":"Kotlin","index":1,"extract":"Kotlin is a programming language."}}}}"""
        assertEquals("Kotlin: Kotlin is a programming language.", Wikipedia.parse(json))
        val long = """{"query":{"pages":{"1":{"title":"T","index":1,"extract":"${"word ".repeat(1000)}"}}}}"""
        assertTrue(Wikipedia.parse(long, maxChars = 100)!!.length <= 101)
        assertNull(Wikipedia.parse("""{"batchcomplete":""}"""))
        assertNull(Wikipedia.parse("""{"query":{"pages":{"1":{"title":"T","index":1,"extract":""}}}}"""))
    }
}
