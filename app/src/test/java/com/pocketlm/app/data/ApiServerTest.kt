package com.pocketlm.app.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class ApiServerTest {

    private class FakeBackend : ApiBackend {
        var last: ChatRequest? = null
        var failWith: ApiException? = null
        override fun modelNames() = listOf("fake-model.gguf")
        var embedded: List<String>? = null
        var tokens = listOf("Hel", "lo", " there")
        var embedWorks = true
        override suspend fun embed(inputs: List<String>): EmbeddingsResult {
            if (!embedWorks) return super.embed(inputs)
            embedded = inputs
            return EmbeddingsResult(inputs.mapIndexed { i, t -> floatArrayOf(i + 1f, t.length.toFloat(), 0.5f) }, "fake-embed", inputs.sumOf { it.length })
        }
        override suspend fun complete(request: ChatRequest, onToken: (String) -> Unit): Int {
            last = request
            failWith?.let { throw it }
            tokens.forEach(onToken)
            return tokens.size
        }
    }

    private val backend = FakeBackend()
    private var key: String? = "secret"
    private lateinit var server: ApiServer
    private var port = 0

    @Before
    fun start() {
        server = ApiServer(0, { key }, { backend })
        port = server.start()
    }

    @After
    fun stop() = server.stop()

    private data class Reply(val status: Int, val body: String)

    private fun call(method: String, path: String, body: String? = null, auth: String? = "Bearer secret"): Reply {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5000
        c.readTimeout = 10000
        auth?.let { c.setRequestProperty("Authorization", it) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = c.responseCode
        val text = (if (status >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.readText().orEmpty()
        return Reply(status, text)
    }

    @Test
    fun rootAnswersWithoutAKey() {
        val r = call("GET", "/", auth = null)
        assertEquals(200, r.status)
        assertTrue(r.body.contains("running"))
    }

    @Test
    fun wrongOrMissingKeyIsRejected() {
        assertEquals(401, call("GET", "/v1/models", auth = null).status)
        assertEquals(401, call("GET", "/v1/models", auth = "Bearer nope").status)
    }

    @Test
    fun noKeyConfiguredMeansOpenAccess() {
        key = null
        assertEquals(200, call("GET", "/v1/models", auth = null).status)
    }

    @Test
    fun modelsListsTheLoadedModel() {
        val json = JSONObject(call("GET", "/v1/models").body)
        assertEquals("list", json.getString("object"))
        assertEquals("fake-model.gguf", json.getJSONArray("data").getJSONObject(0).getString("id"))
    }

    @Test
    fun chatCompletionReturnsTheJoinedReply() {
        val r = call("POST", "/v1/chat/completions", """{"messages":[{"role":"user","content":"hi"}],"max_tokens":50}""")
        assertEquals(200, r.status)
        val json = JSONObject(r.body)
        assertEquals("chat.completion", json.getString("object"))
        val choice = json.getJSONArray("choices").getJSONObject(0)
        assertEquals("Hello there", choice.getJSONObject("message").getString("content"))
        assertEquals("stop", choice.getString("finish_reason"))
        assertEquals(3, json.getJSONObject("usage").getInt("completion_tokens"))
        assertEquals(50, backend.last?.maxTokens)
    }

    @Test
    fun hittingMaxTokensReportsLength() {
        val r = call("POST", "/v1/chat/completions", """{"messages":[{"role":"user","content":"hi"}],"max_tokens":3}""")
        assertEquals("length", JSONObject(r.body).getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
    }

    @Test
    fun contentPartsAreJoined() {
        call("POST", "/v1/chat/completions", """{"messages":[{"role":"user","content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}]}""")
        assertEquals("a\nb", backend.last?.messages?.single()?.content)
    }

    @Test
    fun streamingSendsDeltasThenDone() {
        val r = call("POST", "/v1/chat/completions", """{"stream":true,"messages":[{"role":"user","content":"hi"}]}""")
        assertEquals(200, r.status)
        val events = r.body.split("\n\n").filter { it.startsWith("data: ") }.map { it.removePrefix("data: ") }
        assertEquals("[DONE]", events.last())
        val deltas = events.dropLast(1).map { JSONObject(it).getJSONArray("choices").getJSONObject(0) }
        val text = deltas.mapNotNull { it.getJSONObject("delta").optString("content").ifEmpty { null } }.joinToString("")
        assertEquals("Hello there", text)
        assertEquals("stop", deltas.last().getString("finish_reason"))
    }

    @Test
    fun aWrongKeyIsRejectedWithoutWaitingForTheBody() {
        // Announce a 3 MB body but never send it. The server must answer 401 straight away.
        java.net.Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 3000
            s.getOutputStream().apply {
                write(
                    ("POST /v1/chat/completions HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer wrong\r\n" +
                        "Content-Length: 3000000\r\n\r\n").toByteArray()
                )
                flush()
            }
            val status = s.getInputStream().bufferedReader().readLine()
            assertTrue(status, status.contains("401"))
        }
    }

    @Test
    fun tooManyOpenConnectionsAreTurnedAway() {
        val small = ApiServer(0, { key }, { backend }, maxConnections = 1)
        val smallPort = small.start()
        try {
            // This connection sends nothing and holds the only slot.
            java.net.Socket("127.0.0.1", smallPort).use {
                Thread.sleep(300)
                val c = URL("http://127.0.0.1:$smallPort/v1/models").openConnection() as HttpURLConnection
                c.setRequestProperty("Authorization", "Bearer secret")
                c.readTimeout = 5000
                assertEquals(503, c.responseCode)
            }
        } finally {
            small.stop()
        }
    }

    @Test
    fun aStopSequenceEndsTheReplyBeforeIt() {
        // The fake model says "Hel" "lo" " there"; stopping at "lo t" leaves "Hel".
        val r = call("POST", "/v1/chat/completions", """{"stop":"lo t","messages":[{"role":"user","content":"hi"}]}""")
        val choice = JSONObject(r.body).getJSONArray("choices").getJSONObject(0)
        assertEquals("Hel", choice.getJSONObject("message").getString("content"))
        assertEquals("stop", choice.getString("finish_reason"))
        assertEquals(listOf("lo t"), backend.last?.stop)
    }

    @Test
    fun streamingNeverSendsAPartOfAStopSequence() {
        val r = call("POST", "/v1/chat/completions", """{"stream":true,"stop":["lo"],"messages":[{"role":"user","content":"hi"}]}""")
        val events = r.body.split("\n\n").filter { it.startsWith("data: ") }.map { it.removePrefix("data: ") }
        val text = events.dropLast(1).map { JSONObject(it).getJSONArray("choices").getJSONObject(0).getJSONObject("delta").optString("content") }.joinToString("")
        assertEquals("Hel", text)
    }

    @Test
    fun samplingOptionsReachTheBackendAndAreKeptInRange() {
        call("POST", "/v1/chat/completions", """{"temperature":0.2,"top_p":0.5,"messages":[{"role":"user","content":"hi"}]}""")
        assertEquals(0.2f, backend.last?.temperature)
        assertEquals(0.5f, backend.last?.topP)
        call("POST", "/v1/chat/completions", """{"temperature":9,"top_p":0,"messages":[{"role":"user","content":"hi"}]}""")
        assertEquals(2f, backend.last?.temperature)
        assertEquals(0.01f, backend.last?.topP)
        call("POST", "/v1/chat/completions", """{"messages":[{"role":"user","content":"hi"}]}""")
        assertEquals(null, backend.last?.temperature)
    }

    @Test
    fun theTextCompletionsEndpointUsesTheTextLayout() {
        val r = call("POST", "/v1/completions", """{"prompt":"Say hi","max_tokens":10}""")
        assertEquals(200, r.status)
        val json = JSONObject(r.body)
        assertEquals("text_completion", json.getString("object"))
        assertEquals("Hello there", json.getJSONArray("choices").getJSONObject(0).getString("text"))
        assertEquals("Say hi", backend.last?.messages?.single()?.content)
    }

    @Test
    fun streamingTextCompletionsUseTextChunks() {
        val r = call("POST", "/v1/completions", """{"prompt":["Say hi"],"stream":true}""")
        val events = r.body.split("\n\n").filter { it.startsWith("data: ") }.map { it.removePrefix("data: ") }
        assertEquals("[DONE]", events.last())
        val text = events.dropLast(1).joinToString("") { JSONObject(it).getJSONArray("choices").getJSONObject(0).getString("text") }
        assertEquals("Hello there", text)
    }

    @Test
    fun aCompletionWithoutAPromptIsRejected() {
        assertEquals(400, call("POST", "/v1/completions", """{"max_tokens":5}""").status)
    }

    @Test
    fun badRequestsGetClearErrors() {
        assertEquals(400, call("POST", "/v1/chat/completions", "not json").status)
        assertEquals(400, call("POST", "/v1/chat/completions", """{"messages":[{"role":"system","content":"x"}]}""").status)
        assertEquals(404, call("GET", "/v1/nothing").status)
    }

    @Test
    fun backendErrorsKeepTheirStatus() {
        backend.failWith = ApiException(503, "busy")
        val r = call("POST", "/v1/chat/completions", """{"messages":[{"role":"user","content":"hi"}]}""")
        assertEquals(503, r.status)
        assertEquals("busy", JSONObject(r.body).getJSONObject("error").getString("message"))
    }

    // ---- Embeddings -----------------------------------------------------------------

    @Test
    fun embeddingsReturnsOneVectorPerInput() {
        val reply = call("POST", "/v1/embeddings", """{"input":["hello","a longer text"],"model":"x"}""")
        assertEquals(200, reply.status)
        val json = JSONObject(reply.body)
        assertEquals("list", json.getString("object"))
        assertEquals("fake-embed", json.getString("model"))
        val data = json.getJSONArray("data")
        assertEquals(2, data.length())
        assertEquals(1, data.getJSONObject(1).getInt("index") )
        assertEquals("embedding", data.getJSONObject(0).getString("object"))
        val vector = data.getJSONObject(1).getJSONArray("embedding")
        assertEquals(3, vector.length())
        assertEquals(2.0, vector.getDouble(0), 1e-6)
        assertEquals("a longer text".length.toDouble(), vector.getDouble(1), 1e-6)
        assertEquals(listOf("hello", "a longer text"), backend.embedded)
        assertEquals("hello".length + "a longer text".length, json.getJSONObject("usage").getInt("total_tokens"))
    }

    @Test
    fun aSingleStringInputIsAccepted() {
        val reply = call("POST", "/v1/embeddings", """{"input":"just one"}""")
        assertEquals(200, reply.status)
        assertEquals(1, JSONObject(reply.body).getJSONArray("data").length())
    }

    @Test
    fun base64VectorsDecodeToTheSameNumbers() {
        val reply = call("POST", "/v1/embeddings", """{"input":"hello","encoding_format":"base64"}""")
        val text = JSONObject(reply.body).getJSONArray("data").getJSONObject(0).getString("embedding")
        val bytes = java.nio.ByteBuffer.wrap(java.util.Base64.getDecoder().decode(text)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(1f, bytes.float, 0f)
        assertEquals(5f, bytes.float, 0f)
        assertEquals(0.5f, bytes.float, 0f)
    }

    @Test
    fun embeddingsNeedsTheKeyLikeEverythingElse() {
        assertEquals(401, call("POST", "/v1/embeddings", """{"input":"x"}""", auth = null).status)
    }

    @Test
    fun badEmbeddingRequestsGetClearErrors() {
        assertEquals(400, call("POST", "/v1/embeddings", "{}").status)
        assertEquals(400, call("POST", "/v1/embeddings", """{"input":[]}""").status)
        assertEquals(400, call("POST", "/v1/embeddings", """{"input":[[1,2,3]]}""").status)
        assertEquals(400, call("POST", "/v1/embeddings", """{"input":"x","encoding_format":"binary"}""").status)
        assertEquals(400, call("POST", "/v1/embeddings", "not json").status)
        val tooMany = (1..129).joinToString(",") { "\"t$it\"" }
        assertEquals(400, call("POST", "/v1/embeddings", """{"input":[$tooMany]}""").status)
    }

    @Test
    fun withoutAnEmbeddingModelTheClientIsToldWhatToDo() {
        backend.embedWorks = false
        val reply = call("POST", "/v1/embeddings", """{"input":"x"}""")
        assertEquals(503, reply.status)
        assertTrue(reply.body, reply.body.contains("No embedding model"))
    }

    // ---- Tool calls -------------------------------------------------------------------

    private val weatherTool = """[{"type":"function","function":{"name":"get_weather","description":"Weather","parameters":{"type":"object","properties":{"city":{"type":"string"}}}}}]"""
    private val callText = listOf("Let me check. <tool_call>", "{\"name\":\"get_weather\",", "\"arguments\":{\"city\":\"Pune\"}}", "</tool_call>")

    @Test
    fun theModelAsksForAFunctionAndTheCallerGetsToolCalls() {
        backend.tokens = callText
        val reply = call("POST", "/v1/chat/completions", """{"messages":[{"role":"user","content":"Weather in Pune?"}],"tools":$weatherTool}""")
        assertEquals(200, reply.status)
        val choice = JSONObject(reply.body).getJSONArray("choices").getJSONObject(0)
        assertEquals("tool_calls", choice.getString("finish_reason"))
        val message = choice.getJSONObject("message")
        assertEquals("Let me check.", message.getString("content"))
        val toolCall = message.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("function", toolCall.getString("type"))
        assertTrue(toolCall.getString("id").startsWith("call_"))
        assertEquals("get_weather", toolCall.getJSONObject("function").getString("name"))
        assertEquals("Pune", JSONObject(toolCall.getJSONObject("function").getString("arguments")).getString("city"))
        assertEquals(1, backend.last!!.tools.size)
    }

    @Test
    fun anAnswerWithoutACallIsAnOrdinaryReplyEvenWhenFunctionsWereOffered() {
        val reply = call("POST", "/v1/chat/completions", """{"messages":[{"role":"user","content":"Hi"}],"tools":$weatherTool}""")
        val choice = JSONObject(reply.body).getJSONArray("choices").getJSONObject(0)
        assertEquals("stop", choice.getString("finish_reason"))
        assertEquals("Hello there", choice.getJSONObject("message").getString("content"))
        assertFalse(choice.getJSONObject("message").has("tool_calls"))
    }

    @Test
    fun streamedToolCallsArriveAsDeltasThenTheFinishReason() {
        backend.tokens = callText
        val reply = call("POST", "/v1/chat/completions", """{"stream":true,"messages":[{"role":"user","content":"Weather?"}],"tools":$weatherTool}""")
        val chunks = reply.body.lines().filter { it.startsWith("data: {") }.map { JSONObject(it.removePrefix("data: ")) }
        val deltas = chunks.map { it.getJSONArray("choices").getJSONObject(0) }
        assertEquals("assistant", deltas.first().getJSONObject("delta").getString("role"))
        val calls = deltas.mapNotNull { it.getJSONObject("delta").optJSONArray("tool_calls") }
        assertEquals("get_weather", calls.first().getJSONObject(0).getJSONObject("function").getString("name"))
        val arguments = calls.drop(1).joinToString("") { it.getJSONObject(0).getJSONObject("function").getString("arguments") }
        assertEquals("Pune", JSONObject(arguments).getString("city"))
        assertEquals("tool_calls", deltas.last().getString("finish_reason"))
        assertTrue(reply.body.trimEnd().endsWith("data: [DONE]"))
    }

    @Test
    fun toolChoiceNoneKeepsFunctionsOutOfIt() {
        backend.tokens = callText
        val reply = call("POST", "/v1/chat/completions", """{"messages":[{"role":"user","content":"x"}],"tools":$weatherTool,"tool_choice":"none"}""")
        val message = JSONObject(reply.body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        assertFalse(message.has("tool_calls"))
        assertTrue(message.getString("content").contains("<tool_call>"))
        assertFalse(backend.last!!.usesTools)
    }

    @Test
    fun aToolConversationReachesTheModelWithItsResults() {
        val body = """{"messages":[
            {"role":"user","content":"Weather in Pune?"},
            {"role":"assistant","content":null,"tool_calls":[{"id":"call_9","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Pune\"}"}}]},
            {"role":"tool","tool_call_id":"call_9","content":"31 degrees"}],"tools":$weatherTool}"""
        assertEquals(200, call("POST", "/v1/chat/completions", body).status)
        val messages = backend.last!!.messages
        assertEquals(listOf("user", "assistant", "tool"), messages.map { it.role })
        assertEquals("call_9", messages[1].toolCalls.single().id)
        assertEquals("call_9", messages[2].toolCallId)
    }

    @Test
    fun badToolsAreRefusedBeforeAnythingRuns() {
        assertEquals(400, call("POST", "/v1/chat/completions", """{"messages":[{"role":"user","content":"x"}],"tools":[{"type":"function"}]}""").status)
        assertEquals(400, call("POST", "/v1/chat/completions", """{"messages":[{"role":"user","content":"x"}],"tools":$weatherTool,"tool_choice":"maybe"}""").status)
    }
}
