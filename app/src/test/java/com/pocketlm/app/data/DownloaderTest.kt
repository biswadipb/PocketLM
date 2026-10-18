package com.pocketlm.app.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files

class DownloaderTest {

    private val content = ByteArray(1000) { (it % 251).toByte() }
    private lateinit var dir: File
    private lateinit var server: ServerSocket

    /** What the next connection should do. */
    private var cutAfter: Int? = null
    private var ignoreRange = false

    @Before
    fun start() {
        dir = Files.createTempDirectory("dl").toFile()
        server = ServerSocket(0)
        Thread {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: IOException) { break }
                Thread {
                    client.use { s ->
                        val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val lines = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                        val range = lines.firstOrNull { it.startsWith("Range:", true) }
                            ?.substringAfter("bytes=")?.substringBefore('-')?.trim()?.toIntOrNull()
                        val out = s.getOutputStream()
                        when {
                            range != null && range >= content.size -> out.write(
                                "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${content.size}\r\nContent-Length: 0\r\n\r\n".toByteArray()
                            )
                            range != null && !ignoreRange -> {
                                val body = content.copyOfRange(range, content.size)
                                out.write(("HTTP/1.1 206 Partial Content\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray())
                                out.write(body)
                            }
                            else -> {
                                out.write(("HTTP/1.1 200 OK\r\nContent-Length: ${content.size}\r\n\r\n").toByteArray())
                                out.write(content, 0, cutAfter ?: content.size)
                            }
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

    private val url get() = "http://127.0.0.1:${server.localPort}/model.gguf"
    private val target get() = File(dir, "model.gguf")
    private val partial get() = File(dir, "model.gguf.part")

    private fun download() = runBlocking { Downloader.download(url, target, partial) { _, _ -> } }

    @Test
    fun aCompleteDownloadIsSavedWithTheRightBytes() {
        download()
        assertTrue(target.readBytes().contentEquals(content))
        assertFalse(partial.exists())
    }

    @Test
    fun aDownloadThatEndsEarlyIsNotSavedAndKeepsItsPartialFile() {
        cutAfter = 600
        try {
            download()
            fail("expected the short download to fail")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("stopped early"))
        }
        assertFalse(target.exists())
        assertEquals(600L, partial.length())
    }

    @Test
    fun afterAShortDownloadTheNextAttemptResumesAndFinishes() {
        cutAfter = 600
        runCatching { download() }
        cutAfter = null
        download()
        assertTrue(target.readBytes().contentEquals(content))
    }

    @Test
    fun aServerThatIgnoresRangeRestartsFromTheBeginning() {
        partial.writeBytes(ByteArray(300) { 9 })
        ignoreRange = true
        download()
        assertTrue(target.readBytes().contentEquals(content))
    }

    @Test
    fun aPartialFileThatIsAlreadyTheWholeFileIsAcceptedOn416() {
        partial.writeBytes(content)
        download()
        assertTrue(target.readBytes().contentEquals(content))
        assertFalse(partial.exists())
    }

    @Test
    fun aPartialFileLongerThanTheRealFileIsRemoved() {
        partial.writeBytes(ByteArray(1500))
        try {
            download()
            fail("expected an error")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("out of date"))
        }
        assertFalse(partial.exists())
    }

    @Test
    fun theSizeCheckAndHeaderParsing() {
        assertTrue(Downloader.isComplete(10, 10))
        assertFalse(Downloader.isComplete(9, 10))
        assertTrue(Downloader.isComplete(5, -1))
        assertEquals(1000L, Downloader.totalFromContentRange("bytes */1000"))
        assertEquals(1000L, Downloader.totalFromContentRange("bytes 0-9/1000"))
        assertEquals(null, Downloader.totalFromContentRange(null))
    }
}
