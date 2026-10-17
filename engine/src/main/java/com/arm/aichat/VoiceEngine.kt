package com.arm.aichat

/** The voice model could not be used. The message says why, in words for the user. */
class VoiceException(message: String) : Exception(message)

/**
 * A text-to-speech model that runs on the phone: a backbone model and an audio add-on (llama.cpp's audio generation).
 * It is separate from the chat model and never uses the GPU. Speaking is slower than listening: how much slower depends
 * on the model and the phone, and has not been measured on real phones.
 */
interface VoiceEngine {
    /** File of the loaded voice model, or null. */
    val loadedPath: String?

    /** The sample rate of the speech the loaded model makes, in Hz (for example 24000); 0 when none is loaded. */
    val sampleRate: Int

    /**
     * Loads the voice model at [modelPath] with its audio add-on at [projectorPath], replacing any loaded before.
     * @throws VoiceException if the pair cannot make speech, or cannot be loaded
     */
    suspend fun load(modelPath: String, projectorPath: String, threads: Int = 0)

    /**
     * Speech for [text]: mono samples from -1 to 1 at [sampleRate]. [language] is a code such as "en" (used by models that
     * need one); [speakerFile] is an optional short recording (WAV or MP3) of the voice to imitate. [maxFrames] bounds the
     * length of the speech. Ends early with a [VoiceException] if [cancel] is called.
     */
    suspend fun speak(text: String, language: String = "en", speakerFile: String? = null, maxFrames: Int = 600): FloatArray

    /** Stops the speech being made, as soon as the model finishes its current step. */
    fun cancel()

    suspend fun unload()
}
