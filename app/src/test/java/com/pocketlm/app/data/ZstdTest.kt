package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

/**
 * The decoder is checked against files made by the reference compressor (the `zstd` library) at many levels and on
 * different kinds of data, each listed in cases.txt with the length and SHA-256 of what it must decompress to.
 */
class ZstdTest {

    private class Case(val name: String, val length: Int, val sha256: String)

    private fun cases(): List<Case> =
        javaClass.getResourceAsStream("/zstd/cases.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }.map {
            val (name, length, hash) = it.trim().split(' ')
            Case(name, length.toInt(), hash)
        }

    private fun file(name: String) = javaClass.getResourceAsStream("/zstd/$name.zst")!!.readBytes()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun everyFileMadeByTheReferenceCompressorComesBackExactly() {
        val all = cases()
        assertTrue(all.size >= 15)
        for (case in all) {
            val out = Zstd.decompress(file(case.name))
            assertEquals("${case.name}: length", case.length, out.size)
            assertEquals("${case.name}: content", case.sha256, sha(out))
        }
    }

    @Test
    fun aSliceOfALargerArrayIsDecoded() {
        val data = file("small-text")
        val padded = ByteArray(10) + data + ByteArray(7) { 9 }
        val out = Zstd.decompress(padded, 10, 10 + data.size)
        assertEquals(cases().first { it.name == "small-text" }.sha256, sha(out))
    }

    @Test
    fun outputBeyondTheLimitIsRefused() {
        try {
            Zstd.decompress(file("zeros-1m"), maxOutput = 100_000)
            fail("expected a refusal")
        } catch (e: ZstdException) {
            assertTrue(e.message, e.message!!.contains("expands"))
        }
    }

    @Test
    fun dataThatIsNotZstdIsRefused() {
        for (bad in listOf(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), ByteArray(3), "plain text, not compressed".toByteArray())) {
            try {
                Zstd.decompress(bad)
                fail("expected a refusal")
            } catch (e: ZstdException) {
                assertTrue(e.message, !e.message.isNullOrBlank())
            }
        }
    }

    @Test
    fun truncatedAndDamagedDataFailsCleanlyNeverWithAnotherKindOfError() {
        val good = file("text-300k-l3")
        // Cut it short at many places.
        for (cut in listOf(5, 9, 20, 100, 1000, good.size / 2, good.size - 1)) {
            try {
                Zstd.decompress(good, 0, cut)
                fail("a file cut at $cut should not decode")
            } catch (e: ZstdException) {
                // expected
            }
        }
        // Flip bytes in the middle: either it still decodes to something, or it fails with a ZstdException.
        val random = java.util.Random(7)
        repeat(60) {
            val damaged = good.copyOf()
            repeat(3) { damaged[20 + random.nextInt(damaged.size - 20)] = random.nextInt(256).toByte() }
            try {
                Zstd.decompress(damaged)
            } catch (e: ZstdException) {
                // fine
            }
        }
    }
}
