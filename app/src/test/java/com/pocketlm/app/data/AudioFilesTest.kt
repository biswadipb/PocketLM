package com.pocketlm.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AudioFilesTest {

    // ---- multipart ---------------------------------------------------------------------

    private fun body(boundary: String, vararg parts: Triple<String, String?, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, file, data) in parts) {
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"$name\"${if (file != null) "; filename=\"$file\"" else ""}\r\n".toByteArray())
            if (file != null) out.write("Content-Type: audio/wav\r\n".toByteArray())
            out.write("\r\n".toByteArray())
            out.write(data)
            out.write("\r\n".toByteArray())
        }
        out.write("--$boundary--\r\n".toByteArray())
        return out.toByteArray()
    }

    @Test
    fun theBoundaryIsFoundQuotedOrNot() {
        assertEquals("abc", Multipart.boundaryOf("multipart/form-data; boundary=abc"))
        assertEquals("a b", Multipart.boundaryOf("multipart/form-data; boundary=\"a b\"; charset=utf-8"))
        assertEquals("x-1", Multipart.boundaryOf("Multipart/Form-Data;Boundary=x-1"))
        assertNull(Multipart.boundaryOf("application/json"))
        assertNull(Multipart.boundaryOf("multipart/form-data"))
        assertNull(Multipart.boundaryOf(null))
    }

    @Test
    fun fieldsAndAFileAreSeparated() {
        // Bytes inside the file that look like the start of a boundary must not end it early.
        val audio = byteArrayOf(0, 1, 2, 13, 10, 45, 45, 88, 121, 0, -1, -2) + "\r\n--Xy not it\r\n".toByteArray()
        val parts = Multipart.parse(
            "multipart/form-data; boundary=XyZ",
            body("XyZ", Triple("model", null, "whisper-1".toByteArray()), Triple("response_format", null, "text".toByteArray()), Triple("file", "a b.wav", audio)),
        )
        assertEquals(listOf("model", "response_format", "file"), parts.map { it.name })
        assertEquals("whisper-1", parts[0].text)
        assertNull(parts[0].fileName)
        assertEquals("a b.wav", parts[2].fileName)
        assertEquals("audio/wav", parts[2].contentType)
        assertArrayEquals(audio, parts[2].data)
    }

    @Test
    fun anEmptyFieldIsAnEmptyPart() {
        val parts = Multipart.parse("multipart/form-data; boundary=B", body("B", Triple("language", null, ByteArray(0))))
        assertEquals(1, parts.size)
        assertEquals("", parts[0].text)
    }

    @Test
    fun aBodyThatIsNotWellFormedIsExplained() {
        fun fails(contentType: String?, bytes: ByteArray) = try {
            Multipart.parse(contentType, bytes)
            fail("expected a failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, !e.message.isNullOrBlank())
        }
        fails("application/json", "{}".toByteArray())
        fails("multipart/form-data; boundary=B", "nothing here".toByteArray())
        // Cut off before the closing boundary.
        fails("multipart/form-data; boundary=B", "--B\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nvalue".toByteArray())
        // No end of headers.
        fails("multipart/form-data; boundary=B", "--B\r\nContent-Disposition: form-data; name=\"a\"".toByteArray())
        fails("multipart/form-data; boundary=B", "--Bjunk".toByteArray())
    }

    // ---- WAV ---------------------------------------------------------------------------

    private fun wav(
        samples: ByteArray, channels: Int = 1, rate: Int = 16_000, bits: Int = 16, format: Int = 1, declared: Int? = null, extensible: Boolean = false,
    ): ByteArray {
        val fmtSize = if (extensible) 40 else 16
        val buffer = ByteBuffer.allocate(12 + 8 + fmtSize + 8 + samples.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(buffer.capacity() - 8).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(fmtSize)
        buffer.putShort((if (extensible) 0xFFFE else format).toShort()).putShort(channels.toShort()).putInt(rate)
        buffer.putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        if (extensible) {
            buffer.putShort(22).putShort(bits.toShort()).putInt(0).putShort(format.toShort())
            buffer.put(ByteArray(14))
        }
        buffer.put("data".toByteArray()).putInt(declared ?: samples.size).put(samples)
        return buffer.array()
    }

    private fun pcm16(vararg values: Int): ByteArray =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> values.forEach { b.putShort(it.toShort()) } }.array()

    @Test
    fun sixteenBitSamplesAreScaledToBetweenMinusOneAndOne() {
        val decoded = WavFile.decode(wav(pcm16(0, 16384, -16384, 32767, -32768), rate = 22_050))!!
        assertEquals(22_050, decoded.sampleRate)
        assertArrayEquals(floatArrayOf(0f, 0.5f, -0.5f, 32767 / 32768f, -1f), decoded.samples, 1e-6f)
    }

    @Test
    fun stereoIsMixedDownToMono() {
        val decoded = WavFile.decode(wav(pcm16(16384, -16384, 32767, 0), channels = 2))!!
        assertArrayEquals(floatArrayOf(0f, 32767 / 65536f), decoded.samples, 1e-6f)
    }

    @Test
    fun otherSampleSizesAreRead() {
        val eight = WavFile.decode(wav(byteArrayOf(128.toByte(), 255.toByte(), 0), bits = 8))!!
        assertArrayEquals(floatArrayOf(0f, 127 / 128f, -1f), eight.samples, 1e-6f)
        val three = WavFile.decode(wav(byteArrayOf(0, 0, 0x40, 0, 0, 0xC0.toByte()), bits = 24))!!
        assertArrayEquals(floatArrayOf(0.5f, -0.5f), three.samples, 1e-6f)
        val float = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.25f).putFloat(-0.75f).array()
        assertArrayEquals(floatArrayOf(0.25f, -0.75f), WavFile.decode(wav(float, bits = 32, format = 3))!!.samples, 1e-6f)
        assertArrayEquals(floatArrayOf(0.25f, -0.75f), WavFile.decode(wav(float, bits = 32, format = 3, extensible = true))!!.samples, 1e-6f)
    }

    @Test
    fun aStreamedFileWithAnUnknownLengthIsReadToItsEnd() {
        val decoded = WavFile.decode(wav(pcm16(1000, 2000, 3000), declared = 0))!!
        assertEquals(3, decoded.samples.size)
        assertEquals(3, WavFile.decode(wav(pcm16(1000, 2000, 3000), declared = -1))!!.samples.size)
    }

    @Test
    fun somethingThatIsNotWavIsLeftForOtherDecoders() {
        assertNull(WavFile.decode("ID3 this is an mp3".toByteArray()))
        assertNull(WavFile.decode(ByteArray(0)))
    }

    @Test
    fun aCompressedWavIsExplained() {
        try {
            WavFile.decode(wav(pcm16(1, 2), format = 2))
            fail("expected a failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains("compressed"))
        }
        try {
            WavFile.decode(wav(ByteArray(0)).copyOf(30))
            fail("expected a failure")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    // ---- sample rate -------------------------------------------------------------------

    private fun rms(a: FloatArray, from: Int = 0, to: Int = a.size): Double {
        var s = 0.0
        for (i in from until to) s += a[i] * a[i]
        return sqrt(s / (to - from))
    }

    @Test
    fun theSameRateChangesNothing() {
        val x = floatArrayOf(0.1f, 0.2f)
        assertTrue(Resample.toRate(x, 16_000, 16_000) === x)
    }

    @Test
    fun aSteadySoundStaysSteadyWhenGoingDownOrUp() {
        val steady = FloatArray(4_800) { 0.5f }
        val down = Resample.toRate(steady, 48_000, 16_000)
        assertEquals(1_600, down.size)
        assertTrue(down.all { Math.abs(it - 0.5f) < 1e-5f })
        val up = Resample.toRate(FloatArray(800) { 0.25f }, 8_000, 16_000)
        assertEquals(1_600, up.size)
        assertTrue(up.all { Math.abs(it - 0.25f) < 1e-5f })
    }

    @Test
    fun goingUpJoinsSamplesWithStraightLines() {
        val up = Resample.toRate(floatArrayOf(0f, 1f, 0f), 8_000, 16_000)
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f, 0.5f, 0f, 0f), up, 1e-6f)
    }

    @Test
    fun goingDownAveragesInsteadOfSkipping() {
        // Alternating samples average to the middle; picking every third sample would give a jagged result.
        val alternating = FloatArray(9) { if (it % 2 == 0) 1f else -1f }
        val down = Resample.toRate(alternating, 3, 1)
        assertEquals(3, down.size)
        assertEquals(1f / 3f, down[0], 1e-6f)
        assertEquals(-1f / 3f, down[1], 1e-6f)
    }

    @Test
    fun aVoiceToneKeepsItsLoudnessAndAHighToneIsNotFoldedBackIn() {
        val rate = 48_000
        val voice = FloatArray(rate) { (0.5 * sin(2 * PI * 440 * it / rate)).toFloat() }
        val down = Resample.toRate(voice, rate, 16_000)
        assertEquals(0.5 / sqrt(2.0), rms(down), 0.02)
        // A 20 kHz tone cannot exist at 16 kHz; averaging keeps most of it from showing up as a false lower tone.
        val high = FloatArray(rate) { (0.5 * sin(2 * PI * 20_000 * it / rate)).toFloat() }
        assertTrue(rms(Resample.toRate(high, rate, 16_000)) < 0.15)
    }

    // ---- cutting long recordings -------------------------------------------------------

    @Test
    fun aShortRecordingIsNotCut() {
        val x = FloatArray(16_000 * 20) { 0.3f }
        val pieces = Audio.split(x, 16_000)
        assertEquals(1, pieces.size)
        assertTrue(pieces[0] === x)
    }

    @Test
    fun aLongRecordingIsCutAtAPauseAndNothingIsLost() {
        val rate = 16_000
        val loud = FloatArray(rate * 75) { (0.4 * sin(2 * PI * 300 * it / rate)).toFloat() }
        // Pauses at 27.0–27.5 s and 56.0–56.5 s.
        for (i in 27 * rate until (27 * rate + rate / 2)) loud[i] = 0f
        for (i in 56 * rate until (56 * rate + rate / 2)) loud[i] = 0f
        val pieces = Audio.split(loud, rate, 30)
        assertEquals(3, pieces.size)
        assertTrue(pieces.all { it.size <= 30 * rate })
        val firstCut = pieces[0].size.toDouble() / rate
        assertTrue("cut at $firstCut s", firstCut in 27.0..27.5)
        val secondCut = (pieces[0].size + pieces[1].size).toDouble() / rate
        assertTrue("second cut at $secondCut s", secondCut in 56.0..56.5)
        assertArrayEquals(loud, pieces.reduce { a, b -> a + b }, 0f)
    }
}
