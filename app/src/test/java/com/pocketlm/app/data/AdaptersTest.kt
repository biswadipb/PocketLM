package com.pocketlm.app.data

import com.arm.aichat.GenerationParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptersTest {

    @Test
    fun anAdapterForTheSameKindOfModelIsAccepted() {
        assertNull(LoraCheck.problem("adapter", "qwen2", "qwen2"))
        assertNull(LoraCheck.problem("adapter", "Qwen2", "qwen2"))
    }

    @Test
    fun anAdapterForADifferentKindOfModelIsRefusedWithTheNames() {
        val problem = LoraCheck.problem("adapter", "llama", "qwen2")!!
        assertTrue(problem, problem.contains("llama") && problem.contains("qwen2"))
    }

    @Test
    fun aWholeModelIsNotAnAdapter() {
        assertTrue(LoraCheck.problem("model", "llama", "llama")!!.contains("whole model"))
    }

    @Test
    fun unknownDetailsAreGivenTheBenefitOfTheDoubt() {
        assertNull(LoraCheck.problem(null, null, "llama"))
        assertNull(LoraCheck.problem("adapter", "llama", null))
        assertNull(LoraCheck.problem(null, "llama", "llama"))
    }

    @Test
    fun fileNamesAreMadeSafeAndEndInGguf() {
        assertEquals("style.gguf", LoraCheck.safeName("style"))
        assertEquals("style-v2.gguf", LoraCheck.safeName("/storage/emulated/0/Download/style-v2.gguf"))
        assertEquals("b.gguf", LoraCheck.safeName("a/b"))
        assertNull(LoraCheck.safeName(""))
        assertNull(LoraCheck.safeName("   "))
        assertEquals("my_lora_.gguf", LoraCheck.safeName("my\$lora!"))
    }

    @Test
    fun theSignatureChangesWhenAnAdapterOrItsStrengthChanges() {
        val a = LoraAdapterInfo("a.gguf", 100, 5, 1f)
        val base = LoraCheck.signature(listOf(a))
        assertNotEquals(base, LoraCheck.signature(listOf(a.copy(scale = 0.5f))))
        assertNotEquals(base, LoraCheck.signature(listOf(a.copy(size = 101))))
        assertNotEquals(base, LoraCheck.signature(listOf(a.copy(modified = 6))))
        assertNotEquals(base, LoraCheck.signature(emptyList()))
        // The order they were added in does not matter.
        val b = LoraAdapterInfo("b.gguf", 1, 1, 1f)
        assertEquals(LoraCheck.signature(listOf(a, b)), LoraCheck.signature(listOf(b, a)))
    }

    private val turns = listOf(Turn(true, "hi"), Turn(false, "hello"))

    @Test
    fun aSavedChatStateIsNotReusedWithOtherAdapters() {
        val plain = ChatState.tag("m", 4096, "", "sys", turns)
        val withLora = ChatState.tag("m", 4096, "", "sys", turns, LoraCheck.signature(listOf(LoraAdapterInfo("a.gguf", 1, 1, 1f))))
        val otherLora = ChatState.tag("m", 4096, "", "sys", turns, LoraCheck.signature(listOf(LoraAdapterInfo("a.gguf", 1, 1, 0.5f))))
        assertNotEquals(plain, withLora)
        assertNotEquals(withLora, otherLora)
    }

    @Test
    fun statesSavedBeforeAdaptersExistedStillApply() {
        // No adapters leaves the label exactly as it was, so nothing saved earlier is thrown away.
        assertEquals(ChatState.tag("m", 4096, "", "sys", turns), ChatState.tag("m", 4096, "", "sys", turns, ""))
        assertFalse(ChatState.tag("m", 4096, "", "sys", turns).contains("lora"))
    }

    @Test
    fun adaptersSurviveBeingSavedWithAModelsSettings() {
        val settings = mapOf(
            "model.gguf" to ModelSettings(
                params = GenerationParams(contextSize = 2048),
                adapters = listOf(AdapterSetting("style.gguf", 0.75f), AdapterSetting("lang.gguf", 1f)),
            ),
            "plain.gguf" to ModelSettings(chatTemplate = "chatml"),
        )
        val back = ModelSettingsStore.fromJson(ModelSettingsStore.toJson(settings))
        assertEquals(settings.getValue("model.gguf").adapters, back.getValue("model.gguf").adapters)
        assertTrue(back.getValue("plain.gguf").adapters.isEmpty())
        assertFalse(ModelSettingsStore.toJson(mapOf("plain.gguf" to ModelSettings(chatTemplate = "chatml"))).contains("loras"))
    }

    @Test
    fun savedSettingsWithOddAdapterEntriesAreCleanedOnLoad() {
        val json = """{"m.gguf":{"preset":null,"template":"","draft":null,"loras":[
            {"file":"ok.gguf","scale":9},{"file":"../escape.gguf","scale":1},{"file":"","scale":1},{"scale":1},{"file":"neg.gguf","scale":-3}]}}"""
        val loaded = ModelSettingsStore.fromJson(json).getValue("m.gguf").adapters
        assertEquals(listOf(AdapterSetting("ok.gguf", 2f), AdapterSetting("neg.gguf", 0f)), loaded)
    }
}
