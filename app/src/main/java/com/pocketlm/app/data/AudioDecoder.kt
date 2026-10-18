package com.pocketlm.app.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * Turns an uploaded recording into mono samples. WAV files are read directly; anything else the phone can play (MP3, M4A,
 * AAC, OGG, FLAC, AMR…) goes through Android's own decoders. Throws [IllegalArgumentException], with a message fit to
 * show the caller, when the file cannot be used.
 */
object AudioDecoder {
    /** Longer recordings are refused, to keep the memory this takes in check. */
    const val MAX_SECONDS = 300

    fun decode(context: Context, bytes: ByteArray, fileName: String?): WavFile.Decoded {
        val decoded = WavFile.decode(bytes) ?: decodeWithAndroid(context, bytes, fileName)
        if (decoded.samples.size.toLong() > decoded.sampleRate.toLong() * MAX_SECONDS) {
            throw IllegalArgumentException("The recording is longer than ${MAX_SECONDS / 60} minutes.")
        }
        return decoded
    }

    private fun decodeWithAndroid(context: Context, bytes: ByteArray, fileName: String?): WavFile.Decoded {
        val extension = fileName?.substringAfterLast('.', "")?.takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) } ?: "audio"
        val file = File.createTempFile("upload", ".$extension", context.cacheDir)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            file.writeBytes(bytes)
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("No audio was found in that file.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floats = false

            val decoder = MediaCodec.createDecoderByType(mime).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()

            val mono = FloatBuffer()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val changed = decoder.outputFormat
                        rate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floats = changed.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            changed.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    out >= 0 -> {
                        val buffer = decoder.getOutputBuffer(out)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        if (floats) {
                            val view = buffer.asFloatBuffer()
                            while (view.remaining() >= channels) {
                                var sum = 0f
                                repeat(channels) { sum += view.get() }
                                mono.add(sum / channels)
                            }
                        } else {
                            val view = buffer.asShortBuffer()
                            while (view.remaining() >= channels) {
                                var sum = 0f
                                repeat(channels) { sum += view.get() / 32768f }
                                mono.add(sum / channels)
                            }
                        }
                        decoder.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (mono.size > rate.toLong() * MAX_SECONDS) {
                            throw IllegalArgumentException("The recording is longer than ${MAX_SECONDS / 60} minutes.")
                        }
                    }
                }
            }
            return WavFile.Decoded(mono.toArray(), rate)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: IOException) {
            throw IllegalArgumentException("That audio file could not be read.", e)
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("That audio format is not supported on this phone.", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            file.delete()
        }
    }

    private const val TIMEOUT_US = 10_000L

    /** A growing list of samples without boxing each one. */
    private class FloatBuffer {
        private var data = FloatArray(1 shl 16)
        var size = 0
            private set

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
