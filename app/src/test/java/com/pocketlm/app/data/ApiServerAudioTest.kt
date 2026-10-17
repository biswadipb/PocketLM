package com.pocketlm.app.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ApiServerAudioTest {

    private class Backend : ApiBackend {
        var heard: Triple<Int, String?, String?>? = null
        var firstBytes: ByteArray? = null
        var prepared: ChatRequest? = null
        var completed: ChatRequest? = null
        var speech = true
        override fun modelNames() = listOf("fake")
        override suspend fun prepare(request: ChatRequest) {
            prepared = request
        }
        override suspend fun transcribe(audio: ByteArray, fileName: String?, language: String?): TranscriptionResult {
            if (!speech) return super.transcribe(audio, fileName, language)
            heard = Triple(audio.size, fileName, language)
            firstBytes = audio.copyOf(4)
            return TranscriptionResult("Hello world", 2.5, "english")
        }
        override suspend fun complete(request: ChatRequest, onToken: (String) -> Unit): Int {
            completed = request
            onToken("ok")
            return 1
        }
    }

    private val backend = Backend()
    private lateinit var server: ApiServer
    private var port = 0

    @Before
    fun start() {
        server = ApiServer(0, { "secret" }, { backend })
        port = server.start()
    }

    @After
    fun stop() = server.stop()

    private class Reply(val status: Int, val body: String)

    private fun upload(
        fields: Map<String, String> = emptyMap(), audio: ByteArray? = byteArrayOf(82, 73, 70, 70, 1, 2, 3), name: String = "talk.wav", auth: String? = "Bearer secret",
        contentType: String? = "multipart/form-data; boundary=BND",
    ): Reply {
        val out = ByteArrayOutputStream()
        fields.forEach { (k, v) ->
            out.write("--BND\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n".toByteArray())
        }
        if (audio != null) {
            out.write("--BND\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
            out.write(audio)
            out.write("\r\n".toByteArray())
        }
        out.write("--BND--\r\n".toByteArray())
        val c = URL("http://127.0.0.1:$port/v1/audio/transcriptions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 5_000
        c.readTimeout = 20_000
        auth?.let { c.setRequestProperty("Authorization", it) }
        contentType?.let { c.setRequestProperty("Content-Type", it) }
        c.outputStream.use { it.write(out.toByteArray()) }
        val status = c.responseCode
        return Reply(status, (if (status >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.readText().orEmpty())
    }

    @Test
    fun aRecordingIsWrittenDownAsJson() {
        val r = upload(mapOf("model" to "whisper-1", "language" to "en"))
        assertEquals(200, r.status)
        assertEquals("Hello world", JSONObject(r.body).getString("text"))
        assertEquals(Triple(7, "talk.wav", "en"), backend.heard)
        assertEquals(listOf<Byte>(82, 73, 70, 70), backend.firstBytes!!.toList())
    }

    @Test
    fun otherAnswerLayoutsAreAvailable() {
        assertEquals("Hello world\n", upload(mapOf("response_format" to "text")).body)
        val verbose = JSONObject(upload(mapOf("response_format" to "verbose_json")).body)
        assertEquals("transcribe", verbose.getString("task"))
        assertEquals("english", verbose.getString("language"))
        assertEquals(2.5, verbose.getDouble("duration"), 0.0)
        assertEquals(400, upload(mapOf("response_format" to "srt")).status)
    }

    @Test
    fun aLargeRecordingIsAccepted() {
        val big = ByteArray(6_000_000) { (it % 251).toByte() }
        val r = upload(audio = big)
        assertEquals(200, r.status)
        assertEquals(6_000_000, backend.heard!!.first)
    }

    @Test
    fun aRequestWithoutAFileIsRefused() {
        assertEquals(400, upload(audio = null).status)
        assertEquals(400, upload(audio = ByteArray(0)).status)
        assertEquals(400, upload(contentType = "application/json").status)
    }

    @Test
    fun theKeyIsRequired() {
        assertEquals(401, upload(auth = null).status)
        assertNull(backend.heard)
    }

    @Test
    fun withoutASpeechModelThePhoneSaysSo() {
        backend.speech = false
        val r = upload()
        assertEquals(503, r.status)
        assertTrue(r.body, r.body.contains("speech model"))
    }

    @Test
    fun theModelNamedInAChatRequestIsPassedOn() {
        val c = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer secret")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write("""{"model":"qwen-3b","messages":[{"role":"user","content":"hi"}]}""".toByteArray()) }
        assertEquals(200, c.responseCode)
        assertEquals("qwen-3b", backend.prepared?.model)
        assertEquals("qwen-3b", backend.completed?.model)
    }
}
