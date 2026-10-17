package com.pocketlm.app.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest

class VerificationTest {

    private val content = ByteArray(5000) { (it * 7 % 251).toByte() }
    private val goodHash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    private lateinit var dir: File
    private lateinit var server: ServerSocket

    /** What the server says the file's checksum is: the right one, a wrong one, or nothing. */
    private var announced: String? = null

    @Before
    fun start() {
        dir = Files.createTempDirectory("verify").toFile()
        server = ServerSocket(0)
        Thread {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: IOException) { break }
                Thread {
                    client.use { s ->
                        val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val request = reader.readLine().orEmpty()
                        while (reader.readLine()?.isNotEmpty() == true) { /* skip the headers */ }
                        val out = s.getOutputStream()
                        if (request.startsWith("HEAD")) {
                            val etag = announced?.let { "X-Linked-Etag: \"$it\"\r\n" }.orEmpty()
                            out.write("HTTP/1.1 302 Found\r\nLocation: /elsewhere\r\n$etag\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        } else {
                            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${content.size}\r\n\r\n".toByteArray())
                            out.write(content)
                        }
                        out.flush()
                    }
                }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    @After
    fun stop() {
        server.close()
        dir.deleteRecursively()
    }

    private val url get() = "http://127.0.0.1:${server.localPort}/owner/repo/resolve/main/model.gguf"
    private val target get() = File(dir, "model.gguf")
    private val partial get() = File(dir, "model.gguf.part")

    private fun download() = runBlocking { Downloader.download(url, target, partial) { _, _ -> } }

    @Test
    fun aFileThatMatchesTheAnnouncedChecksumIsMarkedVerified() {
        announced = goodHash
        assertEquals(Downloader.Verified.Yes, download())
        assertTrue(target.exists())
        assertEquals(content.size.toLong(), target.length())
    }

    @Test
    fun aFileThatDoesNotMatchIsDeletedAndRefused() {
        announced = "0".repeat(64)
        try {
            download()
            fail("a wrong checksum must be refused")
        } catch (e: IOException) {
            assertTrue("checksum" in e.message.orEmpty())
        }
        assertFalse(target.exists())
        assertFalse(partial.exists())
    }

    @Test
    fun aServerThatDoesNotAnnounceOneLeavesTheFileUnchecked() {
        announced = null
        assertEquals(Downloader.Verified.NotChecked, download())
        assertTrue(target.exists())
    }

    @Test
    fun anAddressThatIsNotAModelDownloadIsNeverAsked() {
        announced = goodHash
        assertNull(Verification.expectedFor("http://127.0.0.1:${server.localPort}/model.gguf"))
        assertEquals(goodHash, Verification.expectedFor(url))
    }

    @Test
    fun theChecksumHeaderIsReadInItsUsualForms() {
        val hash = "ab".repeat(32)
        assertEquals(hash, Verification.parseLinkedEtag("\"$hash\""))
        assertEquals(hash, Verification.parseLinkedEtag("W/\"$hash\""))
        assertEquals(hash, Verification.parseLinkedEtag(hash.uppercase()))
        assertNull(Verification.parseLinkedEtag("\"not-a-hash\""))
        assertNull(Verification.parseLinkedEtag("\"${"ab".repeat(20)}\"")) // a git SHA-1 is not a SHA-256
        assertNull(Verification.parseLinkedEtag(null))
    }

    @Test
    fun theHashOfAFileIsCorrect() {
        val file = File(dir, "abc.txt").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Verification.sha256(file))
        assertTrue(Verification.matches("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD", Verification.sha256(file)))
    }
}
