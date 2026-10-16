package com.pocketlm.app.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
        override suspend fun complete(request: ChatRequest, onToken: (String) -> Unit): Int {
            last = request
            failWith?.let { throw it }
            listOf("Hel", "lo", " there").forEach(onToken)
            return 3
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
}
