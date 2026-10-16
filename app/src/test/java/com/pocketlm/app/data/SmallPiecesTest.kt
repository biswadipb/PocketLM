package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmallPiecesTest {

    @Test
    fun benchmarkSpeedsAreReadFromTheEngineTable() {
        val table = """
            | model | size | params | backend | test | t/s |
            | --- | --- | --- | --- | --- | --- |
            | qwen2 0.5B | 0.37GiB | 0.494B | CPU | pp 128 | 142 ± 3.1 |
            | qwen2 0.5B | 0.37GiB | 0.494B | CPU | tg 64 | 18.4 ± 0.2 |
        """.trimIndent()
        assertEquals(142.0 to 18.4, BenchResult.parseSpeeds(table))
    }

    @Test
    fun benchmarkOutputWithoutATableGivesNoSpeeds() {
        assertEquals(null to null, BenchResult.parseSpeeds("Fail to init_context! Bench aborted."))
    }

    @Test
    fun benchmarkResultsSurviveJson() {
        val results = listOf(BenchResult("a.gguf", 100.5, null, 42L))
        assertEquals(results, BenchResult.fromJson(BenchResult.toJson(results)))
    }

    @Test
    fun noWarningWhenEverythingIsFine() {
        assertNull(DeviceStatus(batteryPercent = 80, charging = false, thermal = 1).warning)
    }

    @Test
    fun heatWarningsGrowWithSeverity() {
        val warm = DeviceStatus(thermal = 2).warning
        val hot = DeviceStatus(thermal = 3).warning
        assertTrue(warm!!.contains("warm"))
        assertTrue(hot!!.contains("very hot"))
    }

    @Test
    fun lowBatteryOnlyWarnsWhenNotCharging() {
        assertTrue(DeviceStatus(batteryPercent = 10, charging = false).warning!!.contains("10%"))
        assertNull(DeviceStatus(batteryPercent = 10, charging = true).warning)
    }

    @Test
    fun unusedFilesAreTheOnesNothingRefersTo() {
        assertEquals(listOf("b", "d"), StorageManager.unusedNames(listOf("a", "b", "c", "d"), setOf("a", "c")))
    }
}
