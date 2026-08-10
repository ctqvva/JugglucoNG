package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiNativeGlucoseMirrorTests {

    @Test
    fun liveReadingUsesNativeUnitsAndWakesNightscoutAfterWrite() {
        var written: NativeWrite? = null
        val wakes = mutableListOf<Pair<String, Long>>()
        val mirror = OttaiNativeGlucoseMirror(
            writeNative = { timestampSec, glucose, temperature, sensorId ->
                written = NativeWrite(timestampSec, glucose, temperature, sensorId)
                true
            },
            wakeNightscout = { source, timestampMs -> wakes += source to timestampMs },
        )

        val stored = mirror.mirrorLive(
            sensorId = "AABBCCDDEEFF",
            timestampMs = 180_000L,
            glucoseMgdl = 126f,
            temperatureC = 32f,
        )

        assertTrue(stored)
        assertTrue(written == NativeWrite(180L, 12.6f, 32f, "AABBCCDDEEFF"))
        assertEquals(listOf("ottai" to 180_000L), wakes)
    }

    @Test
    fun failedLiveWriteDoesNotWakeNightscout() {
        val wakes = mutableListOf<Pair<String, Long>>()
        val mirror = OttaiNativeGlucoseMirror(
            writeNative = { _, _, _, _ -> false },
            wakeNightscout = { source, timestampMs -> wakes += source to timestampMs },
        )

        val stored = mirror.mirrorLive(
            sensorId = "AABBCCDDEEFF",
            timestampMs = 180_000L,
            glucoseMgdl = 126f,
            temperatureC = 32f,
        )

        assertTrue(!stored)
        assertTrue(wakes.isEmpty())
    }

    @Test
    fun historyBatchIsMirroredAndWakesNightscoutOncePerBatch() {
        val written = mutableListOf<NativeWrite>()
        val wakes = mutableListOf<Pair<String, Long>>()
        val mirror = OttaiNativeGlucoseMirror(
            writeNative = { timestampSec, glucose, temperature, sensorId ->
                written += NativeWrite(timestampSec, glucose, temperature, sensorId)
                true
            },
            wakeNightscout = { source, timestampMs -> wakes += source to timestampMs },
        )

        val stored = mirror.mirrorHistory(
            sensorId = "AABBCCDDEEFF",
            timestampsMs = longArrayOf(60_000L, 120_000L, 180_000L),
            glucoseMgdl = floatArrayOf(100f, 110f, 126f),
            temperaturesC = floatArrayOf(32f, 32.5f, 33f),
        )

        assertEquals(3, stored)
        assertEquals(listOf(60L, 120L, 180L), written.map { it.timestampSec })
        assertEquals(12.6f, written.last().glucose, 0.0001f)
        // One wake for the batch, anchored on the newest row: waking per row is what made an
        // earlier attempt at history mirroring resend continuously.
        assertEquals(listOf("ottai-history" to 180_000L), wakes)
    }

    @Test
    fun historyUsesOneNativeBatchWithNativeUnits() {
        var batch: NativeBatch? = null
        val wakes = mutableListOf<Pair<String, Long>>()
        val mirror = OttaiNativeGlucoseMirror(
            writeNative = { _, _, _, _ -> error("per-row writer must not run") },
            wakeNightscout = { source, timestampMs -> wakes += source to timestampMs },
            writeNativeBatch = { timestampsSec, glucoses, temperatures, sensorId ->
                batch = NativeBatch(timestampsSec, glucoses, temperatures, sensorId)
                timestampsSec.size
            },
        )

        val stored = mirror.mirrorHistory(
            sensorId = "AABBCCDDEEFF",
            timestampsMs = longArrayOf(60_000L, 120_000L, 180_000L),
            glucoseMgdl = floatArrayOf(100f, 110f, 126f),
            temperaturesC = floatArrayOf(32f, 32.5f, 33f),
        )

        assertEquals(3, stored)
        assertEquals(listOf(60L, 120L, 180L), batch!!.timestampsSec.toList())
        assertEquals(12.6f, batch!!.glucoses.last(), 0.0001f)
        assertEquals("AABBCCDDEEFF", batch!!.sensorId)
        assertEquals(listOf("ottai-history" to 180_000L), wakes)
    }

    @Test
    fun historyBatchThatStoresNothingDoesNotWakeNightscout() {
        val wakes = mutableListOf<Pair<String, Long>>()
        val mirror = OttaiNativeGlucoseMirror(
            writeNative = { _, _, _, _ -> false },
            wakeNightscout = { source, timestampMs -> wakes += source to timestampMs },
        )

        val stored = mirror.mirrorHistory(
            sensorId = "AABBCCDDEEFF",
            timestampsMs = longArrayOf(60_000L, 120_000L),
            glucoseMgdl = floatArrayOf(100f, 110f),
            temperaturesC = floatArrayOf(32f, 32f),
        )

        assertEquals(0, stored)
        assertTrue(wakes.isEmpty())
    }

    @Test
    fun historyBatchSkipsRowsWithoutATimestamp() {
        val written = mutableListOf<NativeWrite>()
        val mirror = OttaiNativeGlucoseMirror(
            writeNative = { timestampSec, glucose, temperature, sensorId ->
                written += NativeWrite(timestampSec, glucose, temperature, sensorId)
                true
            },
            wakeNightscout = { _, _ -> },
        )

        val stored = mirror.mirrorHistory(
            sensorId = "AABBCCDDEEFF",
            timestampsMs = longArrayOf(0L, 120_000L),
            glucoseMgdl = floatArrayOf(100f, 110f),
            temperaturesC = floatArrayOf(32f, 32f),
        )

        assertEquals(1, stored)
        assertEquals(listOf(120L), written.map { it.timestampSec })
    }

    private data class NativeWrite(
        val timestampSec: Long,
        val glucose: Float,
        val temperatureC: Float,
        val sensorId: String,
    )

    private data class NativeBatch(
        val timestampsSec: LongArray,
        val glucoses: FloatArray,
        val temperaturesC: FloatArray,
        val sensorId: String,
    )
}
