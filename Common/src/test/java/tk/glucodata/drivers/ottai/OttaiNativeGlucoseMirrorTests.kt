package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiNativeGlucoseMirrorTests {

    @Test
    fun historyReadingsUseNativeUnitsAndDoNotWakeNightscout() {
        val timestampsSec = mutableListOf<Long>()
        val nativeGlucose = mutableListOf<Float>()
        val temperatures = mutableListOf<Float>()
        val sensorIds = mutableListOf<String>()
        val wakes = mutableListOf<Pair<String, Long>>()
        val mirror = OttaiNativeGlucoseMirror(
            writeNative = { timestampSec, glucose, temperature, sensorId ->
                timestampsSec += timestampSec
                nativeGlucose += glucose
                temperatures += temperature
                sensorIds += sensorId
                true
            },
            wakeNightscout = { source, timestampMs -> wakes += source to timestampMs },
        )

        val stored = mirror.mirror(
            sensorId = "AABBCCDDEEFF",
            timestampsMs = longArrayOf(120_000L, 180_000L),
            glucoseMgdl = floatArrayOf(108f, 126f),
            temperaturesC = floatArrayOf(31.5f, 32f),
            live = false,
        )

        assertEquals(2, stored)
        assertEquals(listOf(120L, 180L), timestampsSec)
        assertEquals(listOf(10.8f, 12.6f), nativeGlucose)
        assertEquals(listOf(31.5f, 32f), temperatures)
        assertEquals(listOf("AABBCCDDEEFF", "AABBCCDDEEFF"), sensorIds)
        assertTrue(wakes.isEmpty())
    }

    @Test
    fun successfulLiveWriteWakesNightscoutOnceAtNewestStoredTimestamp() {
        val wakes = mutableListOf<Pair<String, Long>>()
        val mirror = OttaiNativeGlucoseMirror(
            writeNative = { timestampSec, _, _, _ -> timestampSec >= 180L },
            wakeNightscout = { source, timestampMs -> wakes += source to timestampMs },
        )

        val stored = mirror.mirror(
            sensorId = "AABBCCDDEEFF",
            timestampsMs = longArrayOf(120_000L, 180_000L),
            glucoseMgdl = floatArrayOf(108f, 126f),
            temperaturesC = floatArrayOf(31.5f, 32f),
            live = true,
        )

        assertEquals(1, stored)
        assertEquals(listOf("ottai" to 180_000L), wakes)
    }

    @Test
    fun failedLiveWriteDoesNotWakeNightscout() {
        val wakes = mutableListOf<Pair<String, Long>>()
        val mirror = OttaiNativeGlucoseMirror(
            writeNative = { _, _, _, _ -> false },
            wakeNightscout = { source, timestampMs -> wakes += source to timestampMs },
        )

        val stored = mirror.mirror(
            sensorId = "AABBCCDDEEFF",
            timestampsMs = longArrayOf(180_000L),
            glucoseMgdl = floatArrayOf(126f),
            temperaturesC = floatArrayOf(32f),
            live = true,
        )

        assertEquals(0, stored)
        assertTrue(wakes.isEmpty())
    }
}
