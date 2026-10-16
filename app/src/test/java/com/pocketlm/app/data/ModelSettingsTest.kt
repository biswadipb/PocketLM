package com.pocketlm.app.data

import com.arm.aichat.GenerationParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSettingsTest {

    @Test
    fun everythingSurvivesSavingAndLoading() {
        val all = mapOf(
            "small.gguf" to ModelSettings(
                GenerationParams(contextSize = 2048, threads = 3, temperature = 0.35f, topP = 0.8f, topK = 20, minP = 0.1f, repeatPenalty = 1.15f, seed = 42),
                presetId = "coder",
                chatTemplate = "chatml",
                draftFile = "tiny.gguf",
            ),
            "other.gguf" to ModelSettings(params = null, chatTemplate = "llama3"),
        )
        assertEquals(all, ModelSettingsStore.fromJson(ModelSettingsStore.toJson(all)))
    }

    @Test
    fun aModelWithoutItsOwnSliderValuesFollowsTheApp() {
        val loaded = ModelSettingsStore.fromJson(ModelSettingsStore.toJson(mapOf("m.gguf" to ModelSettings(presetId = "tutor"))))
        assertNull(loaded.getValue("m.gguf").params)
        assertEquals("tutor", loaded.getValue("m.gguf").presetId)
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        val loaded = ModelSettingsStore.fromJson("""{"m.gguf":{"ctx":8192}}""").getValue("m.gguf")
        assertEquals(8192, loaded.params!!.contextSize)
        assertEquals(GenerationParams().temperature, loaded.params!!.temperature)
        assertEquals("", loaded.chatTemplate)
        assertNull(loaded.draftFile)
    }

    @Test
    fun brokenDataGivesNothing() {
        assertTrue(ModelSettingsStore.fromJson(null).isEmpty())
        assertTrue(ModelSettingsStore.fromJson("[[").isEmpty())
    }
}
