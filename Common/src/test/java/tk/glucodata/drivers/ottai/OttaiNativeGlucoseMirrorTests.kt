package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiNativeGlucoseMirrorTests {

    @Test
    fun unanchoredHistoryIsDeferredThenReplayedFromDataNo() {
        val prepared = mutableListOf<Pair<String, Long>>()
        val timestampsSec = mutableListOf<Long>()
        val nativeGlucose = mutableListOf<Float>()
        val temperatures = mutableListOf<Float>()
        val sensorIds = mutableListOf<String>()
        val rewinds = mutableListOf<String>()
        val wakes = mutableListOf<Pair<String, Long>>()
        val mirror = OttaiNativeGlucoseMirror(
            prepareNative = { sensorId, startMs ->
                prepared += sensorId to startMs
                true
            },
            writeNative = { timestampSec, glucose, temperature, sensorId ->
                timestampsSec += timestampSec
                nativeGlucose += glucose
                temperatures += temperature
                sensorIds += sensorId
                true
            },
            rewindNightscout = { sensorId ->
                rewinds += sensorId
                true
            },
            wakeNightscout = { source, timestampMs -> wakes += source to timestampMs },
        )

        val deferred = mirror.mirrorHistory(
            sensorId = "AABBCCDDEEFF",
            reliableStartMs = 0L,
            dataNos = intArrayOf(19_000, 19_001),
            glucoseMgdl = floatArrayOf(108f, 126f),
            temperaturesC = floatArrayOf(31.5f, 32f),
        )

        assertEquals(0, deferred)
        assertTrue(prepared.isEmpty())
        assertTrue(timestampsSec.isEmpty())
        assertTrue(rewinds.isEmpty())

        val stored = mirror.mirrorHistory(
            sensorId = "AABBCCDDEEFF",
            reliableStartMs = 1_700_000_000_000L,
            dataNos = intArrayOf(),
            glucoseMgdl = floatArrayOf(),
            temperaturesC = floatArrayOf(),
        )

        assertEquals(2, stored)
        assertEquals(listOf("AABBCCDDEEFF" to 1_700_000_000_000L), prepared)
        assertEquals(listOf(1_701_140_000L, 1_701_140_060L), timestampsSec)
        assertEquals(listOf(10.8f, 12.6f), nativeGlucose)
        assertEquals(listOf(31.5f, 32f), temperatures)
        assertEquals(listOf("AABBCCDDEEFF", "AABBCCDDEEFF"), sensorIds)
        assertEquals(listOf("AABBCCDDEEFF"), rewinds)
        assertTrue(wakes.isEmpty())
    }

    @Test
    fun preparationFailureKeepsHistoryPendingAndDoesNotWrite() {
        var preparationAllowed = false
        var writerCalls = 0
        val rewinds = mutableListOf<String>()
        val mirror = OttaiNativeGlucoseMirror(
            prepareNative = { _, _ -> preparationAllowed },
            writeNative = { _, _, _, _ ->
                writerCalls++
                true
            },
            rewindNightscout = { sensorId ->
                rewinds += sensorId
                true
            },
            wakeNightscout = { _, _ -> },
        )

        val blocked = mirror.mirrorHistory(
            sensorId = "AABBCCDDEEFF",
            reliableStartMs = 1_700_000_000_000L,
            dataNos = intArrayOf(100),
            glucoseMgdl = floatArrayOf(108f),
            temperaturesC = floatArrayOf(31.5f),
        )

        assertEquals(0, blocked)
        assertEquals(0, writerCalls)
        assertTrue(rewinds.isEmpty())

        preparationAllowed = true
        val stored = mirror.mirrorHistory(
            sensorId = "AABBCCDDEEFF",
            reliableStartMs = 1_700_000_000_000L,
            dataNos = intArrayOf(),
            glucoseMgdl = floatArrayOf(),
            temperaturesC = floatArrayOf(),
        )

        assertEquals(1, stored)
        assertEquals(1, writerCalls)
        assertEquals(listOf("AABBCCDDEEFF"), rewinds)
    }

    @Test
    fun successfulHistoryBatchRewindsOnceAndNeverUsesLiveWake() {
        val rewinds = mutableListOf<String>()
        val wakes = mutableListOf<Pair<String, Long>>()
        val mirror = OttaiNativeGlucoseMirror(
            prepareNative = { _, _ -> true },
            writeNative = { _, _, _, _ -> true },
            rewindNightscout = { sensorId ->
                rewinds += sensorId
                true
            },
            wakeNightscout = { source, timestampMs -> wakes += source to timestampMs },
        )

        val stored = mirror.mirrorHistory(
            sensorId = "AABBCCDDEEFF",
            reliableStartMs = 1_700_000_000_000L,
            dataNos = intArrayOf(100, 101, 102),
            glucoseMgdl = floatArrayOf(108f, 126f, 144f),
            temperaturesC = floatArrayOf(31.5f, 32f, 32.5f),
        )

        assertEquals(3, stored)
        assertEquals(listOf("AABBCCDDEEFF"), rewinds)
        assertTrue(wakes.isEmpty())
    }

    @Test
    fun failedHistoryWriteRemainsPendingForLaterReplay() {
        var writerAllowed = false
        var writerCalls = 0
        val rewinds = mutableListOf<String>()
        val mirror = OttaiNativeGlucoseMirror(
            prepareNative = { _, _ -> true },
            writeNative = { _, _, _, _ ->
                writerCalls++
                writerAllowed
            },
            rewindNightscout = { sensorId ->
                rewinds += sensorId
                true
            },
            wakeNightscout = { _, _ -> },
        )

        val failed = mirror.mirrorHistory(
            sensorId = "AABBCCDDEEFF",
            reliableStartMs = 1_700_000_000_000L,
            dataNos = intArrayOf(100),
            glucoseMgdl = floatArrayOf(108f),
            temperaturesC = floatArrayOf(31.5f),
        )

        assertEquals(0, failed)
        assertEquals(1, writerCalls)
        assertTrue(rewinds.isEmpty())

        writerAllowed = true
        val stored = mirror.mirrorHistory(
            sensorId = "AABBCCDDEEFF",
            reliableStartMs = 1_700_000_000_000L,
            dataNos = intArrayOf(),
            glucoseMgdl = floatArrayOf(),
            temperaturesC = floatArrayOf(),
        )

        assertEquals(1, stored)
        assertEquals(2, writerCalls)
        assertEquals(listOf("AABBCCDDEEFF"), rewinds)
    }

    @Test
    fun failedHistoryRewindBlocksLiveWriteAndWakeUntilRetrySucceeds() {
        var rewindAllowed = false
        val timestampsSec = mutableListOf<Long>()
        val wakes = mutableListOf<Pair<String, Long>>()
        val mirror = OttaiNativeGlucoseMirror(
            prepareNative = { _, _ -> true },
            writeNative = { timestampSec, _, _, _ ->
                timestampsSec += timestampSec
                true
            },
            rewindNightscout = { rewindAllowed },
            wakeNightscout = { source, timestampMs -> wakes += source to timestampMs },
        )

        mirror.mirrorHistory(
            sensorId = "AABBCCDDEEFF",
            reliableStartMs = 1_700_000_000_000L,
            dataNos = intArrayOf(100),
            glucoseMgdl = floatArrayOf(108f),
            temperaturesC = floatArrayOf(31.5f),
        )

        val blocked = mirror.mirrorLive(
            sensorId = "AABBCCDDEEFF",
            reliableStartMs = 1_700_000_000_000L,
            timestampsMs = longArrayOf(1_700_010_000_000L),
            glucoseMgdl = floatArrayOf(126f),
            temperaturesC = floatArrayOf(32f),
        )

        assertEquals(1, blocked)
        assertEquals(listOf(1_700_006_000L, 1_700_006_000L), timestampsSec)
        assertTrue(wakes.isEmpty())

        rewindAllowed = true
        val stored = mirror.mirrorLive(
            sensorId = "AABBCCDDEEFF",
            reliableStartMs = 1_700_000_000_000L,
            timestampsMs = longArrayOf(1_700_010_000_000L),
            glucoseMgdl = floatArrayOf(126f),
            temperaturesC = floatArrayOf(32f),
        )

        assertEquals(2, stored)
        assertEquals(
            listOf(1_700_006_000L, 1_700_006_000L, 1_700_006_000L, 1_700_010_000L),
            timestampsSec,
        )
        assertEquals(listOf("ottai" to 1_700_010_000_000L), wakes)
    }

    @Test
    fun successfulLiveWriteWakesNightscoutOnceAtNewestStoredTimestamp() {
        val wakes = mutableListOf<Pair<String, Long>>()
        val mirror = OttaiNativeGlucoseMirror(
            prepareNative = { _, _ -> true },
            writeNative = { timestampSec, _, _, _ -> timestampSec >= 180L },
            rewindNightscout = { true },
            wakeNightscout = { source, timestampMs -> wakes += source to timestampMs },
        )

        val stored = mirror.mirrorLive(
            sensorId = "AABBCCDDEEFF",
            reliableStartMs = 60_000L,
            timestampsMs = longArrayOf(120_000L, 180_000L),
            glucoseMgdl = floatArrayOf(108f, 126f),
            temperaturesC = floatArrayOf(31.5f, 32f),
        )

        assertEquals(1, stored)
        assertEquals(listOf("ottai" to 180_000L), wakes)
    }

    @Test
    fun failedLiveWriteDoesNotWakeNightscout() {
        val wakes = mutableListOf<Pair<String, Long>>()
        val mirror = OttaiNativeGlucoseMirror(
            prepareNative = { _, _ -> true },
            writeNative = { _, _, _, _ -> false },
            rewindNightscout = { true },
            wakeNightscout = { source, timestampMs -> wakes += source to timestampMs },
        )

        val stored = mirror.mirrorLive(
            sensorId = "AABBCCDDEEFF",
            reliableStartMs = 60_000L,
            timestampsMs = longArrayOf(180_000L),
            glucoseMgdl = floatArrayOf(126f),
            temperaturesC = floatArrayOf(32f),
        )

        assertEquals(0, stored)
        assertTrue(wakes.isEmpty())
    }
}
