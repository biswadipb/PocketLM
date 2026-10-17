package com.pocketlm.app.data

/**
 * Text-to-speech models for Piko's own voice (run through llama.cpp's audio generation), kept apart from chat models.
 * A voice is a backbone model plus an audio add-on ("mmproj") that turns what the model works out into sound.
 */
object VoiceModels {
    private val voiceName = Regex("(^|[-_. ])tts([-_. ]|$)", RegexOption.IGNORE_CASE)
    private val quantisation = Regex("[-_.](?:i?q\\d\\w*|bf16|f16|f32)$", RegexOption.IGNORE_CASE)

    /** True for a text-to-speech model file (or its audio add-on), which is not a chat model and must not be offered as one. */
    fun isVoiceFile(fileName: String): Boolean = voiceName.containsMatchIn(fileName)

    /** The name of a model without "mmproj-" in front and the kind of quantisation (Q4_K_M, Q8_0, bf16…) at the end. */
    fun baseName(fileName: String): String =
        quantisation.replace(fileName.removeSuffix(".gguf").removePrefix("mmproj-"), "").lowercase()

    /** The audio add-on that goes with [modelFile], whatever quantisation each was published in, if it is among [projectors]. */
    fun projectorFor(modelFile: String, projectors: List<LocalModel>): LocalModel? {
        val base = baseName(modelFile)
        return projectors.filter { baseName(it.fileName) == base }.minByOrNull { it.sizeBytes }
    }

    /** The voice offered for download: the official Qwen3-TTS files from ggml-org on Hugging Face. */
    val catalog = listOf(
        CatalogModel(
            name = "Qwen3-TTS 1.7B (natural voice)",
            description = "A model that speaks replies in a natural voice, in ten languages, with no connection. About 1.5 GB in all, " +
                "and slower than the phone's own voice.",
            fileName = "Qwen3-TTS-12Hz-1.7B-Base-Q4_K_M.gguf",
            url = "https://huggingface.co/ggml-org/Qwen3-TTS-12Hz-1.7B-Base-GGUF/resolve/main/Qwen3-TTS-12Hz-1.7B-Base-Q4_K_M.gguf",
            sizeMb = 988,
            projectorFileName = "mmproj-Qwen3-TTS-12Hz-1.7B-Base-Q8_0.gguf",
            projectorUrl = "https://huggingface.co/ggml-org/Qwen3-TTS-12Hz-1.7B-Base-GGUF/resolve/main/mmproj-Qwen3-TTS-12Hz-1.7B-Base-Q8_0.gguf",
        ),
    )

    /** Languages Qwen3-TTS speaks, by the code the model takes. */
    val languages = listOf(
        "en" to "English", "zh" to "Chinese", "de" to "German", "it" to "Italian", "pt" to "Portuguese",
        "es" to "Spanish", "ja" to "Japanese", "ko" to "Korean", "fr" to "French", "ru" to "Russian",
    )

    /** The language to start with: the phone's, if the model speaks it, otherwise English. */
    fun defaultLanguage(phoneLanguage: String): String = languages.firstOrNull { it.first == phoneLanguage.lowercase() }?.first ?: "en"

    /** The most frames to let the model make for [text]. It speaks about 12 frames a second, a little under 15 characters a second. */
    fun maxFrames(text: String): Int = (text.length * 1.7 + 40).toInt().coerceIn(60, 500)

    /** The recording used to imitate a voice may not be larger than this. */
    const val MAX_SAMPLE_BYTES = 20L * 1024 * 1024

    /** Quietest speech is raised, loudest is kept from clipping: [samples] scaled so the peak is [target], never more than [maxGain] times louder. */
    fun levelled(samples: FloatArray, target: Float = 0.9f, maxGain: Float = 3f): FloatArray {
        val peak = samples.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
        if (peak <= 0.0001f) return samples
        val gain = (target / peak).coerceAtMost(maxGain)
        return if (gain in 0.99f..1.01f) samples else FloatArray(samples.size) { (samples[it] * gain).coerceIn(-1f, 1f) }
    }
}
