package com.pocketlm.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.arm.aichat.VoiceEngine
import com.arm.aichat.VoiceException
import com.pocketlm.app.data.SpeechText
import com.pocketlm.app.data.VoiceModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Plays speech as it is made: mono floats at one sample rate, written to the speaker piece by piece. */
class PcmPlayer(private val sampleRate: Int) {
    private val track: AudioTrack
    private var frames = 0L

    @Volatile
    private var stopped = false

    init {
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(maxOf(minimum, sampleRate * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
    }

    /** Queues [samples]; waits while the speaker's buffer is full. */
    fun write(samples: FloatArray) {
        var at = 0
        while (at < samples.size && !stopped) {
            val n = track.write(samples, at, samples.size - at, AudioTrack.WRITE_BLOCKING)
            if (n < 0) return
            at += n
        }
        frames += samples.size
    }

    /** Waits for everything queued to be heard, then lets go of the speaker. */
    fun finish() {
        if (!stopped) {
            track.stop() // lets what is queued play to its end
            val deadline = System.currentTimeMillis() + (frames * 1000 / sampleRate) + 2_000
            while (!stopped && track.playbackHeadPosition < frames && System.currentTimeMillis() < deadline) Thread.sleep(25)
        }
        release()
    }

    /** Cuts the sound off now. */
    fun stop() {
        stopped = true
        runCatching { track.pause(); track.flush() }
        release()
    }

    @Synchronized
    private fun release() {
        runCatching { track.release() }
    }
}

/**
 * Reads a reply aloud with the voice model. The reply is spoken a sentence at a time: while one is heard, the next is
 * being made, so a long reply starts after the first sentence rather than after all of it.
 */
class NeuralSpeaker(
    private val engine: VoiceEngine,
    private val scope: CoroutineScope,
    /** Gets the id of the message being read, or null when the voice stops. */
    private val onSpeaking: (String?) -> Unit,
    private val onProblem: (String) -> Unit,
) {
    /** The files and choices a reading needs. */
    class Setup(val modelPath: String, val projectorPath: String, val language: String, val sampleFile: String?)

    private var job: Job? = null

    @Volatile
    private var player: PcmPlayer? = null

    fun speak(id: String, markdown: String, setup: Setup) {
        halt()
        val sentences = SpeechText.sentences(SpeechText.clean(markdown))
        if (sentences.isEmpty()) return
        job = scope.launch {
            onSpeaking(id)
            try {
                if (engine.loadedPath != setup.modelPath) engine.load(setup.modelPath, setup.projectorPath)
                coroutineScope {
                    val made = Channel<FloatArray>(capacity = 2)
                    launch {
                        try {
                            for (sentence in sentences) {
                                made.send(engine.speak(sentence, setup.language, setup.sampleFile, VoiceModels.maxFrames(sentence)))
                            }
                            made.close()
                        } catch (t: Throwable) {
                            made.close(t)
                            throw t
                        }
                    }
                    withContext(Dispatchers.IO) {
                        for (piece in made) {
                            val out = player ?: PcmPlayer(engine.sampleRate).also { player = it }
                            out.write(VoiceModels.levelled(piece))
                            // A short pause between sentences, as a speaker would take.
                            out.write(FloatArray(engine.sampleRate / 8))
                        }
                        player?.finish()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VoiceException) {
                onProblem(e.message ?: "The natural voice stopped.")
            } catch (e: Exception) {
                onProblem("The natural voice failed: ${e.message ?: e::class.simpleName}")
            } finally {
                player?.stop()
                player = null
                onSpeaking(null)
            }
        }
    }

    private fun halt() {
        engine.cancel()
        job?.cancel()
        job = null
        player?.stop()
        player = null
    }

    fun stop() {
        halt()
        onSpeaking(null)
    }
}
