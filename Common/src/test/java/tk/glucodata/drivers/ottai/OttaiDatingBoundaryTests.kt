package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exact edges of the dating path's tuning handles. The other tests sit well inside or well
 * outside each window, so widening a bound by one step, or turning `<` into `<=`, would pass them.
 */
class OttaiDatingBoundaryTests {

    private val minute = 60_000L
    private val arrival = 1_700_000_405_000L

    /**
     * The monitor-live window (CURRENT_SAMPLE_FRESH_MS, 2 min) is narrower than isFreshLiveSample's
     * (3 min): a monitor time can be fresh enough to publish and still not anchor the stream. This
     * window is what let the 2026-09-22 record 0 anchor a minute early.
     */
    @Test
    fun aMonitorTimeAnchorsOnlyWithinTwoMinutesOfItsArrival() {
        fun startFrom(monitorMs: Long) = OttaiBleManager.monitorLiveStartMs(arrival, monitorMs, 5, 0L)
        val early = arrival - 2L * minute
        assertEquals(early / minute * minute - 5L * minute, startFrom(early))
        assertEquals(0L, startFrom(early - 1L))
        assertTrue(startFrom(arrival + 2L * minute) > 0L)
        assertEquals(0L, startFrom(arrival + 2L * minute + 1L))
        assertEquals(0L, startFrom(0L))

        val between = arrival - 150_000L
        assertTrue(OttaiBleManager.isFreshLiveSample(arrival, between))
        assertEquals(0L, startFrom(between))
    }

    @Test
    fun aLiveSampleIsFreshDownToThreeMinutesBeforeItsArrival() {
        assertTrue(OttaiBleManager.isFreshLiveSample(arrival, arrival - 3L * minute))
        assertFalse(OttaiBleManager.isFreshLiveSample(arrival, arrival - 3L * minute - 1L))
    }

    @Test
    fun twoStartsAgreeUpToTwoMinutesApart() {
        val start = arrival - 1_600L * minute
        // Two minutes, as the name says: a wider bound lets a one-minute-off claim through twice.
        assertEquals(2L * minute, OttaiBleManager.CONFIRMED_START_AGREEMENT_MS)
        val tolerance = OttaiBleManager.CONFIRMED_START_AGREEMENT_MS
        assertTrue(OttaiBleManager.startsCorroborate(start, 1_600, start + tolerance, 1_601))
        assertTrue(OttaiBleManager.startsCorroborate(start, 1_600, start - tolerance, 1_601))
        assertFalse(OttaiBleManager.startsCorroborate(start, 1_600, start + tolerance + 1L, 1_601))
        assertFalse(OttaiBleManager.startsCorroborate(start, 1_600, start - tolerance - 1L, 1_601))
    }

    /** CONFIRMED_CLAIM_MAX_GAP_RECORDS: a claim vouches for the 15 records above it, not the 16th. */
    @Test
    fun aClaimVouchesForFifteenRecordsAboveIt() {
        val held = arrival - 1_600L * minute
        assertTrue(OttaiBleManager.datesLiveByArrival(true, 1_601, 1_590, held, 1_600, held))
        assertTrue(OttaiBleManager.datesLiveByArrival(true, 1_615, 1_590, held, 1_600, held))
        assertFalse(OttaiBleManager.datesLiveByArrival(true, 1_616, 1_590, held, 1_600, held))
    }

    /** WARMUP_SUPPRESS_MS is ten records: record 9 is still settling, record 10 is not. */
    @Test
    fun theWarmupCounterEndsAtRecordTen() {
        val anchor = arrival - 60L * minute
        val datedPastTheWindow = arrival
        assertTrue(OttaiBleManager.warmupSuppresses(anchor, datedPastTheWindow, dataNo = 9))
        assertFalse(OttaiBleManager.warmupSuppresses(anchor, datedPastTheWindow, dataNo = 10))
        // And by date: up to, not including, ten minutes after the anchor.
        assertTrue(OttaiConstants.isWithinWarmup(anchor, anchor + OttaiConstants.WARMUP_SUPPRESS_MS - 1L))
        assertFalse(OttaiConstants.isWithinWarmup(anchor, anchor + OttaiConstants.WARMUP_SUPPRESS_MS))
        assertEquals(10L * minute, OttaiConstants.WARMUP_SUPPRESS_MS)
    }
}
