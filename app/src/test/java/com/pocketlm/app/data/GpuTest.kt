package com.pocketlm.app.data

import com.arm.aichat.GenerationParams
import com.arm.aichat.GpuDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuTest {

    private class Memory : GpuGuard.Flags {
        val values = HashMap<String, Boolean>()
        override fun get(key: String) = values[key] ?: false
        override fun set(key: String, value: Boolean) {
            values[key] = value
        }
    }

    @Test
    fun aSavedIdFindsItsChoiceAndAnythingElseMeansTheCpu() {
        assertEquals(GpuChoice.Vulkan, GpuChoice.fromId("vulkan"))
        assertEquals(GpuChoice.OpenCl, GpuChoice.fromId("opencl"))
        assertEquals(GpuChoice.Auto, GpuChoice.fromId("auto"))
        assertEquals(GpuChoice.Off, GpuChoice.fromId(""))
        assertEquals(GpuChoice.Off, GpuChoice.fromId(null))
        assertEquals(GpuChoice.Off, GpuChoice.fromId("cuda"))
    }

    @Test
    fun theGpuIsOffByDefault() {
        assertEquals("", GenerationParams().gpuBackend)
        assertEquals("", ModelSettings().gpu)
    }

    @Test
    fun aSuccessfulAttemptLeavesNothingBehind() {
        val guard = GpuGuard(Memory())
        guard.attemptStarting()
        guard.attemptFinished()
        assertFalse(guard.recoverFromCrash())
        assertFalse(guard.disabled)
    }

    @Test
    fun anAttemptThatEndedTheAppSwitchesTheGpuOffNextTime() {
        val flags = Memory()
        GpuGuard(flags).attemptStarting() // the app dies here

        val afterRestart = GpuGuard(flags)
        assertTrue(afterRestart.recoverFromCrash())
        assertTrue(afterRestart.disabled)
        // It is reported once, and stays off across further restarts.
        assertFalse(GpuGuard(flags).recoverFromCrash())
        assertTrue(GpuGuard(flags).disabled)
    }

    @Test
    fun theUserCanAllowTheGpuAgain() {
        val flags = Memory()
        GpuGuard(flags).attemptStarting()
        val guard = GpuGuard(flags)
        guard.recoverFromCrash()
        guard.allowAgain()
        assertFalse(guard.disabled)
        assertFalse(GpuGuard(flags).recoverFromCrash())
    }

    @Test
    fun foundGpusAreReadFromTheNativeLines() {
        val devices = GpuDevice.parse("Vulkan\tVulkan0\tMali-G615 MC2\t8000000000\nOpenCL\tGPUOpenCL\tQualcomm Adreno(TM) 750\t0\n")
        assertEquals(2, devices.size)
        assertEquals("Vulkan", devices[0].backend)
        assertEquals("Mali-G615 MC2", devices[0].description)
        assertEquals(8_000_000_000L, devices[0].totalBytes)
        assertTrue(GpuDevice.parse("").isEmpty())
        assertTrue(GpuDevice.parse("garbage line\n").isEmpty())
    }

    @Test
    fun aGpuIsDescribedWithItsBackendNameAndMemory() {
        assertEquals("Vulkan: Mali-G615 MC2 (7.5 GB)", GpuDevice("Vulkan", "Vulkan0", "Mali-G615 MC2", 8_000_000_000L).summary())
        assertEquals("OpenCL: Adreno", GpuDevice("OpenCL", "x", "Adreno", 0).summary())
        assertEquals("Vulkan: Vulkan0", GpuDevice("Vulkan", "Vulkan0", "", 0).summary())
    }

    @Test
    fun theGpuChoiceIsKeptWithTheModelsSettings() {
        val all = mapOf("m.gguf" to ModelSettings(gpu = "vulkan"), "n.gguf" to ModelSettings(gpu = "opencl", chatTemplate = "chatml"))
        val loaded = ModelSettingsStore.fromJson(ModelSettingsStore.toJson(all))
        assertEquals("vulkan", loaded.getValue("m.gguf").gpu)
        assertEquals("opencl", loaded.getValue("n.gguf").gpu)
        assertEquals("", ModelSettingsStore.fromJson("""{"o.gguf":{"preset":null}}""").getValue("o.gguf").gpu)
    }
}
