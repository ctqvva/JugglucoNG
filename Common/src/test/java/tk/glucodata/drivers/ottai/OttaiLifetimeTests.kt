package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiLifetimeTests {
    @Test
    fun v3EpochSecondsAreNormalizedToPersistenceMilliseconds() {
        assertEquals(1_787_489_036_000L, normalizeOttaiActiveTimeMs(1_787_489_036L))
        assertEquals(1_787_489_036_000L, normalizeOttaiActiveTimeMs(1_787_489_036_000L))
        assertEquals(0L, normalizeOttaiActiveTimeMs(0L))
    }

    @Test
    fun impossibleLifetimeValuesCannotProduceFarFutureEndDates() {
        assertEquals(28L * DAY_MS, OttaiConstants.sanitizeActiveExpireMs(28L * DAY_MS))
        assertEquals(0L, OttaiConstants.sanitizeActiveExpireMs(4_204_901_547_000L))
        assertEquals(
            OttaiConstants.DEFAULT_ACTIVE_EXPIRE_MS,
            OttaiConstants.expectedLifetimeMs(
                cloudActiveExpireMs = 4_204_901_547_000L,
                acceptedMaxActiveMs = 0L,
            ),
        )
    }

    @Test
    fun activationNegotiatesDownToCloudRatedLifetime() {
        val cloudFourteenDays = 14L * DAY_MS

        assertEquals(
            ((30L downTo 15L) + 14L).map { it * DAY_MS },
            OttaiConstants.activationMaxActiveCandidatesMs(cloudFourteenDays),
        )
    }

    @Test
    fun activationTriesLongerCloudLifetimeFirst() {
        val cloudFortyDays = 40L * DAY_MS

        assertEquals(
            (listOf(40L) + (30L downTo 15L)).map { it * DAY_MS },
            OttaiConstants.activationMaxActiveCandidatesMs(cloudFortyDays),
        )
    }

    @Test
    fun activationDoesNotDuplicateCloudDurationInsideLadder() {
        val cloudTwentyFiveDays = 25L * DAY_MS

        assertEquals(
            (30L downTo 15L).map { it * DAY_MS },
            OttaiConstants.activationMaxActiveCandidatesMs(cloudTwentyFiveDays),
        )
    }

    @Test
    fun acceptedLifetimeIsCommittedOnlyAfterActivationStatusThree() {
        val accepted = 28L * DAY_MS

        assertEquals(
            0L,
            OttaiBleManager.acceptedMaxActiveToCommit(
                commandStatus = -1,
                activationCommandAcknowledged = false,
                pendingDurationMs = accepted,
            ),
        )
        assertEquals(
            0L,
            OttaiBleManager.acceptedMaxActiveToCommit(
                commandStatus = 2,
                activationCommandAcknowledged = true,
                pendingDurationMs = accepted,
            ),
        )
        assertEquals(
            0L,
            OttaiBleManager.acceptedMaxActiveToCommit(
                commandStatus = 3,
                activationCommandAcknowledged = false,
                pendingDurationMs = accepted,
            ),
        )
        assertEquals(
            accepted,
            OttaiBleManager.acceptedMaxActiveToCommit(
                commandStatus = 3,
                activationCommandAcknowledged = true,
                pendingDurationMs = accepted,
            ),
        )
    }

    @Test
    fun expectedLifetimeUsesAcceptedMaxActive() {
        assertEquals(
            25L * DAY_MS,
            OttaiConstants.expectedLifetimeMs(
                cloudActiveExpireMs = 14L * DAY_MS,
                acceptedMaxActiveMs = 25L * DAY_MS,
            ),
        )
        assertEquals(
            14L * DAY_MS,
            OttaiConstants.expectedLifetimeMs(
                cloudActiveExpireMs = 14L * DAY_MS,
                acceptedMaxActiveMs = 0L,
            ),
        )
    }

    @Test
    fun endedSensorRecoveryIsLimitedToStatusFourInsideManagedLifetime() {
        val start = 1_000_000L

        assertTrue(
            OttaiConstants.shouldAttemptEndedSensorRecovery(
                commandStatus = 4,
                activeTimeMs = start,
                nowMs = start + 14L * DAY_MS,
            ),
        )
        assertFalse(
            OttaiConstants.shouldAttemptEndedSensorRecovery(
                commandStatus = 3,
                activeTimeMs = start,
                nowMs = start + 14L * DAY_MS,
            ),
        )
        assertFalse(
            OttaiConstants.shouldAttemptEndedSensorRecovery(
                commandStatus = 4,
                activeTimeMs = start,
                nowMs = start + OttaiConstants.EXTENDED_LIFETIME_MS,
            ),
        )
        assertFalse(
            OttaiConstants.shouldAttemptEndedSensorRecovery(
                commandStatus = 4,
                activeTimeMs = 0L,
                nowMs = start + 14L * DAY_MS,
            ),
        )
    }

    @Test
    fun commandStatusBelowThreeRequiresActivation() {
        assertFalse(OttaiConstants.commandNeedsActivation(-1))
        assertTrue(OttaiConstants.commandNeedsActivation(0))
        assertTrue(OttaiConstants.commandNeedsActivation(1))
        assertTrue(OttaiConstants.commandNeedsActivation(2))
        assertFalse(OttaiConstants.commandNeedsActivation(3))
        assertFalse(OttaiConstants.commandNeedsActivation(4))
    }

    @Test
    fun activationRequiresExplicitUserRequest() {
        assertFalse(OttaiConstants.shouldStartActivation(commandStatus = 2, explicitlyRequested = false))
        assertTrue(OttaiConstants.shouldStartActivation(commandStatus = 2, explicitlyRequested = true))
        assertFalse(OttaiConstants.shouldStartActivation(commandStatus = 3, explicitlyRequested = true))
        assertFalse(OttaiConstants.shouldStartActivation(commandStatus = 4, explicitlyRequested = true))
    }

    @Test
    fun cnAndSyaiActivationRequireNfcWakeButGlobalOttaiDoesNot() {
        assertTrue(OttaiConstants.requiresNfcActivationWake(OttaiConstants.API_BASE))
        assertTrue(OttaiConstants.requiresNfcActivationWake(OttaiConstants.API_BASE_SYAI))
        assertFalse(OttaiConstants.requiresNfcActivationWake(OttaiConstants.API_BASE_GLOBAL))
    }

    @Test
    fun setupActivationRescansOnlyBeforeCommandStatusIsKnown() {
        assertTrue(
            OttaiConstants.shouldRescanPendingSetupActivation(
                commandStatus = -1,
                explicitlyRequested = true,
            ),
        )
        assertFalse(
            OttaiConstants.shouldRescanPendingSetupActivation(
                commandStatus = -1,
                explicitlyRequested = false,
            ),
        )
        assertFalse(
            OttaiConstants.shouldRescanPendingSetupActivation(
                commandStatus = 2,
                explicitlyRequested = true,
            ),
        )
    }

    @Test
    fun activationAdvertisementProbeAcceptsExpectedOrNamedOttaiOnlyWhileArmed() {
        val expected = "B4:89:31:21:4A:D5"
        val rotated = "20:A7:16:EE:FA:B0"

        assertTrue(
            OttaiConstants.shouldProbeActivationAdvertisement(
                discoveryPending = true,
                scannedAddress = expected,
                expectedAddress = expected,
                advertisedName = null,
            ),
        )
        assertTrue(
            OttaiConstants.shouldProbeActivationAdvertisement(
                discoveryPending = true,
                scannedAddress = rotated,
                expectedAddress = expected,
                advertisedName = "Ottai",
            ),
        )
        assertFalse(
            OttaiConstants.shouldProbeActivationAdvertisement(
                discoveryPending = false,
                scannedAddress = rotated,
                expectedAddress = expected,
                advertisedName = "Ottai",
            ),
        )
        assertFalse(
            OttaiConstants.shouldProbeActivationAdvertisement(
                discoveryPending = true,
                scannedAddress = rotated,
                expectedAddress = expected,
                advertisedName = "Sinocare CGM",
            ),
        )
        assertFalse(
            OttaiConstants.shouldProbeActivationAdvertisement(
                discoveryPending = true,
                scannedAddress = rotated,
                expectedAddress = expected,
                advertisedName = "Ottai",
                rejectedAddresses = setOf(rotated.lowercase()),
            ),
        )
    }

    @Test
    fun activationProbeIgnoresStrangersByNameWhenNameMatchDisabled() {
        val ours = "B4:89:31:21:4A:D5"
        val neighbour = "C0:9B:9E:60:07:37"

        // An already-activated sensor keeps its address, so a neighbouring Ottai must not
        // be probed just because its advertisement is called "Ottai" — that retargets the
        // transport away from our own sensor for a device that can never authenticate.
        assertFalse(
            OttaiConstants.shouldProbeActivationAdvertisement(
                discoveryPending = true,
                scannedAddress = neighbour,
                expectedAddress = ours,
                advertisedName = "Ottai CGM",
                allowNameMatch = false,
            ),
        )
        // Our own address still matches with the name fallback disabled.
        assertTrue(
            OttaiConstants.shouldProbeActivationAdvertisement(
                discoveryPending = true,
                scannedAddress = ours,
                expectedAddress = ours,
                advertisedName = null,
                allowNameMatch = false,
            ),
        )
        // With the fallback enabled the same neighbour is admitted (fresh-activation case,
        // where the sensor's address may legitimately have changed).
        assertTrue(
            OttaiConstants.shouldProbeActivationAdvertisement(
                discoveryPending = true,
                scannedAddress = neighbour,
                expectedAddress = ours,
                advertisedName = "Ottai CGM",
                allowNameMatch = true,
            ),
        )
    }

    @Test
    fun activationProbeHoldsOutForTheExactAddressWhileTheScanIsYoung() {
        val ours = "60:83:DA:F7:D9:15"
        val neighbour = "C0:9B:9E:60:07:37"
        val armedAt = 1_785_334_591_000L

        // The three units probed on 2026-07-29 were all admitted by the name-only fallback
        // within 15 s of arming, each costing a connect/discover/auth cycle and a scan restart
        // before our own sensor could be seen.
        assertTrue(OttaiConstants.isActivationExactOnlyWindowOpen(armedAt, armedAt + 12_000L))
        assertFalse(
            OttaiConstants.shouldProbeActivationAdvertisement(
                discoveryPending = true,
                scannedAddress = neighbour,
                expectedAddress = ours,
                advertisedName = "Ottai CGM",
                exactOnlyWindowOpen = true,
            ),
        )
        // Our own address is always admitted, window or not — that is the whole point of it.
        assertTrue(
            OttaiConstants.shouldProbeActivationAdvertisement(
                discoveryPending = true,
                scannedAddress = ours,
                expectedAddress = ours,
                advertisedName = null,
                exactOnlyWindowOpen = true,
            ),
        )
        // Once the window closes the fallback returns, so a genuinely rotated address is still
        // reachable — just not before the sensor has had a chance to advertise itself.
        assertFalse(OttaiConstants.isActivationExactOnlyWindowOpen(armedAt, armedAt + 15_000L))
        assertTrue(
            OttaiConstants.shouldProbeActivationAdvertisement(
                discoveryPending = true,
                scannedAddress = neighbour,
                expectedAddress = ours,
                advertisedName = "Ottai CGM",
                exactOnlyWindowOpen = false,
            ),
        )
        // No armed timestamp means no window; never hold out on a scan we cannot age.
        assertFalse(OttaiConstants.isActivationExactOnlyWindowOpen(0L, armedAt))
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
