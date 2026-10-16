package com.pocketlm.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.TreeMap

/** A pretend S3 server that checks every request signature the way a real one does. */
private class FakeS3(
    private val accessKey: String,
    private val secretKey: String,
    private val region: String,
    private val bucket: String,
    private val pageSize: Int = 1000,
) : SyncHttp {
    val objects = TreeMap<String, ByteArray>()
    val requests = ArrayList<String>()

    private fun error(code: Int, name: String, message: String) =
        HttpResponse(code, """<?xml version="1.0"?><Error><Code>$name</Code><Message>$message</Message></Error>""".toByteArray())

    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        requests += "$method $url"
        val uri = URI(url)
        val payload = body ?: ByteArray(0)
        val auth = headers["Authorization"] ?: return error(403, "AccessDenied", "Access Denied")
        if (headers["x-amz-content-sha256"] != SigV4.sha256Hex(payload)) return error(400, "XAmzContentSHA256Mismatch", "The provided hash does not match")

        val query = (uri.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.map {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        val sentAt = LocalDateTime.parse(headers.getValue("x-amz-date"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")).toInstant(ZoneOffset.UTC).toEpochMilli()
        val expected = SigV4.sign(
            accessKey, secretKey, region, method, uri.authority, uri.path, query, emptyMap(), headers.getValue("x-amz-content-sha256"), sentAt,
        )
        if (auth != expected.authorization) return error(403, "SignatureDoesNotMatch", "The request signature we calculated does not match")

        val path = uri.path
        if (path != "/$bucket" && !path.startsWith("/$bucket/")) return error(404, "NoSuchBucket", "The specified bucket does not exist")
        val key = path.removePrefix("/$bucket").removePrefix("/")
        return when {
            method == "GET" && key.isEmpty() -> list(query)
            method == "PUT" -> { objects[key] = payload; HttpResponse(200, ByteArray(0)) }
            method == "GET" -> objects[key]?.let { HttpResponse(200, it) } ?: error(404, "NoSuchKey", "The specified key does not exist.")
            method == "DELETE" -> { objects.remove(key); HttpResponse(204, ByteArray(0)) }
            else -> error(405, "MethodNotAllowed", "no")
        }
    }

    private fun list(query: List<Pair<String, String>>): HttpResponse {
        val prefix = query.firstOrNull { it.first == "prefix" }?.second.orEmpty()
        val token = query.firstOrNull { it.first == "continuation-token" }?.second
        val keys = objects.keys.filter { it.startsWith(prefix) }
        val start = token?.toInt() ?: 0
        val page = keys.drop(start).take(pageSize)
        val more = start + page.size < keys.size
        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?><ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">""")
            append("<Name>$bucket</Name><IsTruncated>$more</IsTruncated>")
            if (more) append("<NextContinuationToken>${start + page.size}</NextContinuationToken>")
            for (k in page) append("<Contents><Key>$k</Key><Size>${objects.getValue(k).size}</Size></Contents>")
            append("</ListBucketResult>")
        }
        return HttpResponse(200, xml.toByteArray())
    }
}

/** A pretend WebDAV server for one folder. */
private class FakeDav(private val folderPath: String, private val user: String, private val password: String) : SyncHttp {
    val files = TreeMap<String, ByteArray>()
    val requests = ArrayList<String>()
    var folderExists = true
    var canMkcol = true

    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        requests += "$method $url"
        val expected = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        if (headers["Authorization"] != expected) return HttpResponse(401, ByteArray(0))
        val path = URI(url).path
        if (method == "MKCOL") {
            if (!canMkcol) throw SyncException("cannot send MKCOL")
            return if (path == folderPath) { folderExists = true; HttpResponse(201, ByteArray(0)) } else HttpResponse(409, ByteArray(0))
        }
        if (method == "PROPFIND") return propfind()
        if (!folderExists) return HttpResponse(409, ByteArray(0))
        if (!path.startsWith(folderPath)) return HttpResponse(404, ByteArray(0))
        val name = URLDecoder.decode(path.removePrefix(folderPath), "UTF-8")
        return when (method) {
            "PUT" -> { val existed = files.containsKey(name); files[name] = body!!; HttpResponse(if (existed) 204 else 201, ByteArray(0)) }
            "GET" -> files[name]?.let { HttpResponse(200, it) } ?: HttpResponse(404, ByteArray(0))
            "DELETE" -> if (files.remove(name) != null) HttpResponse(204, ByteArray(0)) else HttpResponse(404, ByteArray(0))
            else -> HttpResponse(405, ByteArray(0))
        }
    }

    private fun propfind(): HttpResponse {
        if (!folderExists) return HttpResponse(404, ByteArray(0))
        val xml = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:">""")
            append("<d:response><d:href>$folderPath</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>")
            for ((name, data) in files) {
                append("<d:response><d:href>$folderPath$name</d:href><d:propstat><d:prop><d:getcontentlength>${data.size}</d:getcontentlength><d:resourcetype/></d:prop></d:propstat></d:response>")
            }
            append("</d:multistatus>")
        }
        return HttpResponse(207, xml.toByteArray())
    }
}

class SyncBackendsTest {

    private val now = 1369353600000L

    private fun s3(server: FakeS3, prefix: String = "", endpoint: String = "https://s3.example.com") =
        S3Backend(endpoint, "us-east-1", "my-bucket", prefix, "AKIAEXAMPLE", "secret/key+value", server, { now })

    private fun fakeS3(pageSize: Int = 1000) = FakeS3("AKIAEXAMPLE", "secret/key+value", "us-east-1", "my-bucket", pageSize)

    // ---- S3 ------------------------------------------------------------------------

    @Test
    fun s3WritesReadsAndDeletesFilesWithValidSignatures() {
        val server = fakeS3()
        val backend = s3(server)
        val data = ByteArray(1000) { (it % 251).toByte() }
        backend.put("c-1.bin", data)
        assertArrayEquals(data, backend.get("c-1.bin"))
        assertNull(backend.get("missing.bin"))
        backend.delete("c-1.bin")
        assertNull(backend.get("c-1.bin"))
        backend.delete("c-1.bin") // already gone is fine
        assertTrue(server.requests.first().startsWith("PUT https://s3.example.com/my-bucket/c-1.bin"))
    }

    @Test
    fun s3FilesLiveUnderTheChosenFolderInTheBucket() {
        val server = fakeS3()
        val backend = s3(server, prefix = "/pocketlm/phone sync/")
        backend.put("manifest.bin", byteArrayOf(1))
        assertEquals(setOf("pocketlm/phone sync/manifest.bin"), server.objects.keys)
        assertTrue(server.requests.single().contains("/my-bucket/pocketlm/phone%20sync/manifest.bin"))
        assertArrayEquals(byteArrayOf(1), backend.get("manifest.bin"))
    }

    @Test
    fun s3ListingFollowsPagesAndStaysInItsFolder() {
        val server = fakeS3(pageSize = 2)
        val backend = s3(server, prefix = "mine")
        for (i in 1..5) backend.put("c-$i.bin", ByteArray(i))
        server.objects["other/ignored.bin"] = ByteArray(1)
        server.objects["mine/sub/deep.bin"] = ByteArray(1)
        val listed = backend.list().sortedBy { it.name }
        assertEquals((1..5).map { "c-$it.bin" }, listed.map { it.name })
        assertEquals((1..5).map { it.toLong() }, listed.map { it.size })
        assertEquals(3, server.requests.count { it.startsWith("GET") && it.contains("list-type=2") })
    }

    @Test
    fun s3KeepsAPortAndPathInTheEndpointAndSignsThem() {
        var seen: Triple<String, String, Map<String, String>>? = null
        val http = object : SyncHttp {
            override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
                seen = Triple(method, url, headers)
                return HttpResponse(200, ByteArray(0))
            }
        }
        S3Backend("https://minio.lan:9000/s3/", "us-east-1", "my-bucket", "", "AKIAEXAMPLE", "secret/key+value", http, { now })
            .put("x.bin", byteArrayOf(1))
        val (method, url, headers) = seen!!
        assertEquals("PUT", method)
        assertEquals("https://minio.lan:9000/s3/my-bucket/x.bin", url)
        val expected = SigV4.sign(
            "AKIAEXAMPLE", "secret/key+value", "us-east-1", "PUT", "minio.lan:9000", "/s3/my-bucket/x.bin",
            emptyList(), emptyMap(), SigV4.sha256Hex(byteArrayOf(1)), now,
        )
        assertEquals(expected.authorization, headers["Authorization"])
    }

    @Test
    fun theDefaultPortIsLeftOutOfTheSignedHost() {
        var host: String? = null
        val http = object : SyncHttp {
            override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
                host = URI(url).authority
                return HttpResponse(200, ByteArray(0))
            }
        }
        S3Backend("https://s3.example.com:443", "us-east-1", "b", "", "k", "s", http, { now }).put("x.bin", byteArrayOf(1))
        assertEquals("s3.example.com", host)
    }

    @Test
    fun s3ReportsAWrongSecretAsAnAccessProblem() {
        val server = fakeS3()
        val backend = S3Backend("https://s3.example.com", "us-east-1", "my-bucket", "", "AKIAEXAMPLE", "WRONG", server, { now })
        try {
            backend.put("x.bin", byteArrayOf(1))
            fail()
        } catch (e: SyncException) {
            assertTrue(e.message!!, e.message!!.contains("refused the access key"))
            assertTrue(e.message!!, e.message!!.contains("SignatureDoesNotMatch"))
        }
    }

    @Test
    fun s3ReportsAMissingBucketInsteadOfAnEmptyLocation() {
        val server = FakeS3("AKIAEXAMPLE", "secret/key+value", "us-east-1", "other-bucket")
        val backend = s3(server)
        try {
            backend.get("manifest.bin")
            fail("a missing bucket must not look like a missing file")
        } catch (e: SyncException) {
            assertTrue(e.message!!, e.message!!.contains("bucket was not found"))
        }
    }

    @Test
    fun s3RefusesNamesThatCouldEscapeTheFolder() {
        val backend = s3(fakeS3())
        for (bad in listOf("../x", "a/b", "", "..")) {
            try {
                backend.put(bad, byteArrayOf(1))
                fail("accepted '$bad'")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun s3ListingIsReadFromTheXmlAnswer() {
        val xml = """<?xml version="1.0"?><ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><IsTruncated>true</IsTruncated>
            <NextContinuationToken>abc==</NextContinuationToken>
            <Contents><Key>pre/c-1.bin</Key><Size>12</Size></Contents><Contents><Key>pre/sub/x.bin</Key><Size>3</Size></Contents></ListBucketResult>"""
        val page = S3Backend.parseListing(xml.toByteArray(), "pre/")
        assertEquals(listOf(RemoteFile("c-1.bin", 12)), page.files)
        assertEquals("abc==", page.next)
    }

    // ---- WebDAV --------------------------------------------------------------------

    private val folder = "/remote.php/dav/files/me/PocketLM/"

    private fun dav(server: FakeDav, password: String = "app-password") =
        WebDavBackend("https://cloud.example.com$folder", "me", password, server)

    @Test
    fun webDavWritesReadsAndDeletesFiles() {
        val server = FakeDav(folder, "me", "app-password")
        val backend = dav(server)
        backend.put("c-1.bin", byteArrayOf(1, 2, 3))
        backend.put("c-1.bin", byteArrayOf(4, 5)) // overwrite
        assertArrayEquals(byteArrayOf(4, 5), backend.get("c-1.bin"))
        assertNull(backend.get("nothing.bin"))
        backend.delete("c-1.bin")
        backend.delete("c-1.bin")
        assertNull(backend.get("c-1.bin"))
        assertTrue(server.requests.first().startsWith("PUT https://cloud.example.com$folder" + "c-1.bin"))
    }

    @Test
    fun webDavAcceptsAFolderAddressWithoutATrailingSlash() {
        val server = FakeDav(folder, "me", "app-password")
        val backend = WebDavBackend("https://cloud.example.com" + folder.trimEnd('/'), "me", "app-password", server)
        backend.put("x.bin", byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), server.files["x.bin"])
    }

    @Test
    fun webDavSaysWhenThePasswordIsRefused() {
        val server = FakeDav(folder, "me", "app-password")
        try {
            dav(server, password = "wrong").get("manifest.bin")
            fail()
        } catch (e: SyncException) {
            assertTrue(e.message!!, e.message!!.contains("user name or password"))
        }
    }

    @Test
    fun webDavCreatesAMissingFolderWhenTheDeviceCanSendMkcol() {
        val server = FakeDav(folder, "me", "app-password").apply { folderExists = false }
        dav(server).put("x.bin", byteArrayOf(1))
        assertTrue(server.folderExists)
        assertArrayEquals(byteArrayOf(1), server.files["x.bin"])
        assertTrue(server.requests.any { it.startsWith("MKCOL") })
    }

    @Test
    fun webDavAsksTheUserToCreateTheFolderWhenItCannot() {
        val server = FakeDav(folder, "me", "app-password").apply { folderExists = false; canMkcol = false }
        try {
            dav(server).put("x.bin", byteArrayOf(1))
            fail()
        } catch (e: SyncException) {
            assertTrue(e.message!!, e.message!!.contains("Create it there first"))
        }
    }

    @Test
    fun webDavListsOnlyFilesInTheFolder() {
        val server = FakeDav(folder, "me", "app-password")
        val backend = dav(server)
        backend.put("a.bin", ByteArray(3))
        backend.put("b.bin", ByteArray(5))
        assertEquals(listOf(RemoteFile("a.bin", 3), RemoteFile("b.bin", 5)), backend.list().sortedBy { it.name })
    }

    @Test
    fun aPropfindAnswerIsReadWhateverPrefixAndEncodingTheServerUses() {
        val xml = """<?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>http://nas.local/dav/PocketLM/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
              <D:response><D:href>http://nas.local/dav/PocketLM/c-1%2Dx.bin</D:href><D:propstat><D:prop><D:getcontentlength>42</D:getcontentlength><D:resourcetype/></D:prop></D:propstat></D:response>
              <D:response><D:href>/dav/PocketLM/sub/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
              <D:response><D:href>/dav/PocketLM/with%20space.bin</D:href><D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat></D:response>
              <D:response><D:href>/dav/PocketLM/..%2Fescape.bin</D:href><D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat></D:response>
            </D:multistatus>"""
        assertEquals(listOf(RemoteFile("c-1-x.bin", 42)), WebDavBackend.parseListing(xml.toByteArray()))
    }

    @Test
    fun answersThatDeclareEntitiesAreRefused() {
        val evil = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><d:multistatus xmlns:d="DAV:">&e;</d:multistatus>"""
        try {
            WebDavBackend.parseListing(evil.toByteArray())
            fail()
        } catch (e: SyncException) {
            assertFalse(e.message!!.contains("root:"))
        }
        try {
            S3Backend.parseListing(evil.toByteArray(), "")
            fail()
        } catch (e: SyncException) {
            // expected
        }
    }

    // ---- Any backend ---------------------------------------------------------------

    @Test
    fun theAccessCheckLeavesNothingBehind() {
        val memory = MemoryBackend()
        memory.checkAccess()
        assertTrue(memory.files.isEmpty())
        val davServer = FakeDav(folder, "me", "app-password")
        dav(davServer).checkAccess()
        assertTrue(davServer.files.isEmpty())
        val s3Server = fakeS3()
        s3(s3Server).checkAccess()
        assertTrue(s3Server.objects.isEmpty())
    }

    @Test
    fun theAccessCheckNoticesAPlaceThatLosesData() {
        val forgetful = object : SyncBackend {
            override val label = "forgetful"
            override fun list() = emptyList<RemoteFile>()
            override fun get(name: String): ByteArray? = null
            override fun put(name: String, data: ByteArray) {}
            override fun delete(name: String) {}
        }
        try {
            forgetful.checkAccess()
            fail()
        } catch (e: SyncException) {
            assertTrue(e.message!!.contains("would not return it"))
        }
    }

    @Test
    fun theWholeSyncWorksThroughS3AndWebDav() {
        for (make in listOf<() -> SyncBackend>({ s3(fakeS3()) }, { dav(FakeDav(folder, "me", "app-password")) })) {
            val backend = make()
            val (header, key) = SyncCrypto.newHeader("a long passphrase".toCharArray(), 1_000)
            backend.put(SyncNames.HEADER, header.toJson().toByteArray())
            val dir = java.nio.file.Files.createTempDirectory("e2e").toFile()
            try {
                fun phone(name: String): SyncEngine {
                    val base = java.io.File(dir, name)
                    return SyncEngine(
                        java.io.File(base, "chats").also { it.mkdirs() }, java.io.File(base, "images"), java.io.File(base, "docs"),
                        backend, header, key, SyncStateStore(java.io.File(base, "state.json")),
                    ) { now }
                }
                java.io.File(dir, "a/chats").mkdirs()
                java.io.File(dir, "a/chats/c1.json").writeText(
                    """{"id":"c1","title":"Hello","updatedAt":5,"modelFile":null,"messages":[{"text":"hi","isUser":true}],"attachments":[]}""",
                )
                assertEquals(1, phone("a").run().uploaded)
                assertEquals(1, phone("b").run().downloaded)
                assertTrue(java.io.File(dir, "b/chats/c1.json").readText().contains("Hello"))
            } finally {
                dir.deleteRecursively()
            }
        }
    }
}
