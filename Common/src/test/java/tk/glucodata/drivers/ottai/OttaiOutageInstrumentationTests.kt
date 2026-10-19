package tk.glucodata.drivers.ottai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three measurements the 2026-08-01 analysis could not make: when the sensor advertises again
 * after a drop, and how strong the link was while it lasted. Both are gates on how often the app
 * is allowed to spend radio on asking, and getting either gate wrong turns instrumentation into a
 * second RF problem — so the gates, and only the gates, are pinned here.
 */
class OttaiOutageInstrumentationTests {

    private val now = 1_785_605_570_000L

    @Test
    fun theFirstOutageIsProbedImmediately() {
        assertTrue(
            OttaiBleManager.shouldProbeOutageAdvertisement(
                alreadyProbedThisOutage = false,
                activationScanActive = false,
                managedScanActive = false,
                nowMs = now,
                lastProbeAtMs = 0L,
            ),
        )
    }

    /**
     * 88 drops in four hours: without a once-per-outage latch the probe would restart on every
     * disconnect callback of a flapping link and hit Android's 5-scans-per-30s throttle, which
     * parks the offending app's scans for half an hour — including the managed scan the driver
     * needs to find sensors at all.
     */
    @Test
    fun oneOutageIsProbedOnlyOnce() {
        assertFalse(
            OttaiBleManager.shouldProbeOutageAdvertisement(
                alreadyProbedThisOutage = true,
                activationScanActive = false,
                managedScanActive = false,
                nowMs = now,
                lastProbeAtMs = now - OttaiBleManager.OUTAGE_PROBE_MIN_GAP_MS * 10,
            ),
        )
    }

    @Test
    fun probesAreSpacedEvenAcrossSeparateOutages() {
        assertFalse(
            OttaiBleManager.shouldProbeOutageAdvertisement(
                alreadyProbedThisOutage = false,
                activationScanActive = false,
                managedScanActive = false,
                nowMs = now,
                lastProbeAtMs = now - OttaiBleManager.OUTAGE_PROBE_MIN_GAP_MS + 1_000L,
            ),
        )
        assertTrue(
            OttaiBleManager.shouldProbeOutageAdvertisement(
                alreadyProbedThisOutage = false,
                activationScanActive = false,
                managedScanActive = false,
                nowMs = now,
                lastProbeAtMs = now - OttaiBleManager.OUTAGE_PROBE_MIN_GAP_MS,
            ),
        )
    }

    /**
     * The activation/candidate scan owns the radio and the transport during setup. A probe racing
     * it is exactly the interference this must never introduce, so it stands aside regardless of
     * how long ago the last probe ran.
     */
    @Test
    fun anArmedActivationScanSuppressesTheProbeEntirely() {
        assertFalse(
            OttaiBleManager.shouldProbeOutageAdvertisement(
                alreadyProbedThisOutage = false,
                activationScanActive = true,
                managedScanActive = false,
                nowMs = now,
                lastProbeAtMs = 0L,
            ),
        )
    }

    /**
     * Android's scan throttle is per app, not per scanner: five starts in 30s parks the offender
     * for half an hour, and the startScan the platform then refuses can be SensorBluetooth's — the
     * discovery and recovery path for every sensor family, restarted on its own interval loop.
     * A measurement must never be the reason that call fails.
     */
    @Test
    fun theManagedScanOutranksTheProbe() {
        assertFalse(
            OttaiBleManager.shouldProbeOutageAdvertisement(
                alreadyProbedThisOutage = false,
                activationScanActive = false,
                managedScanActive = true,
                nowMs = now,
                lastProbeAtMs = 0L,
            ),
        )
    }

    @Test
    fun aClockStepBackwardsDoesNotLatchTheProbeOff() {
        // Only an accepted probe writes lastProbeAtMs, so a timestamp from the future would
        // otherwise silence the measurement for as long as the clock stayed behind it.
        assertTrue(
            OttaiBleManager.shouldProbeOutageAdvertisement(
                alreadyProbedThisOutage = false,
                activationScanActive = false,
                managedScanActive = false,
                nowMs = now,
                lastProbeAtMs = now + 24L * 3_600_000L,
            ),
        )
    }

    @Test
    fun rssiIsNotReadOutsideStreaming() {
        assertFalse(OttaiBleManager.shouldPollRssi(streaming = false, nowMs = now, lastRssiReadAtMs = 0L))
    }

    @Test
    fun rssiIsReadOncePerCadenceAndNotSooner() {
        assertTrue(OttaiBleManager.shouldPollRssi(streaming = true, nowMs = now, lastRssiReadAtMs = 0L))
        assertFalse(
            OttaiBleManager.shouldPollRssi(
                streaming = true,
                nowMs = now,
                lastRssiReadAtMs = now - OttaiBleManager.RSSI_POLL_INTERVAL_MS + 1_000L,
            ),
        )
        assertTrue(
            OttaiBleManager.shouldPollRssi(
                streaming = true,
                nowMs = now,
                lastRssiReadAtMs = now - OttaiBleManager.RSSI_POLL_INTERVAL_MS,
            ),
        )
        assertTrue(
            OttaiBleManager.shouldPollRssi(
                streaming = true,
                nowMs = now,
                lastRssiReadAtMs = now + 24L * 3_600_000L,
            ),
        )
    }

}
