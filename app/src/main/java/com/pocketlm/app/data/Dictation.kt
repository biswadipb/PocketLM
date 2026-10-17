package com.pocketlm.app.data

import kotlin.math.abs
import kotlin.math.sqrt

/** The speech models that can be downloaded for dictation on the phone. */
object SpeechModels {
    /** Qwen3-ASR, which llama.cpp runs through its multimodal library: a model file and an audio add-on. */
    val catalog = listOf(
        CatalogModel(
            name = "Qwen3-ASR 0.6B (speech to text)",
            description = "Listens on the phone and writes down what you say, in many languages. Nothing is sent anywhere. About 1 GB in all.",
            fileName = "Qwen3-ASR-0.6B-Q8_0.gguf",
            url = "https://huggingface.co/ggml-org/Qwen3-ASR-0.6B-GGUF/resolve/main/Qwen3-ASR-0.6B-Q8_0.gguf",
            sizeMb = 767,
            projectorFileName = "mmproj-Qwen3-ASR-0.6B-Q8_0.gguf",
            projectorUrl = "https://huggingface.co/ggml-org/Qwen3-ASR-0.6B-GGUF/resolve/main/mmproj-Qwen3-ASR-0.6B-Q8_0.gguf",
        ),
    )

    private val SPEECH_NAME = Regex("(^|[-_. ])asr([-_. ]|$)", RegexOption.IGNORE_CASE)

    /** True for a file that is a speech model (or its audio add-on) and so should not be offered as a chat model. */
    fun isSpeechFile(fileName: String): Boolean = SPEECH_NAME.containsMatchIn(fileName)

    /** The audio add-on that goes with [modelFile], named the way the model's publisher names it, if it is among [projectors]. */
    fun projectorFor(modelFile: String, projectors: List<LocalModel>): LocalModel? =
        projectors.firstOrNull { it.fileName == "mmproj-$modelFile" }
}

/** Tidying what a speech model wrote. */
object Transcript {
    private val LANGUAGE_TAG = Regex("^\\s*language\\s+[\\p{L}-]+\\s*<asr_text>", RegexOption.IGNORE_CASE)
    private val MARKERS = Regex("</?asr_text>|<\\|[a-z_]+\\|>", RegexOption.IGNORE_CASE)

    /** The words themselves: no "language English" tag, no markers, tidy spacing. */
    fun clean(raw: String): String =
        MARKERS.replace(LANGUAGE_TAG.replace(raw, ""), "").replace(Regex("[ \\t]+"), " ").trim()
}

/** Cutting the quiet parts off a recording, so the model has less to listen to and nothing to make up. */
object Audio {
    private const val FRAME_MS = 20
    private const val KEEP_MS = 200

    /** Root-mean-square loudness of [samples] from [from] to [to]. */
    private fun loudness(samples: FloatArray, from: Int, to: Int): Float {
        if (to <= from) return 0f
        var sum = 0.0
        for (i in from until to) sum += samples[i] * samples[i]
        return sqrt(sum / (to - from)).toFloat()
    }

    /**
     * [samples] without the silence at the start and end (a little is kept, so words are not clipped). A recording that
     * is quiet all the way through gives an empty array.
     */
    fun trimSilence(samples: FloatArray, sampleRate: Int, threshold: Float = 0.01f): FloatArray {
        val frame = sampleRate * FRAME_MS / 1000
        if (samples.size < frame || frame <= 0) return samples
        var first = -1
        var last = -1
        var at = 0
        while (at + frame <= samples.size) {
            if (loudness(samples, at, at + frame) >= threshold) {
                if (first < 0) first = at
                last = at + frame
            }
            at += frame
        }
        if (first < 0) return FloatArray(0)
        val keep = sampleRate * KEEP_MS / 1000
        return samples.copyOfRange((first - keep).coerceAtLeast(0), (last + keep).coerceAtMost(samples.size))
    }

    /** The loudest point of [samples], 0 to 1. */
    fun peak(samples: FloatArray): Float = samples.maxOfOrNull { abs(it) } ?: 0f
}
