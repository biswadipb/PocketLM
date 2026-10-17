package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationTest {

    @Test
    fun theLanguageTagAndMarkersAreRemoved() {
        assertEquals("Hello, how are you?", Transcript.clean("language English<asr_text>Hello, how are you?"))
        assertEquals("नमस्ते दोस्तों", Transcript.clean("language Hindi<asr_text>नमस्ते दोस्तों"))
        assertEquals("Just words", Transcript.clean("<asr_text>Just words</asr_text>"))
        assertEquals("Plain text stays", Transcript.clean("  Plain   text stays \n"))
    }

    @Test
    fun speechThatNeverHadATagIsUntouched() {
        assertEquals("language is hard to learn", Transcript.clean("language is hard to learn"))
        assertEquals("", Transcript.clean("language English<asr_text>"))
        assertEquals("", Transcript.clean(""))
    }

    @Test
    fun silenceAtBothEndsIsCutButWordsAreNotClipped() {
        val rate = 16_000
        val quiet = FloatArray(rate) { 0.001f }
        val speech = FloatArray(rate / 2) { if (it % 2 == 0) 0.3f else -0.3f }
        val trimmed = Audio.trimSilence(quiet + speech + quiet, rate)
        assertTrue(trimmed.size < rate * 2.5)
        assertTrue(trimmed.size >= speech.size)
        assertTrue(trimmed.size <= speech.size + 2 * rate * 220 / 1000)
        assertTrue(Audio.peak(trimmed) >= 0.3f)
    }

    @Test
    fun aRecordingThatIsQuietThroughoutBecomesEmpty() {
        assertEquals(0, Audio.trimSilence(FloatArray(32_000) { 0.002f }, 16_000).size)
        assertEquals(0, Audio.trimSilence(FloatArray(32_000), 16_000).size)
    }

    @Test
    fun aVeryShortRecordingIsLeftAlone() {
        val tiny = FloatArray(100) { 0.5f }
        assertEquals(tiny.size, Audio.trimSilence(tiny, 16_000).size)
    }

    @Test
    fun speechModelFilesAreToldApartFromChatModels() {
        assertTrue(SpeechModels.isSpeechFile("Qwen3-ASR-0.6B-Q8_0.gguf"))
        assertTrue(SpeechModels.isSpeechFile("mmproj-Qwen3-ASR-0.6B-Q8_0.gguf"))
        assertTrue(SpeechModels.isSpeechFile("whisper_asr_small.gguf"))
        assertFalse(SpeechModels.isSpeechFile("Qwen2.5-0.5B-Instruct-q4_k_m.gguf"))
        assertFalse(SpeechModels.isSpeechFile("llama-3.2-1b-instruct-q4_k_m.gguf"))
        assertFalse(SpeechModels.isSpeechFile("Phi-3-mini-4k-instruct-fasr.gguf"))
    }

    @Test
    fun theAudioAddOnIsFoundByItsPublishersName() {
        val projectors = listOf(LocalModel("mmproj-Qwen3-ASR-0.6B-Q8_0.gguf", "/p/a", 1), LocalModel("mmproj-other.gguf", "/p/b", 1))
        assertEquals("/p/a", SpeechModels.projectorFor("Qwen3-ASR-0.6B-Q8_0.gguf", projectors)?.path)
        assertNull(SpeechModels.projectorFor("Missing.gguf", projectors))
    }

    @Test
    fun theCatalogEntryHasBothFilesFromTheSameRepository() {
        val entry = SpeechModels.catalog.single()
        assertTrue(RemoteCatalog.allowedUrl(entry.url))
        assertTrue(RemoteCatalog.allowedUrl(entry.projectorUrl!!))
        assertEquals("mmproj-${entry.fileName}", entry.projectorFileName)
        assertTrue(SpeechModels.isSpeechFile(entry.fileName))
    }
}
