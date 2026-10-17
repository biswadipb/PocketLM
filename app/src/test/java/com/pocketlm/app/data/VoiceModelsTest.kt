package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModelsTest {

    private fun model(name: String, size: Long = 1) = LocalModel(name, "/models/$name", size)

    @Test
    fun speechModelsAreToldApartFromChatModels() {
        assertTrue(VoiceModels.isVoiceFile("Qwen3-TTS-12Hz-1.7B-Base-Q4_K_M.gguf"))
        assertTrue(VoiceModels.isVoiceFile("mmproj-Qwen3-TTS-12Hz-1.7B-Base-Q8_0.gguf"))
        assertTrue(VoiceModels.isVoiceFile("pocket-tts.gguf"))
        assertFalse(VoiceModels.isVoiceFile("Qwen3-ASR-0.6B-Q8_0.gguf"))
        assertFalse(VoiceModels.isVoiceFile("Qwen2.5-3B-Instruct-Q4_K_M.gguf"))
        assertFalse(VoiceModels.isVoiceFile("settings-model.gguf"))
        assertFalse(SpeechModels.isSpeechFile("Qwen3-TTS-12Hz-1.7B-Base-Q4_K_M.gguf"))
    }

    @Test
    fun theAddOnIsMatchedWhateverQuantisationEachWasPublishedIn() {
        assertEquals("qwen3-tts-12hz-1.7b-base", VoiceModels.baseName("Qwen3-TTS-12Hz-1.7B-Base-Q4_K_M.gguf"))
        assertEquals("qwen3-tts-12hz-1.7b-base", VoiceModels.baseName("mmproj-Qwen3-TTS-12Hz-1.7B-Base-Q8_0.gguf"))
        assertEquals("qwen3-tts-12hz-1.7b-base", VoiceModels.baseName("mmproj-Qwen3-TTS-12Hz-1.7B-Base-bf16.gguf"))
        val small = model("mmproj-Qwen3-TTS-12Hz-1.7B-Base-Q8_0.gguf", 446)
        val big = model("mmproj-Qwen3-TTS-12Hz-1.7B-Base-bf16.gguf", 669)
        val other = model("mmproj-Pocket-TTS-Q8_0.gguf", 10)
        assertEquals(small, VoiceModels.projectorFor("Qwen3-TTS-12Hz-1.7B-Base-Q4_K_M.gguf", listOf(other, big, small)))
        assertNull(VoiceModels.projectorFor("Qwen3-TTS-12Hz-1.7B-Base-Q4_K_M.gguf", listOf(other)))
        assertNull(VoiceModels.projectorFor("Qwen3-TTS-12Hz-1.7B-Base-Q4_K_M.gguf", emptyList()))
    }

    @Test
    fun theDownloadOfferedPairsItsOwnFiles() {
        val voice = VoiceModels.catalog.first()
        assertTrue(VoiceModels.isVoiceFile(voice.fileName))
        assertTrue(VoiceModels.isVoiceFile(voice.projectorFileName!!))
        assertEquals(voice.projectorFileName, VoiceModels.projectorFor(voice.fileName, listOf(model(voice.projectorFileName!!)))?.fileName)
        assertTrue(voice.url.startsWith("https://huggingface.co/ggml-org/") && voice.url.endsWith(voice.fileName))
        assertTrue(voice.projectorUrl!!.endsWith(voice.projectorFileName!!))
    }

    @Test
    fun theLanguageStartsAsThePhonesIfTheModelSpeaksIt() {
        assertEquals("de", VoiceModels.defaultLanguage("de"))
        assertEquals("fr", VoiceModels.defaultLanguage("FR"))
        assertEquals("en", VoiceModels.defaultLanguage("hi"))
        assertEquals("en", VoiceModels.defaultLanguage(""))
        assertEquals(10, VoiceModels.languages.size)
    }

    @Test
    fun theLengthOfSpeechIsBoundedByTheText() {
        assertEquals(60, VoiceModels.maxFrames("Hi"))
        assertEquals(500, VoiceModels.maxFrames("x".repeat(5000)))
        assertTrue(VoiceModels.maxFrames("x".repeat(100)) in 150..250)
    }

    @Test
    fun speechIsLevelledWithoutClippingOrAmplifyingSilence() {
        val quiet = floatArrayOf(0.1f, -0.2f, 0.05f)
        val raised = VoiceModels.levelled(quiet)
        assertEquals(0.6f, raised.maxOf { kotlin.math.abs(it) }, 1e-5f) // 3x is the most it is raised
        val loud = floatArrayOf(1.0f, -1.0f)
        assertEquals(0.9f, VoiceModels.levelled(loud).maxOf { kotlin.math.abs(it) }, 1e-5f)
        val silence = FloatArray(10)
        assertTrue(VoiceModels.levelled(silence) === silence)
        val fine = floatArrayOf(0.9f, -0.45f)
        assertTrue(VoiceModels.levelled(fine) === fine)
    }

    // ---- sentences for a voice --------------------------------------------------------------

    @Test
    fun aReplyIsSpokenASentenceAtATime() {
        val text = "Hello there, how are you today? I am fine. Thank you for asking!"
        assertEquals(listOf("Hello there, how are you today?", "I am fine. Thank you for asking!"), SpeechText.sentences(text))
    }

    @Test
    fun aVeryShortSentenceJoinsTheNext() {
        assertEquals(listOf("Yes. That is correct, and here is why."), SpeechText.sentences("Yes. That is correct, and here is why."))
        assertEquals(listOf("Ok. Right."), SpeechText.sentences("Ok. Right."))
    }

    @Test
    fun aLongSentenceIsCutAtACommaOrBetweenWords() {
        val long = (1..60).joinToString(" ") { "word$it" } + "."
        val parts = SpeechText.sentences(long, maxChars = 80)
        assertTrue(parts.size > 3)
        assertTrue(parts.all { it.length <= 80 })
        assertEquals(long.replace(Regex("\\s+"), " "), parts.joinToString(" "))
        val withCommas = "First we mix the flour with water, then we let it rest for an hour, after which we shape the dough carefully."
        val cut = SpeechText.sentences(withCommas, maxChars = 60)
        assertTrue(cut.all { it.length <= 60 })
        assertTrue(cut.first().endsWith(","))
    }

    @Test
    fun aWordLongerThanTheLimitStillMakesProgress() {
        val parts = SpeechText.sentences("x".repeat(500), maxChars = 100)
        assertEquals(5, parts.size)
        assertTrue(parts.all { it.length <= 100 })
    }

    @Test
    fun paragraphsAndEmptyTextAreHandled() {
        assertEquals(listOf("First paragraph is here.", "Second paragraph is there."), SpeechText.sentences("First paragraph is here.\n\nSecond paragraph is there."))
        assertTrue(SpeechText.sentences("").isEmpty())
        assertTrue(SpeechText.sentences("   \n  ").isEmpty())
    }
}
