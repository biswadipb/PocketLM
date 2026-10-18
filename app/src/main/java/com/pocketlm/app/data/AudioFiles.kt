package com.pocketlm.app.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.min

/** Reading the parts of a `multipart/form-data` request body, such as an uploaded audio file with its options. */
object Multipart {
    class Part(val name: String, val fileName: String?, val contentType: String?, val data: ByteArray) {
        val text: String get() = data.toString(Charsets.UTF_8)
    }

    /** The boundary named in a Content-Type header such as `multipart/form-data; boundary="xyz"`, or null. */
    fun boundaryOf(contentType: String?): String? {
        if (contentType == null || !contentType.trim().startsWith("multipart/", ignoreCase = true)) return null
        val match = Regex("""boundary\s*=\s*(?:"([^"]+)"|([^;\s]+))""", RegexOption.IGNORE_CASE).find(contentType) ?: return null
        return (match.groups[1]?.value ?: match.groups[2]?.value)?.takeIf { it.isNotEmpty() && it.length <= 200 }
    }

    /** Every part of [body]. Throws [IllegalArgumentException], saying what is wrong, if it is not well formed. */
    fun parse(contentType: String?, body: ByteArray): List<Part> {
        val boundary = boundaryOf(contentType) ?: throw IllegalArgumentException("The request is not multipart/form-data.")
        val opening = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val separator = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)
        val blank = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

        var pos = indexOf(body, opening, 0)
        if (pos < 0) throw IllegalArgumentException("The multipart body has no parts.")
        pos += opening.size
        val parts = ArrayList<Part>()
        while (true) {
            if (pos + 1 < body.size && body[pos] == '-'.code.toByte() && body[pos + 1] == '-'.code.toByte()) break
            if (pos + 1 >= body.size || body[pos] != '\r'.code.toByte() || body[pos + 1] != '\n'.code.toByte()) {
                throw IllegalArgumentException("The multipart body is malformed.")
            }
            pos += 2
            // A part with no headers at all starts straight with the blank line.
            val headersText: String
            val dataStart: Int
            if (body.size >= pos + 2 && body[pos] == '\r'.code.toByte() && body[pos + 1] == '\n'.code.toByte()) {
                headersText = ""
                dataStart = pos + 2
            } else {
                val end = indexOf(body, blank, pos)
                if (end < 0) throw IllegalArgumentException("A part of the multipart body has no end of headers.")
                headersText = String(body, pos, end - pos, Charsets.UTF_8)
                dataStart = end + blank.size
            }
            val next = indexOf(body, separator, dataStart)
            if (next < 0) throw IllegalArgumentException("The multipart body is cut short.")
            parts += part(headersText, body.copyOfRange(dataStart, next))
            pos = next + separator.size
        }
        return parts
    }

    private fun part(headersText: String, data: ByteArray): Part {
        val headers = headersText.lines().filter { ':' in it }.associate {
            it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
        }
        val disposition = headers["content-disposition"].orEmpty()
        fun param(name: String): String? =
            Regex("""(?:^|[;\s])$name\s*=\s*(?:"([^"]*)"|([^;\s]+))""", RegexOption.IGNORE_CASE).find(disposition)
                ?.let { it.groups[1]?.value ?: it.groups[2]?.value }
        return Part(param("name").orEmpty(), param("filename"), headers["content-type"], data)
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int {
        if (pattern.isEmpty()) return from
        val last = data.size - pattern.size
        var i = from.coerceAtLeast(0)
        val first = pattern[0]
        while (i <= last) {
            if (data[i] == first) {
                var k = 1
                while (k < pattern.size && data[i + k] == pattern[k]) k++
                if (k == pattern.size) return i
            }
            i++
        }
        return -1
    }
}

/** Reading a WAV file into mono samples between -1 and 1. */
object WavFile {
    class Decoded(val samples: FloatArray, val sampleRate: Int)

    /** True if [bytes] starts like a WAV file. */
    fun looksLikeWav(bytes: ByteArray): Boolean =
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WAVE"

    /**
     * The samples of a WAV file, or null if [bytes] is not one. Throws [IllegalArgumentException], saying why, if it is a
     * WAV file of a kind that is not supported (only PCM and floating point are).
     */
    fun decode(bytes: ByteArray): Decoded? {
        if (!looksLikeWav(bytes)) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var dataStart = -1
        var dataLength = 0
        var at = 12
        while (at + 8 <= bytes.size) {
            val id = String(bytes, at, 4, Charsets.ISO_8859_1)
            val declared = buffer.getInt(at + 4).toLong() and 0xFFFFFFFFL
            val body = at + 8
            if (id == "fmt ") {
                if (body + 16 > bytes.size) throw IllegalArgumentException("The WAV file is cut short.")
                format = buffer.getShort(body).toInt() and 0xFFFF
                channels = buffer.getShort(body + 2).toInt() and 0xFFFF
                rate = buffer.getInt(body + 4)
                bits = buffer.getShort(body + 14).toInt() and 0xFFFF
                // "Extensible" files name the real format in the sub-format that follows.
                if (format == 0xFFFE && declared >= 26 && body + 26 <= bytes.size) format = buffer.getShort(body + 24).toInt() and 0xFFFF
            } else if (id == "data") {
                dataStart = body
                // A stream being written may say 0 or "unknown"; take whatever is there.
                dataLength = if (declared == 0L || declared > bytes.size - body) bytes.size - body else declared.toInt()
                break
            }
            val next = body.toLong() + declared + (declared and 1L)
            if (next > bytes.size) break
            at = next.toInt()
        }
        if (dataStart < 0 || channels <= 0 || rate <= 0) throw IllegalArgumentException("The WAV file has no audio in it.")
        if (format != 1 && format != 3) throw IllegalArgumentException("This WAV file is compressed. Send PCM, or another audio format such as MP3 or M4A.")
        val bytesPerSample = bits / 8
        if (bytesPerSample !in 1..8 || (format == 3 && bytesPerSample != 4 && bytesPerSample != 8) || (format == 1 && bytesPerSample > 4)) {
            throw IllegalArgumentException("This WAV file uses $bits bits per sample, which is not supported.")
        }
        val frames = dataLength / (bytesPerSample * channels)
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val p = dataStart + (f * channels + c) * bytesPerSample
                sum += when {
                    format == 3 && bytesPerSample == 4 -> buffer.getFloat(p)
                    format == 3 -> buffer.getDouble(p).toFloat()
                    bytesPerSample == 1 -> ((bytes[p].toInt() and 0xFF) - 128) / 128f
                    bytesPerSample == 2 -> buffer.getShort(p) / 32768f
                    bytesPerSample == 3 -> (((bytes[p + 2].toInt() shl 16) or ((bytes[p + 1].toInt() and 0xFF) shl 8) or (bytes[p].toInt() and 0xFF))) / 8388608f
                    else -> buffer.getInt(p) / 2147483648f
                }
            }
            out[f] = (sum / channels).coerceIn(-1f, 1f)
        }
        return Decoded(out, rate)
    }
}

/** Changing the sample rate of audio. */
object Resample {
    /**
     * [samples] at [to] hertz instead of [from]. Going down averages the samples that fall into each new one, which keeps
     * higher sounds from folding back into the voice; going up joins the samples with straight lines.
     */
    fun toRate(samples: FloatArray, from: Int, to: Int): FloatArray {
        require(from > 0 && to > 0) { "Sample rates must be positive" }
        if (from == to || samples.isEmpty()) return samples
        val outLength = ((samples.size.toLong() * to + from / 2) / from).toInt().coerceAtLeast(1)
        val out = FloatArray(outLength)
        val step = from.toDouble() / to
        if (step <= 1.0) {
            for (i in out.indices) {
                val p = i * step
                val a = floor(p).toInt().coerceAtMost(samples.lastIndex)
                val b = min(a + 1, samples.lastIndex)
                val t = (p - a).toFloat()
                out[i] = samples[a] * (1 - t) + samples[b] * t
            }
        } else {
            for (i in out.indices) {
                val start = i * step
                val end = min(start + step, samples.size.toDouble())
                var sum = 0.0
                var weight = 0.0
                var k = floor(start).toInt()
                while (k < end && k < samples.size) {
                    val w = min(k + 1.0, end) - maxOf(k.toDouble(), start)
                    if (w > 0) {
                        sum += samples[k] * w
                        weight += w
                    }
                    k++
                }
                out[i] = if (weight > 0) (sum / weight).toFloat() else 0f
            }
        }
        return out
    }
}
