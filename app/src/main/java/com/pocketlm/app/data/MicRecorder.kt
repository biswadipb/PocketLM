package com.pocketlm.app.data

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Records the microphone as mono floats for the speech model. Needs the microphone permission. Stops by itself after
 * [maxSeconds]; [stop] returns everything heard so far.
 */
class MicRecorder(private val sampleRate: Int = 16_000, private val maxSeconds: Int = 60, private val onLimit: () -> Unit = {}) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val chunks = ArrayList<FloatArray>()

    @Volatile
    private var running = false

    /** Starts listening. Returns false if the microphone can't be used (no permission, or another app has it). */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minimum <= 0) return false
        val audio = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
                maxOf(minimum, sampleRate * 4),
            )
        } catch (e: SecurityException) {
            return false
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            return false
        }
        try {
            audio.startRecording()
        } catch (e: IllegalStateException) {
            audio.release()
            return false
        }
        record = audio
        running = true
        thread = Thread({
            val buffer = FloatArray(sampleRate / 10)
            var total = 0
            while (running) {
                val n = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) break
                synchronized(chunks) { chunks += buffer.copyOf(n) }
                total += n
                if (total >= sampleRate * maxSeconds) {
                    running = false
                    onLimit()
                }
            }
        }, "pocketlm-mic").apply { isDaemon = true; start() }
        return true
    }

    /** Stops and returns what was heard. Safe to call more than once. */
    fun stop(): FloatArray {
        running = false
        thread?.join(1_000)
        thread = null
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
        return synchronized(chunks) {
            val out = FloatArray(chunks.sumOf { it.size })
            var at = 0
            for (chunk in chunks) {
                chunk.copyInto(out, at)
                at += chunk.size
            }
            chunks.clear()
            out
        }
    }
}
