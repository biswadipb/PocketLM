package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ApiToolsTest {

    private val weather = ApiTool(
        "get_weather", "Current weather for a city",
        """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""",
    )
    private val time = ApiTool("get_time", "", """{"type":"object","properties":{}}""")
    private val tools = listOf(weather, time)

    private fun toolsJson(vararg names: String) = JSONArray(names.map {
        JSONObject().put("type", "function").put(
            "function", JSONObject().put("name", it).put("description", "does $it").put("parameters", JSONObject().put("type", "object")),
        )
    })

    // ---- Reading the request ----------------------------------------------------------

    @Test
    fun toolsAreReadFromTheRequest() {
        val parsed = ApiTools.parseTools(toolsJson("get_weather", "get_time"))
        assertEquals(listOf("get_weather", "get_time"), parsed.map { it.name })
        assertEquals("does get_weather", parsed[0].description)
        assertTrue(ApiTools.parseTools(null).isEmpty())
        assertTrue(ApiTools.parseTools(JSONArray()).isEmpty())
    }

    @Test
    fun badToolsAreRefusedWithAnExplanation() {
        fun rejected(array: JSONArray, part: String) {
            try {
                ApiTools.parseTools(array)
                fail("accepted: $array")
            } catch (e: ApiException) {
                assertEquals(400, e.status)
                assertTrue(e.message!!, e.message!!.contains(part))
            }
        }
        rejected(JSONArray().put("not an object"), "object")
        rejected(JSONArray().put(JSONObject().put("type", "retrieval")), "function")
        rejected(JSONArray().put(JSONObject().put("type", "function")), "function")
        rejected(toolsJson("bad name!"), "name")
        rejected(toolsJson("same", "same"), "same name")
        rejected(JSONArray((1..33).map { toolsJson("t$it").getJSONObject(0) }), "At most")
    }

    @Test
    fun theToolChoiceIsReadInAllItsForms() {
        val parsed = ApiTools.parseTools(toolsJson("get_weather"))
        assertEquals(ToolChoice.Auto, ApiTools.parseChoice(null, parsed))
        assertEquals(ToolChoice.Auto, ApiTools.parseChoice("auto", parsed))
        assertEquals(ToolChoice.None, ApiTools.parseChoice("none", parsed))
        assertEquals(ToolChoice.Required, ApiTools.parseChoice("required", parsed))
        val named = JSONObject().put("type", "function").put("function", JSONObject().put("name", "get_weather"))
        assertEquals(ToolChoice.Function("get_weather"), ApiTools.parseChoice(named, parsed))
        try {
            ApiTools.parseChoice(JSONObject().put("function", JSONObject().put("name", "missing")), parsed)
            fail()
        } catch (e: ApiException) {
            assertEquals(400, e.status)
        }
        try {
            ApiTools.parseChoice("sometimes", parsed)
            fail()
        } catch (e: ApiException) {
            assertEquals(400, e.status)
        }
    }

    @Test
    fun functionsAreOnlyOfferedWhenThereAreSomeAndTheCallerDidNotSayNone() {
        assertTrue(ApiTools.active(tools, ToolChoice.Auto))
        assertTrue(ApiTools.active(tools, ToolChoice.Required))
        assertFalse(ApiTools.active(tools, ToolChoice.None))
        assertFalse(ApiTools.active(emptyList(), ToolChoice.Auto))
    }

    @Test
    fun assistantCallsAreReadFromEarlierMessages() {
        val calls = ApiTools.parseToolCalls(
            JSONArray().put(
                JSONObject().put("id", "call_1").put("type", "function")
                    .put("function", JSONObject().put("name", "get_weather").put("arguments", """{"city":"Pune"}""")),
            ).put(JSONObject().put("function", JSONObject())),
        )
        assertEquals(listOf(ApiToolCall("call_1", "get_weather", """{"city":"Pune"}""")), calls)
    }

    // ---- Telling the model ------------------------------------------------------------------

    @Test
    fun theSystemBlockDescribesEveryFunctionAndHowToCallOne() {
        val block = ApiTools.systemBlock(tools, ToolChoice.Auto)
        assertTrue(block.contains("<tools>") && block.contains("</tools>"))
        assertTrue(block.contains("\"name\":\"get_weather\""))
        assertTrue(block.contains("Current weather for a city"))
        assertTrue(block.contains("\"required\":[\"city\"]"))
        assertTrue(block.contains("<tool_call>"))
        assertTrue(block.contains("Only call a function when it is needed"))
        assertTrue(ApiTools.systemBlock(tools, ToolChoice.Required).contains("must call at least one"))
        assertTrue(ApiTools.systemBlock(tools, ToolChoice.Function("get_time")).contains("must call the function \"get_time\""))
    }

    @Test
    fun aToolConversationBecomesTurnsTheModelCanFollow() {
        val messages = listOf(
            ApiMessage("system", "Be brief."),
            ApiMessage("user", "Weather in Pune?"),
            ApiMessage("assistant", "", toolCalls = listOf(ApiToolCall("call_1", "get_weather", """{"city":"Pune"}"""))),
            ApiMessage("tool", """{"temp":31}""", toolCallId = "call_1"),
        )
        val c = ApiTools.conversation(messages)
        assertEquals(2, c.earlier.size)
        assertTrue(c.earlier[0].isUser && c.earlier[0].text == "Weather in Pune?")
        assertFalse(c.earlier[1].isUser)
        assertTrue(c.earlier[1].text.contains("<tool_call>") && c.earlier[1].text.contains("get_weather") && c.earlier[1].text.contains("Pune"))
        assertEquals("<tool_response>\n{\"temp\":31}\n</tool_response>", c.question)
    }

    @Test
    fun resultsOfSeveralCallsAreAnsweredTogether() {
        val c = ApiTools.conversation(
            listOf(
                ApiMessage("user", "Both please"),
                ApiMessage("assistant", "", toolCalls = listOf(ApiToolCall("a", "get_weather", "{}"), ApiToolCall("b", "get_time", "{}"))),
                ApiMessage("tool", "sunny", toolCallId = "a"),
                ApiMessage("tool", "noon", toolCallId = "b"),
            ),
        )
        assertEquals(2, c.earlier.size)
        assertEquals("<tool_response>\nsunny\n</tool_response>\n<tool_response>\nnoon\n</tool_response>", c.question)
    }

    @Test
    fun anOrdinaryConversationIsUnchanged() {
        val c = ApiTools.conversation(
            listOf(ApiMessage("system", "s"), ApiMessage("user", "one"), ApiMessage("assistant", "two"), ApiMessage("user", "three")),
        )
        assertEquals("three", c.question)
        assertEquals(listOf("one" to true, "two" to false), c.earlier.map { it.text to it.isUser })
    }

    @Test
    fun aConversationWithoutAUserMessageIsRefused() {
        try {
            ApiTools.conversation(listOf(ApiMessage("system", "s"), ApiMessage("assistant", "hi")))
            fail()
        } catch (e: ApiException) {
            assertEquals(400, e.status)
        }
    }

    // ---- Reading the model's answer -------------------------------------------------------------

    private var counter = 0
    private fun parse(raw: String) = ApiTools.parseReply(raw, tools) { "id${++counter}" }

    @Test
    fun aToolCallBlockBecomesACall() {
        val parsed = parse("""I'll check.<tool_call>
{"name": "get_weather", "arguments": {"city": "Pune"}}
</tool_call>""")
        assertEquals("I'll check.", parsed.content)
        assertEquals(1, parsed.calls.size)
        assertEquals("get_weather", parsed.calls[0].name)
        assertEquals("Pune", JSONObject(parsed.calls[0].argumentsJson).getString("city"))
    }

    @Test
    fun severalBlocksGiveSeveralCallsInOrder() {
        val parsed = parse(
            "<tool_call>{\"name\":\"get_time\",\"arguments\":{}}</tool_call>\n" +
                "<tool_call>{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Goa\"}}</tool_call>",
        )
        assertNull(parsed.content)
        assertEquals(listOf("get_time", "get_weather"), parsed.calls.map { it.name })
        assertEquals(parsed.calls.map { it.id }.toSet().size, 2)
    }

    @Test
    fun aMissingClosingTagIsForgiven() {
        val parsed = parse("""<tool_call>{"name":"get_time","arguments":{}}""")
        assertEquals(listOf("get_time"), parsed.calls.map { it.name })
    }

    @Test
    fun aBareJsonAnswerOrOneInACodeFenceIsACallToo() {
        assertEquals("get_weather", parse("""{"name":"get_weather","parameters":{"city":"Delhi"}}""").calls.single().name)
        assertEquals("get_time", parse("```json\n{\"name\":\"get_time\",\"arguments\":{}}\n```").calls.single().name)
    }

    @Test
    fun argumentsGivenAsTextAreKeptAsAnObject() {
        val call = parse("""<tool_call>{"name":"get_weather","arguments":"{\"city\":\"Pune\"}"}</tool_call>""").calls.single()
        assertEquals("Pune", JSONObject(call.argumentsJson).getString("city"))
        assertEquals("{}", parse("""<tool_call>{"name":"get_time"}</tool_call>""").calls.single().argumentsJson)
    }

    @Test
    fun aCallToAFunctionThatWasNotOfferedStaysPlainText() {
        val parsed = parse("""<tool_call>{"name":"delete_everything","arguments":{}}</tool_call>""")
        assertTrue(parsed.calls.isEmpty())
        assertTrue(parsed.content!!.contains("delete_everything"))
    }

    @Test
    fun ordinaryAnswersHaveNoCalls() {
        val parsed = parse("It is 31 degrees in Pune.")
        assertTrue(parsed.calls.isEmpty())
        assertEquals("It is 31 degrees in Pune.", parsed.content)
        assertNull(parse("   ").content)
        assertTrue(parse("not json { at all").calls.isEmpty())
    }

    @Test
    fun thinkingBeforeACallIsLeftOut() {
        val parsed = parse("<think>The user wants the time.</think>\n<tool_call>{\"name\":\"get_time\",\"arguments\":{}}</tool_call>")
        assertEquals(listOf("get_time"), parsed.calls.map { it.name })
        assertNull(parsed.content)
    }

    @Test
    fun callIdsLookLikeOpenAisAndDiffer() {
        val a = ApiTools.newId()
        val b = ApiTools.newId()
        assertTrue(a.startsWith("call_") && a.length == 29)
        assertTrue(a != b)
    }
}
