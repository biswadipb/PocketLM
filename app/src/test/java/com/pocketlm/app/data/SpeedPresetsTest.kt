package com.pocketlm.app.data

import com.arm.aichat.GenerationParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedPresetsTest {

    @Test
    fun applyingAPresetSetsOnlyItsOwnSettings() {
        val before = GenerationParams(temperature = 0.3f, topP = 0.8f, topK = 20, seed = 7, chatTemplate = "chatml", gpuBackend = "vulkan")
        val after = SpeedPresets.apply(before, SpeedPreset.BatterySaver)
        assertEquals(2, after.threads)
        assertEquals(2048, after.contextSize)
        assertEquals(8, after.cacheBits)
        assertEquals(before.copy(threads = 2, contextSize = 2048, cacheBits = 8), after)
    }

    @Test
    fun theMatchingPresetIsFoundAgain() {
        for (preset in SpeedPreset.entries) {
            val params = SpeedPresets.apply(GenerationParams(), preset)
            assertEquals(preset, SpeedPresets.detect(params, SpeedPresets.maxReplyFor(preset)))
        }
    }

    @Test
    fun changingAnythingMakesItCustom() {
        val balanced = SpeedPresets.apply(GenerationParams(), SpeedPreset.Balanced)
        assertEquals(SpeedPreset.Balanced, SpeedPresets.detect(balanced, 1024))
        assertNull(SpeedPresets.detect(balanced.copy(threads = 3), 1024))
        assertNull(SpeedPresets.detect(balanced, 1536))
        assertNull(SpeedPresets.detect(balanced.copy(contextSize = 16384), 1024))
    }

    @Test
    fun theSettingsThatAreNotPartOfAPresetDoNotAffectDetection() {
        val params = SpeedPresets.apply(GenerationParams(temperature = 1.4f, seed = 99), SpeedPreset.Fast)
        assertEquals(SpeedPreset.Fast, SpeedPresets.detect(params, 1024))
    }

    @Test
    fun everyPresetIsDifferent() {
        val all = SpeedPreset.entries.map { SpeedPresets.values(it) }
        assertEquals(all.size, all.toSet().size)
        assertNotEquals(SpeedPresets.values(SpeedPreset.Balanced), SpeedPresets.values(SpeedPreset.Fast))
    }

    @Test
    fun theBalancedPresetIsWhatANewInstallHas() {
        val defaults = GenerationParams()
        assertEquals(SpeedPreset.Balanced, SpeedPresets.detect(defaults, 1024))
    }
}
