package com.pocketlm.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLContext
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

data class ApiMessage(
    val role: String,
    val content: String,
    /** Function calls the assistant made in this message (a later `tool` message answers each). */
    val toolCalls: List<ApiToolCall> = emptyList(),
    /** For a `tool` message: which call it answers. */
    val toolCallId: String? = null,
)

/**
 * One request to answer. [temperature] and [topP] are null unless the caller chose them; [stop] are
 * sequences that end the reply (and are not included in it).
 */
data class ChatRequest(
    val messages: List<ApiMessage>,
    val stream: Boolean,
    val maxTokens: Int?,
    val temperature: Float? = null,
    val topP: Float? = null,
    val stop: List<String> = emptyList(),
    /** A JSON Schema the reply must follow (`response_format` of type `json_schema`), or null. */
    val jsonSchema: String? = null,
    /** True for `response_format` of type `json_object`: any valid JSON. */
    val jsonObject: Boolean = false,
    /** Functions the model may ask the caller to run (OpenAI `tools`). */
    val tools: List<ApiTool> = emptyList(),
    val toolChoice: ToolChoice = ToolChoice.Auto,
) {
    /** True if the model should be offered the functions and its answer read for calls to them. */
    val usesTools: Boolean get() = ApiTools.active(tools, toolChoice)
}

/** An error with the HTTP status the client should see. */
class ApiException(val status: Int, message: String) : Exception(message)

/** Vectors made for the inputs of an embeddings request, one per input, in order. */
class EmbeddingsResult(val vectors: List<FloatArray>, val model: String, val tokens: Int)

/** The part of the app the server calls to get a reply. */
interface ApiBackend {
    /** Models to list, the loaded one first. */
    fun modelNames(): List<String>

    /** Turns each of [inputs] into an embedding vector. Fails with an [ApiException] if there is no embedding model. */
    suspend fun embed(inputs: List<String>): EmbeddingsResult =
        throw ApiException(503, "No embedding model is selected. Choose one in PocketLM, Settings, Document search.")

    /** Writes a reply, passing each piece to [onToken]. Returns how many pieces were produced. */
    suspend fun complete(request: ChatRequest, onToken: (String) -> Unit): Int
}

/**
 * A small OpenAI-compatible HTTP server: `GET /v1/models` and `POST /v1/chat/completions`
 * (with or without streaming). One request is read per connection.
 */
class ApiServer(
    private val port: Int,
    private val apiKey: () -> String?,
    private val backend: () -> ApiBackend?,
    maxConnections: Int = MAX_CONNECTIONS,
    /** When set, the server speaks HTTPS using this context instead of plain HTTP. */
    private val tls: SSLContext? = null,
) {
    private val permits = Semaphore(maxConnections)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "pocketlm-api").apply { isDaemon = true } }

    /** Starts listening and returns the port in use (useful when [port] is 0). */
    fun start(): Int {
        val socket = if (tls == null) {
            ServerSocket(port, 16, null as InetAddress?)
        } else {
            (tls.serverSocketFactory.createServerSocket(port, 16, null as InetAddress?) as SSLServerSocket).apply {
                enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            }
        }
        serverSocket = socket
        pool.execute {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                if (permits.tryAcquire()) {
                    pool.execute {
                        try {
                            handle(client)
                        } finally {
                            permits.release()
                        }
                    }
                } else {
                    // Too many open connections: turn this one away instead of queueing without limit.
                    pool.execute { reject(client) }
                }
            }
        }
        return socket.localPort
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        pool.shutdownNow()
    }

    // ---- Request handling ---------------------------------------------------------

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private fun reject(socket: Socket) {
        socket.use {
            socket.soTimeout = 2_000
            runCatching {
                val out = BufferedOutputStream(socket.getOutputStream())
                writeJson(out, 503, errorJson("The server is busy. Try again in a moment.", 503))
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            socket.soTimeout = READ_TIMEOUT_MS
            val out = BufferedOutputStream(socket.getOutputStream())
            try {
                val input = socket.getInputStream()
                val head = readHead(input)
                // Check the key before reading the body, so an unauthenticated caller can't make us buffer it.
                if (!isPublic(head.method, head.path)) authenticate(head.headers)
                route(Request(head.method, head.path, head.headers, readBody(input, head.headers)), out)
            } catch (e: ApiException) {
                writeJson(out, e.status, errorJson(e.message ?: "Error", e.status))
            } catch (e: ClientGone) {
                // The caller hung up; nothing more to say.
            } catch (e: IOException) {
                // Timed out or dropped connection.
            } catch (e: Exception) {
                runCatching { writeJson(out, 500, errorJson(e.message ?: "Internal error", 500)) }
            }
            runCatching { out.flush() }
        }
    }

    private fun route(request: Request, out: OutputStream) {
        if (request.method == "OPTIONS") {
            writeRaw(out, 204, "No Content", "text/plain", ByteArray(0))
            return
        }
        if (request.method == "GET" && request.path == "/") {
            writeRaw(out, 200, "OK", "text/plain; charset=utf-8", "PocketLM API server is running.\n".toByteArray())
            return
        }
        when {
            request.method == "GET" && request.path == "/v1/models" -> {
                val data = JSONArray((backend()?.modelNames() ?: emptyList()).map {
                    JSONObject().put("id", it).put("object", "model").put("created", 0).put("owned_by", "pocketlm")
                })
                writeJson(out, 200, JSONObject().put("object", "list").put("data", data))
            }
            request.method == "POST" && request.path == "/v1/embeddings" -> embeddings(request.body, out)
            request.method == "POST" && request.path == "/v1/chat/completions" -> respond(parseChat(request.body), out, completion = false)
            request.method == "POST" && request.path == "/v1/completions" -> respond(parseCompletion(request.body), out, completion = true)
            else -> throw ApiException(404, "Unknown endpoint ${request.method} ${request.path}")
        }
    }

    private fun isPublic(method: String, path: String) =
        method == "OPTIONS" || (method == "GET" && path == "/")

    private fun authenticate(headers: Map<String, String>) {
        val key = apiKey()
        if (key.isNullOrEmpty()) return
        val given = headers["authorization"]?.removePrefix("Bearer ")?.trim().orEmpty()
        if (!MessageDigest.isEqual(given.toByteArray(), key.toByteArray())) {
            throw ApiException(401, "Missing or wrong API key. Send it as: Authorization: Bearer <key>")
        }
    }

    /** Ends generation early when a stop sequence has been found. */
    private class StopReached : CancellationException("stop sequence")

    /**
     * Answers [request]. [completion] selects the older text-completion layout (`text`) instead of the
     * chat layout (`message` / `delta`).
     */
    private fun respond(request: ChatRequest, out: OutputStream, completion: Boolean) {
        val api = backend() ?: throw ApiException(503, "PocketLM is not ready. Open the app and load a model.")
        val model = api.modelNames().firstOrNull() ?: "pocketlm"
        val id = (if (completion) "cmpl-" else "chatcmpl-") + UUID.randomUUID().toString().replace("-", "").take(24)
        val created = System.currentTimeMillis() / 1000
        val filter = StopFilter(request.stop)
        var count = 0

        /** Runs the model and passes on only text that is safe to send; ends early at a stop sequence. */
        fun generate(send: (String) -> Unit) {
            try {
                runBlocking {
                    api.complete(request) { piece ->
                        count++
                        val safe = filter.feed(piece)
                        if (safe.isNotEmpty()) send(safe)
                        if (filter.finished) throw StopReached()
                    }
                }
            } catch (e: StopReached) {
                // The reply ended at a stop sequence.
            }
            if (!filter.finished) filter.flush().let { if (it.isNotEmpty()) send(it) }
        }

        fun finishReason() =
            if (!filter.finished && request.maxTokens != null && count >= request.maxTokens) "length" else "stop"

        // With functions on offer the whole answer is read first, as a call to one is only clear once it is complete.
        if (!completion && request.usesTools) {
            val written = StringBuilder()
            generate { written.append(it) }
            val parsed = ApiTools.parseReply(written.toString(), request.tools)
            val reason = if (parsed.calls.isNotEmpty()) "tool_calls" else finishReason()
            fun callJson(call: ApiToolCall) = JSONObject().put("id", call.id).put("type", "function")
                .put("function", JSONObject().put("name", call.name).put("arguments", call.argumentsJson))

            if (!request.stream) {
                val message = JSONObject().put("role", "assistant").put("content", parsed.content ?: JSONObject.NULL)
                if (parsed.calls.isNotEmpty()) message.put("tool_calls", JSONArray(parsed.calls.map(::callJson)))
                val usage = JSONObject().put("prompt_tokens", 0).put("completion_tokens", count).put("total_tokens", count)
                writeJson(
                    out, 200,
                    JSONObject().put("id", id).put("object", "chat.completion").put("created", created).put("model", model)
                        .put("choices", JSONArray().put(JSONObject().put("index", 0).put("message", message).put("finish_reason", reason)))
                        .put("usage", usage),
                )
                return
            }
            writeHead(out, 200, "OK", "text/event-stream", extra = "Cache-Control: no-cache\r\n")
            fun send(delta: JSONObject, finish: String?) {
                val choice = JSONObject().put("index", 0).put("delta", delta).put("finish_reason", finish ?: JSONObject.NULL)
                val json = JSONObject().put("id", id).put("object", "chat.completion.chunk").put("created", created)
                    .put("model", model).put("choices", JSONArray().put(choice))
                try {
                    out.write(("data: $json\n\n").toByteArray())
                    out.flush()
                } catch (e: IOException) {
                    throw ClientGone()
                }
            }
            send(JSONObject().put("role", "assistant").put("content", ""), null)
            parsed.content?.let { send(JSONObject().put("content", it), null) }
            parsed.calls.forEachIndexed { index, call ->
                val head = JSONObject().put("index", index).put("id", call.id).put("type", "function")
                    .put("function", JSONObject().put("name", call.name).put("arguments", ""))
                send(JSONObject().put("tool_calls", JSONArray().put(head)), null)
                val body = JSONObject().put("index", index).put("function", JSONObject().put("arguments", call.argumentsJson))
                send(JSONObject().put("tool_calls", JSONArray().put(body)), null)
            }
            send(JSONObject(), reason)
            try {
                out.write("data: [DONE]\n\n".toByteArray())
                out.flush()
            } catch (e: IOException) {
                // Already finished; ignore.
            }
            return
        }

        if (!request.stream) {
            val text = StringBuilder()
            generate { text.append(it) }
            val choice = JSONObject().put("index", 0).put("finish_reason", finishReason())
            if (completion) choice.put("text", text.toString()).put("logprobs", JSONObject.NULL)
            else choice.put("message", JSONObject().put("role", "assistant").put("content", text.toString()))
            val usage = JSONObject().put("prompt_tokens", 0).put("completion_tokens", count).put("total_tokens", count)
            writeJson(
                out, 200,
                JSONObject().put("id", id).put("object", if (completion) "text_completion" else "chat.completion")
                    .put("created", created).put("model", model).put("choices", JSONArray().put(choice)).put("usage", usage),
            )
            return
        }

        // Streaming: Server-Sent Events, ended by closing the connection.
        writeHead(out, 200, "OK", "text/event-stream", extra = "Cache-Control: no-cache\r\n")
        fun chunk(piece: String, finish: String?, first: Boolean = false) {
            val choice = JSONObject().put("index", 0).put("finish_reason", finish ?: JSONObject.NULL)
            if (completion) {
                choice.put("text", piece).put("logprobs", JSONObject.NULL)
            } else {
                val delta = JSONObject()
                if (first) delta.put("role", "assistant").put("content", "")
                else if (piece.isNotEmpty()) delta.put("content", piece)
                choice.put("delta", delta)
            }
            val json = JSONObject().put("id", id).put("object", if (completion) "text_completion" else "chat.completion.chunk")
                .put("created", created).put("model", model).put("choices", JSONArray().put(choice))
            try {
                out.write(("data: $json\n\n").toByteArray())
                out.flush()
            } catch (e: IOException) {
                throw ClientGone()
            }
        }
        if (!completion) chunk("", null, first = true)
        generate { chunk(it, null) }
        chunk("", finishReason())
        try {
            out.write("data: [DONE]\n\n".toByteArray())
            out.flush()
        } catch (e: IOException) {
            // Already finished; ignore.
        }
    }

    /** Thrown inside a token callback when the caller disconnected, to stop generating. */
    private class ClientGone : CancellationException("client disconnected")

    // ---- HTTP plumbing ------------------------------------------------------------------

    private class Head(val method: String, val path: String, val headers: Map<String, String>)

    private fun readHead(input: InputStream): Head {
        val requestLine = readLine(input) ?: throw IOException("Empty request")
        val parts = requestLine.split(' ')
        if (parts.size < 2) throw ApiException(400, "Malformed request line")
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: throw IOException("Unexpected end of headers")
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            if (headers.size > MAX_HEADERS) throw ApiException(431, "Too many headers")
        }

        return Head(method, path, headers)
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            throw ApiException(411, "Send the body with a Content-Length instead of chunked encoding")
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY_BYTES) throw ApiException(413, "Request body is too large")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw IOException("Connection closed before the body ended")
            read += n
        }
        return body
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) return buffer.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            buffer.write(b)
            if (buffer.size() > MAX_LINE_BYTES) throw ApiException(431, "Header line too long")
        }
    }

    /** `POST /v1/embeddings`: one vector per input, as a list of numbers or (as the OpenAI libraries ask) base64. */
    private fun embeddings(body: ByteArray, out: OutputStream) {
        val json = parseJson(body)
        val inputs: List<String> = when (val input = json.opt("input")) {
            is String -> listOf(input)
            is JSONArray -> List(input.length()) { i ->
                input.opt(i) as? String ?: throw ApiException(400, "'input' must be text, or a list of texts. Token lists are not supported.")
            }
            else -> throw ApiException(400, "'input' is required")
        }
        if (inputs.isEmpty()) throw ApiException(400, "'input' is empty")
        if (inputs.size > MAX_EMBEDDING_INPUTS) throw ApiException(400, "At most $MAX_EMBEDDING_INPUTS inputs per request")
        if (inputs.any { it.length > MAX_EMBEDDING_CHARS }) throw ApiException(400, "An input is longer than $MAX_EMBEDDING_CHARS characters")
        val format = json.optString("encoding_format", "float")
        if (format != "float" && format != "base64") throw ApiException(400, "'encoding_format' must be \"float\" or \"base64\"")

        val backend = backend() ?: throw ApiException(503, "PocketLM is not ready")
        val result = runBlocking { backend.embed(inputs) }
        val data = JSONArray()
        result.vectors.forEachIndexed { index, vector ->
            val embedding: Any = if (format == "base64") {
                val bytes = java.nio.ByteBuffer.allocate(vector.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                vector.forEach { bytes.putFloat(it) }
                java.util.Base64.getEncoder().encodeToString(bytes.array())
            } else {
                JSONArray(vector.map { it.toDouble() })
            }
            data.put(JSONObject().put("object", "embedding").put("index", index).put("embedding", embedding))
        }
        writeJson(
            out, 200,
            JSONObject().put("object", "list").put("data", data).put("model", result.model)
                .put("usage", JSONObject().put("prompt_tokens", result.tokens).put("total_tokens", result.tokens)),
        )
    }

    private fun parseJson(body: ByteArray): JSONObject = try {
        JSONObject(body.toString(Charsets.UTF_8))
    } catch (e: JSONException) {
        throw ApiException(400, "The request body is not valid JSON")
    }

    private fun parseChat(body: ByteArray): ChatRequest {
        val json = parseJson(body)
        val array = json.optJSONArray("messages") ?: throw ApiException(400, "'messages' is required")
        val messages = List(array.length()) { i ->
            val m = array.optJSONObject(i) ?: throw ApiException(400, "Each message must be an object")
            ApiMessage(
                m.optString("role", "user"), contentText(m.opt("content")),
                toolCalls = ApiTools.parseToolCalls(m.optJSONArray("tool_calls")),
                toolCallId = m.optString("tool_call_id").ifEmpty { null },
            )
        }
        if (messages.none { it.role == "user" }) throw ApiException(400, "'messages' needs at least one user message")
        return withOptions(json, messages)
    }

    /** The older `/v1/completions` shape: a `prompt` instead of `messages`. The chat template still applies. */
    private fun parseCompletion(body: ByteArray): ChatRequest {
        val json = parseJson(body)
        val prompt = when (val p = json.opt("prompt")) {
            is String -> p
            is JSONArray -> if (p.length() > 0) p.optString(0) else ""
            else -> ""
        }
        if (prompt.isBlank()) throw ApiException(400, "'prompt' is required")
        return withOptions(json, listOf(ApiMessage("user", prompt)))
    }

    private fun withOptions(json: JSONObject, messages: List<ApiMessage>): ChatRequest {
        val maxTokens = (json.opt("max_tokens") as? Number)?.toInt()?.takeIf { it > 0 }
            ?: (json.opt("max_completion_tokens") as? Number)?.toInt()?.takeIf { it > 0 }
        val stop = when (val s = json.opt("stop")) {
            is String -> listOf(s)
            is JSONArray -> (0 until s.length()).mapNotNull { s.optString(it).ifEmpty { null } }
            else -> emptyList()
        }.take(MAX_STOP_SEQUENCES)
        val format = json.optJSONObject("response_format")
        val schema = JsonSchema.fromResponseFormat(format)
        if (schema != null) {
            JsonSchema.problem(schema)?.let { throw ApiException(400, "response_format: $it") }
        }
        val tools = ApiTools.parseTools(json.optJSONArray("tools"))
        return ChatRequest(
            messages = messages,
            tools = tools,
            toolChoice = ApiTools.parseChoice(json.opt("tool_choice"), tools),
            stream = json.optBoolean("stream", false),
            maxTokens = maxTokens,
            temperature = (json.opt("temperature") as? Number)?.toFloat()?.coerceIn(0f, 2f),
            topP = (json.opt("top_p") as? Number)?.toFloat()?.coerceIn(0.01f, 1f),
            stop = stop,
            jsonSchema = schema,
            jsonObject = schema == null && JsonSchema.wantsJsonObject(format),
        )
    }

    /** Message content is a string, or a list of parts such as {"type":"text","text":"..."}. */
    private fun contentText(content: Any?): String = when (content) {
        null, JSONObject.NULL -> ""
        is String -> content
        is JSONArray -> (0 until content.length()).mapNotNull { content.optJSONObject(it)?.optString("text")?.ifEmpty { null } }
            .joinToString("\n")
        else -> content.toString()
    }

    private fun errorJson(message: String, status: Int) = JSONObject().put(
        "error",
        JSONObject().put("message", message)
            .put("type", if (status in 400..499) "invalid_request_error" else "server_error")
            .put("code", JSONObject.NULL),
    )

    private fun writeJson(out: OutputStream, status: Int, json: JSONObject) =
        writeRaw(out, status, reason(status), "application/json; charset=utf-8", json.toString().toByteArray())

    private fun writeRaw(out: OutputStream, status: Int, reason: String, type: String, body: ByteArray) {
        writeHead(out, status, reason, type, extra = "Content-Length: ${body.size}\r\n")
        out.write(body)
        out.flush()
    }

    private fun writeHead(out: OutputStream, status: Int, reason: String, type: String, extra: String) {
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $type\r\n" +
            extra +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Headers: authorization, content-type\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
        411 -> "Length Required"; 413 -> "Payload Too Large"; 431 -> "Request Header Fields Too Large"
        503 -> "Service Unavailable"; else -> "Error"
    }

    private companion object {
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_BODY_BYTES = 4_000_000
        const val MAX_LINE_BYTES = 8_192
        const val MAX_HEADERS = 64
        const val MAX_CONNECTIONS = 16
        const val MAX_STOP_SEQUENCES = 4
        const val MAX_EMBEDDING_INPUTS = 128
        const val MAX_EMBEDDING_CHARS = 32_000
    }
}
