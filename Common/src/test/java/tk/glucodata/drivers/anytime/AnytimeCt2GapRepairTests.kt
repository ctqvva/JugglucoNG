package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gap repair for CT-14, which pushes one record per cadence slot.
 *
 * The 2026-10-27 trace: the app process sat frozen for 30 minutes, the
 * transmitter kept producing, and the next live id arrived as 1956 while the
 * newest stored one was 1946. Reconnect backfill asked from 1958 onwards and the
 * transmitter answered "caught up" — ids 1947..1955 were never requested.
 */
class AnytimeCt2GapRepairTests {

    private val threeHourCap = 60

    @Test
    fun theJumpAfterAFreezeBecomesTheMissingRange() {
        val gap = anytimeReconnectGap(
            highestKnownId = 1946,
            liveId = 1956,
            maxRecords = threeHourCap,
        )

        assertEquals(1947, gap?.fromId)
        assertEquals(1956, gap?.stopBeforeId)
        assertEquals(9, gap?.count)
    }

    @Test
    fun theExpectedNextIdIsNotAGap() {
        assertNull(
            anytimeReconnectGap(
                highestKnownId = 1955,
                liveId = 1956,
                maxRecords = threeHourCap,
            )
        )
    }

    @Test
    fun aRepeatedOrOlderLiveIdIsNotAGap() {
        assertNull(anytimeReconnectGap(highestKnownId = 1956, liveId = 1956, maxRecords = threeHourCap))
        assertNull(anytimeReconnectGap(highestKnownId = 1956, liveId = 1950, maxRecords = threeHourCap))
    }

    @Test
    fun nothingIsKnownBeforeTheFirstLiveId() {
        assertNull(anytimeReconnectGap(highestKnownId = -1, liveId = 1956, maxRecords = threeHourCap))
        assertNull(anytimeReconnectGap(highestKnownId = 1946, liveId = 0, maxRecords = threeHourCap))
    }

    @Test
    fun aVeryLongOutageRepairsTheNewestTailOnly() {
        // Two days of silence: the transmitter has long dropped that history, so
        // asking for it would cost a GATT session per range for nothing.
        val gap = anytimeReconnectGap(
            highestKnownId = 100,
            liveId = 1100,
            maxRecords = threeHourCap,
        )

        assertEquals(1040, gap?.fromId)
        assertEquals(1100, gap?.stopBeforeId)
        assertEquals(threeHourCap, gap?.count)
    }

    @Test
    fun ct5KeepsTheSameAnswerThroughItsOwnSpelling() {
        val viaCt5 = ct5ReconnectGap(highestKnownId = 288, liveId = 291, maxRecords = 480)
        val viaFamilyNeutral = anytimeReconnectGap(highestKnownId = 288, liveId = 291, maxRecords = 480)

        assertEquals(viaFamilyNeutral, viaCt5)
        assertEquals(289, viaCt5?.fromId)
        assertEquals(291, viaCt5?.stopBeforeId)
    }

    @Test
    fun aJumpDuringTheRepairKeepsTheIdsThePassNeverAskedFor() {
        // 100 -> 103 starts a pass that stops before 103. A 103 -> 105 jump widens
        // the envelope mid-pass; 104 was never requested, so finishing the pass
        // must leave it outstanding instead of clearing the whole envelope.
        val remainder = gapRemainder(
            runStopBeforeId = 103,
            reachedId = 103,
            pendingFromId = 100,
            pendingStopBeforeId = 105,
        )

        assertEquals(AnytimeIdRange(103, 105), remainder)
    }

    @Test
    fun aPassThatCoveredEverythingOwesNothing() {
        assertNull(
            gapRemainder(
                runStopBeforeId = 1956,
                reachedId = 1956,
                pendingFromId = 1947,
                pendingStopBeforeId = 1956,
            )
        )
    }

    @Test
    fun anUnservablePassStillOwesTheWiderJump() {
        // The transmitter stopped answering inside the range, but the ids beyond it
        // came from a live push, so they exist and are still worth asking for.
        val remainder = gapRemainder(
            runStopBeforeId = 103,
            reachedId = 101,
            pendingFromId = 100,
            pendingStopBeforeId = 105,
        )

        assertEquals(AnytimeIdRange(103, 105), remainder)
    }

    @Test
    fun aRunningPassCoversTheGapOnlyWhenItStartsEarlyEnoughAndReachesFarEnough() {
        // Older-prefix pass over 0..∞ contains the live gap 100..103.
        assertTrue(backfillCoversRange(nextRequestId = 0, stopBeforeId = Int.MAX_VALUE, fromId = 100, gapStopBeforeId = 103))
        // Forward pass from 103 asks for the gap end, not the gap.
        assertFalse(backfillCoversRange(nextRequestId = 103, stopBeforeId = Int.MAX_VALUE, fromId = 100, gapStopBeforeId = 103))
        // A finite pass that ends inside the gap cannot cover it.
        assertFalse(backfillCoversRange(nextRequestId = 90, stopBeforeId = 102, fromId = 100, gapStopBeforeId = 103))
    }
}
