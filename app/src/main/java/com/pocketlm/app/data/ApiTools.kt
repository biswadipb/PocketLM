package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** A function the caller of the local API describes, in the OpenAI `tools` format. */
data class ApiTool(val name: String, val description: String, val parametersJson: String)

/** A request from the model to call one of those functions. [argumentsJson] is a JSON object as text. */
data class ApiToolCall(val id: String, val name: String, val argumentsJson: String)

/** What the caller allows: `auto` (the model decides), `none`, `required`, or one named function. */
sealed interface ToolChoice {
    data object Auto : ToolChoice
    data object None : ToolChoice
    data object Required : ToolChoice
    data class Function(val name: String) : ToolChoice
}

/** The model's reply split into plain text and the function calls it asked for. */
data class ParsedReply(val content: String?, val calls: List<ApiToolCall>)

/**
 * Function calling for the local API, for models that were never trained on a particular template. The functions are
 * described to the model in the system prompt in the widely used `<tool_call>` form (Hermes, Qwen and Llama 3.1 models
 * know it), and the model's `<tool_call>` blocks are turned back into OpenAI `tool_calls`. Pure, so it can be tested.
 */
object ApiTools {
    private const val MAX_TOOLS = 32
    private const val MAX_PARAMETERS_CHARS = 8_000
    private val nameRule = Regex("""[A-Za-z0-9_.-]{1,64}""")

    // ---- Reading the request ----------------------------------------------------------

    fun parseTools(array: JSONArray?): List<ApiTool> {
        if (array == null || array.length() == 0) return emptyList()
        if (array.length() > MAX_TOOLS) throw ApiException(400, "At most $MAX_TOOLS tools per request")
        return List(array.length()) { i ->
            val tool = array.optJSONObject(i) ?: throw ApiException(400, "Each tool must be an object")
            if (tool.optString("type", "function") != "function") throw ApiException(400, "Only tools of type \"function\" are supported")
            val function = tool.optJSONObject("function") ?: throw ApiException(400, "Each tool needs a \"function\" object")
            val name = function.optString("name")
            if (!nameRule.matches(name)) throw ApiException(400, "A function name must be 1 to 64 letters, digits, _ . or -")
            val parameters = function.optJSONObject("parameters")?.toString() ?: """{"type":"object","properties":{}}"""
            if (parameters.length > MAX_PARAMETERS_CHARS) throw ApiException(400, "The parameters of \"$name\" are too long")
            ApiTool(name, function.optString("description"), parameters)
        }.also { tools ->
            if (tools.map { it.name }.toSet().size != tools.size) throw ApiException(400, "Two tools have the same name")
        }
    }

    fun parseChoice(value: Any?, tools: List<ApiTool>): ToolChoice = when (value) {
        null, JSONObject.NULL -> ToolChoice.Auto
        is String -> when (value) {
            "auto" -> ToolChoice.Auto
            "none" -> ToolChoice.None
            "required" -> ToolChoice.Required
            else -> throw ApiException(400, "'tool_choice' must be auto, none, required, or a function")
        }
        is JSONObject -> {
            val name = value.optJSONObject("function")?.optString("name").orEmpty()
            if (tools.none { it.name == name }) throw ApiException(400, "'tool_choice' names a function that is not in 'tools'")
            ToolChoice.Function(name)
        }
        else -> throw ApiException(400, "'tool_choice' is not valid")
    }

    fun parseToolCalls(array: JSONArray?): List<ApiToolCall> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val call = array.optJSONObject(i) ?: return@mapNotNull null
            val function = call.optJSONObject("function") ?: return@mapNotNull null
            val name = function.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val arguments = when (val a = function.opt("arguments")) {
                is String -> a
                is JSONObject -> a.toString()
                else -> "{}"
            }
            ApiToolCall(call.optString("id").ifEmpty { newId() }, name, arguments)
        }
    }

    /** True if functions should be offered to the model for this request. */
    fun active(tools: List<ApiTool>, choice: ToolChoice): Boolean = tools.isNotEmpty() && choice != ToolChoice.None

    // ---- Telling the model ---------------------------------------------------------------

    /** The functions as instructions for the system prompt. */
    fun systemBlock(tools: List<ApiTool>, choice: ToolChoice): String = buildString {
        append("You can call functions to help answer. The functions are described by these JSON schemas:\n<tools>\n")
        for (tool in tools) {
            val function = JSONObject().put("name", tool.name).put("description", tool.description)
                .put("parameters", runCatching { JSONObject(tool.parametersJson) }.getOrDefault(JSONObject()))
            append(JSONObject().put("type", "function").put("function", function)).append('\n')
        }
        append("</tools>\n")
        append("To call a function, write a JSON object inside <tool_call></tool_call> tags, exactly like this:\n")
        append("<tool_call>\n{\"name\": \"<function name>\", \"arguments\": {<arguments as a JSON object>}}\n</tool_call>\n")
        append("You may write several <tool_call> blocks to call several functions. After the results come back, answer in plain text. ")
        when (choice) {
            ToolChoice.Required -> append("You must call at least one function now.")
            is ToolChoice.Function -> append("You must call the function \"${choice.name}\" now.")
            else -> append("Only call a function when it is needed; otherwise just answer.")
        }
    }

    class Conversation(val earlier: List<Turn>, val question: String)

    /**
     * The conversation in the API request as turns for the model: assistant function calls are written out in the same
     * `<tool_call>` form, and results (role `tool`) come back as `<tool_response>` on the user's side. The last turn on
     * the user's side is the one to answer. System messages are left out.
     */
    fun conversation(messages: List<ApiMessage>): Conversation {
        val turns = ArrayList<Turn>()
        for (m in messages) {
            when (m.role) {
                "system" -> {}
                "assistant" -> turns += Turn(false, assistantText(m))
                "tool" -> turns += Turn(true, "<tool_response>\n${m.content}\n</tool_response>")
                else -> turns += Turn(true, m.content)
            }
        }
        val last = turns.indexOfLast { it.isUser }
        if (last < 0) throw ApiException(400, "'messages' needs at least one user message")
        // Results of several calls arrive as several tool messages in a row; they belong together.
        var first = last
        while (first > 0 && turns[first - 1].isUser && turns[first].text.startsWith("<tool_response>") && turns[first - 1].text.startsWith("<tool_response>")) first--
        val question = turns.subList(first, last + 1).joinToString("\n") { it.text }
        return Conversation(turns.take(first), question)
    }

    private fun assistantText(m: ApiMessage): String =
        (listOf(m.content.trim()).filter { it.isNotEmpty() } + m.toolCalls.map {
            "<tool_call>\n" + JSONObject().put("name", it.name).put("arguments", runCatching { JSONObject(it.argumentsJson) }.getOrDefault(JSONObject())) +
                "\n</tool_call>"
        }).joinToString("\n")

    // ---- Reading the model's answer ---------------------------------------------------------

    private val block = Regex("""<tool_call>(.*?)(?:</tool_call>|$)""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val fence = Regex("""^\s*```(?:json)?\s*(.*?)\s*```\s*$""", RegexOption.DOT_MATCHES_ALL)

    /** Splits [raw] into text and calls to the functions in [tools]; calls to unknown functions are left in the text. */
    fun parseReply(raw: String, tools: List<ApiTool>, newId: () -> String = ::newId): ParsedReply {
        val answer = Thinking.answerOnly(raw).ifBlank { raw }.trim()
        val calls = ArrayList<ApiToolCall>()
        val matches = block.findAll(answer).toList()
        for (m in matches) callFrom(m.groupValues[1], tools, newId)?.let { calls += it }
        if (calls.isNotEmpty()) {
            val text = answer.substring(0, matches.first().range.first).trim()
            return ParsedReply(text.ifEmpty { null }, calls)
        }
        // Some models answer with the bare JSON object and no tags.
        callFrom(answer, tools, newId)?.let { return ParsedReply(null, listOf(it)) }
        return ParsedReply(answer.ifEmpty { null }, emptyList())
    }

    private fun callFrom(text: String, tools: List<ApiTool>, newId: () -> String): ApiToolCall? {
        val body = fence.matchEntire(text.trim())?.groupValues?.get(1) ?: text.trim()
        if (!body.startsWith("{")) return null
        val json = try {
            JSONObject(body)
        } catch (e: JSONException) {
            return null
        }
        val name = json.optString("name")
        if (tools.none { it.name == name }) return null
        val arguments = when (val a = json.opt("arguments") ?: json.opt("parameters")) {
            is JSONObject -> a.toString()
            is String -> runCatching { JSONObject(a).toString() }.getOrDefault("{}")
            else -> "{}"
        }
        return ApiToolCall(newId(), name, arguments)
    }

    fun newId(): String = "call_" + UUID.randomUUID().toString().replace("-", "").take(24)
}
