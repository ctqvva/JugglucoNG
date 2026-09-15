package tk.glucodata.drivers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualSensorNativeMirrorTests {

    private val serial = "API-1"
    private val now = 1_760_000_000L

    private data class Batch(
        val timestampsSec: LongArray,
        val glucose: FloatArray,
        val raws: FloatArray,
        val sensorSerial: String,
    )

    /** A stand-in for the native shell: one start second, and whatever was written. */
    private class FakeNative(var startSeconds: Long = 0L) {
        val batches = mutableListOf<Batch>()
        val opened = mutableListOf<Pair<Long, Int>>()
        val rebased = mutableListOf<Long>()
    }

    private fun mirrorOver(native: FakeNative) = VirtualSensorNativeMirror(
        readShellStartSeconds = { native.startSeconds },
        openShell = { _, startSeconds, minimumRecords ->
            native.opened += startSeconds to minimumRecords
            // Native only ever lowers an existing start.
            if (native.startSeconds == 0L || startSeconds < native.startSeconds) {
                native.startSeconds = startSeconds
            }
        },
        rebaseShell = { _, startSeconds ->
            native.rebased += startSeconds
            native.startSeconds = startSeconds
        },
        writeBatch = { timestampsSec, glucose, raws, sensorSerial ->
            native.batches += Batch(timestampsSec, glucose, raws, sensorSerial)
            timestampsSec.size
        },
    )

    private fun reading(atSeconds: Long, mgdl: Float, rawMgdl: Float = Float.NaN) =
        VirtualGlucoseSensorBridge.Reading(
            timestampMs = atSeconds * 1000L,
            glucoseMgdl = mgdl,
            rawMgdl = rawMgdl,
        )

    @Test
    fun firstImportOpensAShellAndWritesEveryPointInNativeUnits() {
        val native = FakeNative()

        val stored = mirrorOver(native).mirror(
            serial,
            listOf(reading(now - 300L, 120f), reading(now, 55f)),
            "API source",
        )

        assertEquals(2, stored)
        assertEquals(1, native.opened.size)
        assertEquals(VirtualSensorNativeMirror.WINDOW_MINUTES, native.opened.single().second)

        val batch = native.batches.single()
        assertEquals(serial, batch.sensorSerial)
        assertArrayEquals(longArrayOf(now - 300L, now), batch.timestampsSec)
        // g.cpp storeGlucoseStreamSample(): mgVal = glucose * 10.
        assertEquals(12.0f, batch.glucose[0], 0.0001f)
        assertEquals(5.5f, batch.glucose[1], 0.0001f)
    }

    @Test
    fun aSecondPollReusesTheWindowInsteadOfReopeningIt() {
        val native = FakeNative()
        val mirror = mirrorOver(native)

        mirror.mirror(serial, listOf(reading(now, 120f)), "API source")
        mirror.mirror(serial, listOf(reading(now + 300L, 122f)), "API source")

        assertEquals(1, native.opened.size)
        assertTrue(native.rebased.isEmpty())
        assertEquals(2, native.batches.size)
    }

    @Test
    fun readingsUnderTheWindowAreDroppedRatherThanFoldedOntoSlotZero() {
        // Native does not refuse a reading below the shell start; lifeCount stays
        // 0 and the stale point overwrites whatever slot 0 holds.
        val native = FakeNative(startSeconds = now)

        val stored = mirrorOver(native).mirror(
            serial,
            listOf(reading(now - 3600L, 90f), reading(now, 120f)),
            "API source",
        )

        assertEquals(1, stored)
        assertArrayEquals(longArrayOf(now), native.batches.single().timestampsSec)
    }

    @Test
    fun nothingIsWrittenWhenEveryPointIsBelowTheWindow() {
        val native = FakeNative(startSeconds = now)

        val stored = mirrorOver(native).mirror(serial, listOf(reading(now - 3600L, 90f)), "API source")

        assertEquals(0, stored)
        assertTrue(native.batches.isEmpty())
    }

    @Test
    fun aFollowerThatOutlivesItsWindowIsRebasedAndKeepsWriting() {
        val native = FakeNative(startSeconds = now)
        val outgrown = VirtualSensorNativeMirror.windowEndSeconds(now) + 60L

        val stored = mirrorOver(native).mirror(serial, listOf(reading(outgrown, 120f)), "API source")

        assertEquals(1, stored)
        assertEquals(1, native.rebased.size)
        assertArrayEquals(longArrayOf(outgrown), native.batches.single().timestampsSec)
    }

    @Test
    fun aRebaseLeavesRoomToRunOnRatherThanWipingAgainNextReading() {
        val native = FakeNative(startSeconds = now)
        val outgrown = VirtualSensorNativeMirror.windowEndSeconds(now) + 60L
        val mirror = mirrorOver(native)

        mirror.mirror(serial, listOf(reading(outgrown, 120f)), "API source")
        mirror.mirror(serial, listOf(reading(outgrown + 13L * 24L * 60L * 60L, 121f)), "API source")

        assertEquals(1, native.rebased.size)
        assertEquals(2, native.batches.size)
    }

    @Test
    fun aRebaseKeepsTheNextBackfillPageWritable() {
        val native = FakeNative(startSeconds = now)
        val outgrown = VirtualSensorNativeMirror.windowEndSeconds(now) + 60L

        val stored = mirrorOver(native).mirror(
            serial,
            listOf(reading(outgrown - 3L * 60L * 60L, 118f), reading(outgrown, 120f)),
            "API source",
        )

        assertEquals(2, stored)
    }

    @Test
    fun aFirstImportWiderThanTheWindowGivesUpItsOldestEndNotItsNewest() {
        val native = FakeNative()
        val oldest = now - 30L * 24L * 60L * 60L

        val stored = mirrorOver(native).mirror(
            serial,
            listOf(reading(oldest, 90f), reading(now, 120f)),
            "API source",
        )

        assertEquals(1, stored)
        assertArrayEquals(longArrayOf(now), native.batches.single().timestampsSec)
    }

    @Test
    fun rawIsPassedThroughAsPlainMgdlAndAbsentRawWritesZero() {
        val native = FakeNative()

        mirrorOver(native).mirror(
            serial,
            listOf(reading(now - 60L, 120f, rawMgdl = 118f), reading(now, 122f)),
            "API source",
        )

        val batch = native.batches.single()
        assertEquals(118f, batch.raws[0], 0.0001f)
        // 0 tells native to leave whatever raw the slot already had alone.
        assertEquals(0f, batch.raws[1], 0.0001f)
    }

    @Test
    fun unusableReadingsNeverReachNative() {
        val native = FakeNative()

        val stored = mirrorOver(native).mirror(
            serial,
            listOf(reading(now, 0f), reading(now + 60L, Float.NaN), reading(0L, 120f)),
            "API source",
        )

        assertEquals(0, stored)
        assertTrue(native.opened.isEmpty())
        assertTrue(native.batches.isEmpty())
    }

    @Test
    fun windowEndIsExclusive() {
        val end = VirtualSensorNativeMirror.windowEndSeconds(now)

        assertTrue(VirtualSensorNativeMirror.isWritable(now, end - 60L))
        assertFalse(VirtualSensorNativeMirror.isWritable(now, end))
        assertFalse(VirtualSensorNativeMirror.needsRebase(now, end - 60L))
        assertTrue(VirtualSensorNativeMirror.needsRebase(now, end))
    }
}
