package com.arm.aichat

/** The speech model could not be used. The message says why, in words for the user. */
class SpeechException(message: String) : Exception(message)

/**
 * A second, separate model that writes down speech, on the phone. It does not touch the chat model, and it never
 * uses the GPU.
 */
interface SpeechEngine {
    /** File of the loaded speech model, or null. */
    val loadedPath: String?

    /** The sample rate the loaded model listens at, in Hz (16000); 0 when none is loaded. */
    val sampleRate: Int

    /**
     * Loads the speech model at [modelPath] with its audio add-on at [projectorPath]. Replaces any model loaded before.
     * @throws SpeechException if the pair cannot take audio, or cannot be loaded
     */
    suspend fun load(modelPath: String, projectorPath: String, threads: Int = 0)

    /**
     * The raw text the model wrote for [samples] (mono floats from -1 to 1 at [sampleRate]). Needs a loaded model.
     * The text may start with a language tag; see [com.arm.aichat.SpeechText] in the app for cleaning it.
     */
    suspend fun transcribe(samples: FloatArray, maxTokens: Int = 400): String

    suspend fun unload()
}
