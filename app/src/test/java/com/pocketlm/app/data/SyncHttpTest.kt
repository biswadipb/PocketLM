package com.pocketlm.app.data

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/** Talks to a real HTTP server on this computer, to check the plain GET, PUT and DELETE handling. */
class SyncHttpTest {

    private lateinit var server: HttpServer
    private val store = HashMap<String, ByteArray>()
    private val seenAuth = ArrayList<String?>()
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val http = UrlConnectionHttp(connectTimeoutMs = 3_000, readTimeoutMs = 3_000, maxBodyBytes = 1_000_000)

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/files") { ex ->
            seenAuth += ex.requestHeaders.getFirst("Authorization")
            val name = ex.requestURI.path.removePrefix("/files/")
            val body = ex.requestBody.readBytes()
            val (code, data) = when (ex.requestMethod) {
                "PUT" -> { store[name] = body; 201 to ByteArray(0) }
                "GET" -> store[name]?.let { 200 to it } ?: (404 to "<Error>no</Error>".toByteArray())
                "DELETE" -> if (store.remove(name) != null) 204 to ByteArray(0) else 404 to ByteArray(0)
                else -> 405 to ByteArray(0)
            }
            ex.sendResponseHeaders(code, if (data.isEmpty()) -1 else data.size.toLong())
            if (data.isNotEmpty()) ex.responseBody.use { it.write(data) } else ex.close()
        }
        server.createContext("/moved") { ex ->
            val body = ex.requestBody.readBytes()
            ex.responseHeaders.add("Location", "/files/from-redirect-${ex.requestMethod}")
            ex.sendResponseHeaders(307, -1)
            if (body.isNotEmpty()) store["redirect-body"] = body
            ex.close()
        }
        server.createContext("/locked") { ex ->
            ex.requestBody.readBytes()
            ex.responseHeaders.add("WWW-Authenticate", "Basic realm=\"dav\"")
            ex.sendResponseHeaders(401, -1)
            ex.close()
        }
        server.createContext("/elsewhere") { ex ->
            ex.responseHeaders.add("Location", "http://localhost:${server.address.port}/files/x")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun putGetAndDeleteWork() {
        val data = ByteArray(200_000) { (it % 256).toByte() }
        assertEquals(201, http.request("PUT", "$base/files/a.bin", mapOf("Authorization" to "Basic abc"), data).code)
        assertArrayEquals(data, http.request("GET", "$base/files/a.bin").body)
        assertEquals(204, http.request("DELETE", "$base/files/a.bin").code)
        assertEquals(404, http.request("GET", "$base/files/a.bin").code)
        assertEquals("Basic abc", seenAuth.first())
    }

    @Test
    fun anErrorAnswerCarriesItsBody() {
        val r = http.request("GET", "$base/files/missing.bin")
        assertEquals(404, r.code)
        assertFalse(r.ok)
        assertEquals("<Error>no</Error>", r.text())
    }

    @Test
    fun aRefusedPasswordOnAnUploadIsAnAnswerNotACrash() {
        val r = http.request("PUT", "$base/locked/x.bin", mapOf("Authorization" to "Basic wrong"), ByteArray(5000))
        assertEquals(401, r.code)
        val backend = WebDavBackend("$base/locked", "me", "wrong", http)
        try {
            backend.put("x.bin", byteArrayOf(1))
            fail()
        } catch (e: SyncException) {
            assertTrue(e.message!!, e.message!!.contains("user name or password"))
        }
    }

    @Test
    fun aRedirectKeepsTheMethodAndTheBody() {
        val r = http.request("PUT", "$base/moved/x", emptyMap(), "payload".toByteArray())
        assertEquals(201, r.code)
        assertEquals("payload", String(store.getValue("from-redirect-PUT")))
    }

    @Test
    fun aRedirectToAnotherAddressIsRefused() {
        try {
            http.request("GET", "$base/elsewhere")
            fail()
        } catch (e: SyncException) {
            assertTrue(e.message!!.contains("another address"))
        }
    }

    @Test
    fun anAnswerLargerThanTheLimitIsRefused() {
        store["big"] = ByteArray(1_500_000)
        try {
            http.request("GET", "$base/files/big")
            fail()
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("larger than expected"))
        }
    }

    @Test
    fun onlyWebAddressesAreUsed() {
        try {
            http.request("GET", "file:///etc/passwd")
            fail()
        } catch (e: java.io.IOException) {
            // expected: either "only http and https" or an unsupported protocol
        }
    }

    @Test
    fun webDavTalksToARealServerForTheFileOperations() {
        val backend = WebDavBackend("$base/files", "me", "pw", http)
        backend.put("manifest.bin", byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), backend.get("manifest.bin"))
        assertNull(backend.get("nothing.bin"))
        backend.delete("manifest.bin")
        assertNull(backend.get("manifest.bin"))
    }
}

class SyncTargetTest {

    @Test
    fun aFolderNeedsToBeChosen() {
        assertEquals("Choose a folder first.", SyncTarget(SyncKind.Folder).problem())
        assertNull(SyncTarget(SyncKind.Folder, folderUri = "content://tree/x").problem())
    }

    @Test
    fun webDavNeedsHttpsAndCredentials() {
        val ok = SyncTarget(SyncKind.WebDav, url = "https://cloud.example.com/dav/PocketLM", username = "me", password = "pw")
        assertNull(ok.problem())
        assertTrue(ok.copy(url = "http://cloud.example.com/dav").problem()!!.contains("https"))
        assertTrue(ok.copy(url = "cloud.example.com").problem()!!.contains("address"))
        assertTrue(ok.copy(username = " ").problem()!!.contains("user name"))
        assertTrue(ok.copy(password = "").problem()!!.contains("password"))
    }

    @Test
    fun s3NeedsAnEndpointBucketRegionAndKeys() {
        val ok = SyncTarget(SyncKind.S3, endpoint = "https://s3.example.com", bucket = "my-bucket", accessKey = "AK", secretKey = "SK")
        assertNull(ok.problem())
        assertTrue(ok.copy(endpoint = "http://s3.example.com").problem()!!.contains("https"))
        assertTrue(ok.copy(bucket = "a").problem()!!.contains("bucket"))
        assertTrue(ok.copy(bucket = "bad/name").problem()!!.contains("bucket"))
        assertTrue(ok.copy(region = " ").problem()!!.contains("region"))
        assertTrue(ok.copy(secretKey = "").problem()!!.contains("secret key"))
    }

    @Test
    fun secretsAreKeptOutOfTheOpenHalf() {
        val target = SyncTarget(
            SyncKind.S3, endpoint = "https://s3.example.com", bucket = "b-1", accessKey = "AKID", secretKey = "TOPSECRET",
            username = "me", password = "hunter2", url = "https://dav.example.com/x",
        )
        val open = target.publicJson().toString()
        assertFalse(open.contains("TOPSECRET"))
        assertFalse(open.contains("hunter2"))
        assertTrue(open.contains("AKID"))
        val secret = target.secretJson().toString()
        assertTrue(secret.contains("TOPSECRET") && secret.contains("hunter2"))
        assertEquals(target, SyncTarget.fromJson(JSONObject(open), JSONObject(secret)))
    }

    @Test
    fun descriptionsSayWhereThingsGo() {
        assertEquals("WebDAV: cloud.example.com", SyncTarget(SyncKind.WebDav, url = "https://cloud.example.com/dav").describe())
        assertEquals("S3: my-bucket at s3.example.com", SyncTarget(SyncKind.S3, endpoint = "https://s3.example.com", bucket = "my-bucket").describe())
        assertEquals("Folder: Drive sync", SyncTarget(SyncKind.Folder, folderName = "Drive sync").describe())
    }
}
