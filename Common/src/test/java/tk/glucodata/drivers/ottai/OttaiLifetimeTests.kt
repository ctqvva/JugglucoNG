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

    // The firmware enforces exactly the maxActive it accepted: on the field a 25-day unit ran
    // 24.999 days and 28-day units 27.999 and past day 19, each under a 14-day cloud rating. The
    // day-15 stop seen once was a unit carrying the official 15 d 30 min, not a cap. So the accepted
    // value is the end from the first minute, and the cloud rating stands in only while it is unknown.
    @Test
    fun theFirmwareAcceptedLifetimeIsTheEndAndTheCloudRatingOnlyAFallback() {
        for (cloud in listOf(14L * DAY_MS, 15L * DAY_MS, 0L)) {
            assertEquals(28L * DAY_MS, OttaiConstants.expectedLifetimeMs(cloud, acceptedMaxActiveMs = 28L * DAY_MS))
            assertEquals(25L * DAY_MS, OttaiConstants.expectedLifetimeMs(cloud, acceptedMaxActiveMs = 25L * DAY_MS))
            val officialMaxActive = 15L * DAY_MS + 30L * 60_000L
            assertEquals(officialMaxActive, OttaiConstants.expectedLifetimeMs(cloud, acceptedMaxActiveMs = officialMaxActive))
        }
        // Accepted below the rating is still the end: the firmware stops there, not at the rating.
        assertEquals(14L * DAY_MS, OttaiConstants.expectedLifetimeMs(15L * DAY_MS, acceptedMaxActiveMs = 14L * DAY_MS))
        // Firmware value unknown: the cloud rating, and without one the default.
        assertEquals(14L * DAY_MS, OttaiConstants.expectedLifetimeMs(14L * DAY_MS, acceptedMaxActiveMs = 0L))
        assertEquals(
            OttaiConstants.DEFAULT_ACTIVE_EXPIRE_MS,
            OttaiConstants.expectedLifetimeMs(0L, acceptedMaxActiveMs = 0L),
        )
    }

    @Test
    fun queueRejectedMaxActiveDoesNotAdvanceAndIssuedActivateMayStampWarmup() {
        val missing = OttaiBleManager.interpretGattWrite(characteristicPresent = false, writeAccepted = true)
        val rejected = OttaiBleManager.interpretGattWrite(characteristicPresent = true, writeAccepted = false)
        val thrown = OttaiBleManager.interpretGattWrite(characteristicPresent = true, writeAccepted = null)
        val issued = OttaiBleManager.interpretGattWrite(characteristicPresent = true, writeAccepted = true)

        assertTrue(missing is OttaiBleManager.GattWriteIssue.Missing)
        assertTrue(rejected is OttaiBleManager.GattWriteIssue.Rejected)
        assertTrue(thrown is OttaiBleManager.GattWriteIssue.Rejected)
        assertTrue(issued is OttaiBleManager.GattWriteIssue.Issued)

        assertTrue(OttaiBleManager.maxActiveAdvancesOnQueueResult(missing))
        assertFalse(OttaiBleManager.maxActiveAdvancesOnQueueResult(rejected))
        assertTrue(OttaiBleManager.maxActiveFailsClosedOnQueueResult(rejected))
        assertTrue(OttaiBleManager.shouldStampWarmupPrefOnActivateIssue(issued, commandNeedsActivation = true))
        assertFalse(OttaiBleManager.shouldStampWarmupPrefOnActivateIssue(rejected, commandNeedsActivation = true))
        assertFalse(OttaiBleManager.shouldStampWarmupPrefOnActivateIssue(issued, commandNeedsActivation = false))
    }

    @Test
    fun officialStartIgnoresProvisionalAndUnreliableStreamAndIssueTimePref() {
        val cloud = 1_700_000_000_000L
        val reliable = 1_700_000_100_000L
        val guessed = 1_700_000_200_000L
        val ack = 1_700_000_300_000L

        assertEquals(
            cloud,
            OttaiBleManager.officialStartMs(
                cloudActiveTimeMs = cloud,
                streamStartMs = guessed,
                streamStartReliable = false,
                activationCommandSentAtMs = ack,
            ),
        )
        assertEquals(
            reliable,
            OttaiBleManager.officialStartMs(
                cloudActiveTimeMs = 0L,
                streamStartMs = reliable,
                streamStartReliable = true,
                activationCommandSentAtMs = ack,
            ),
        )
        assertEquals(
            ack,
            OttaiBleManager.officialStartMs(
                cloudActiveTimeMs = 0L,
                streamStartMs = guessed,
                streamStartReliable = false,
                activationCommandSentAtMs = ack,
            ),
        )
        assertEquals(
            0L,
            OttaiBleManager.officialStartMs(
                cloudActiveTimeMs = 0L,
                streamStartMs = guessed,
                streamStartReliable = false,
                activationCommandSentAtMs = 0L,
            ),
        )
    }

    @Test
    fun draftBleAddressKeepsNonCloudRadioAndDoesNotOverwriteItWithCloudId() {
        val cloud = "001122334455"
        val scanned = "AA:BB:CC:DD:EE:FF"
        val poisoned = OttaiConstants.macWithColons(cloud)

        assertEquals(scanned, OttaiConstants.draftBleAddressForMaterials(scanned, poisoned, cloud))
        assertEquals(scanned, OttaiConstants.draftBleAddressForMaterials(null, scanned, cloud))
        assertEquals(poisoned, OttaiConstants.draftBleAddressForMaterials(null, poisoned, cloud))
        assertEquals(poisoned, OttaiConstants.draftBleAddressForMaterials(poisoned, null, cloud))
        assertTrue(OttaiConstants.isNonCloudColonBle(scanned, cloud))
        assertFalse(OttaiConstants.isNonCloudColonBle(poisoned, cloud))
        assertFalse(
            OttaiConstants.shouldPersistMaterialsDraftAddress(
                poisoned,
                cloud,
                allowCloudIdFallback = false,
            ),
        )
        assertTrue(
            OttaiConstants.shouldPersistMaterialsDraftAddress(
                scanned,
                cloud,
                allowCloudIdFallback = false,
            ),
        )
        assertTrue(
            OttaiConstants.shouldPersistMaterialsDraftAddress(
                poisoned,
                cloud,
                allowCloudIdFallback = true,
            ),
        )
        assertEquals(
            scanned,
            OttaiConstants.connectBleAddress(
                composeBle = null,
                draftBle = poisoned,
                managedBle = scanned,
                cloudId = cloud,
            ),
        )
        assertEquals(
            scanned,
            OttaiConstants.connectBleAddress(
                composeBle = scanned,
                draftBle = poisoned,
                managedBle = null,
                cloudId = cloud,
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
        assertFalse(OttaiConstants.shouldStartActivation(commandStatus = -1, explicitlyRequested = true))
        assertFalse(OttaiConstants.shouldStartActivation(commandStatus = 2, explicitlyRequested = false))
        assertTrue(OttaiConstants.shouldStartActivation(commandStatus = 2, explicitlyRequested = true))
        assertFalse(OttaiConstants.shouldStartActivation(commandStatus = 3, explicitlyRequested = true))
        assertFalse(OttaiConstants.shouldStartActivation(commandStatus = 4, explicitlyRequested = true))
    }

    @Test
    fun wizardConnectArmsActivationWithoutForcingWrites() {
        assertTrue(OttaiConstants.setupConnectArmsActivation(activate = false, activateIfNeeded = true))
        assertFalse(OttaiConstants.setupConnectForcesImmediateActivation(activate = false))
        assertTrue(OttaiConstants.setupConnectForcesImmediateActivation(activate = true))
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
        assertFalse(
            OttaiConstants.shouldRescanPendingSetupActivation(
                commandStatus = -1,
                explicitlyRequested = true,
                commandByteSeenThisAttempt = true,
            ),
        )
        assertTrue(
            OttaiConstants.shouldRescanPendingSetupActivation(
                commandStatus = -1,
                explicitlyRequested = true,
                commandByteSeenThisAttempt = false,
            ),
        )
    }

    @Test
    fun firstUseActivationKeepsArmUntilIssuedAndBlocksInFlightGap() {
        assertTrue(
            OttaiConstants.shouldScheduleFirstUseActivation(
                commandStatus = 2,
                explicitlyRequested = true,
                activateCommandIssued = false,
                advancedActivate = false,
                activationInFlight = false,
                activationBusy = false,
            ),
        )
        assertFalse(
            OttaiConstants.shouldScheduleFirstUseActivation(
                commandStatus = 2,
                explicitlyRequested = true,
                activateCommandIssued = false,
                advancedActivate = false,
                activationInFlight = true,
                activationBusy = false,
            ),
        )
        assertFalse(
            OttaiConstants.shouldScheduleFirstUseActivation(
                commandStatus = -1,
                explicitlyRequested = true,
                activateCommandIssued = false,
                advancedActivate = false,
                activationInFlight = false,
                activationBusy = false,
            ),
        )
        assertFalse(
            OttaiConstants.shouldScheduleFirstUseActivation(
                commandStatus = 3,
                explicitlyRequested = true,
                activateCommandIssued = false,
                advancedActivate = false,
                activationInFlight = false,
                activationBusy = false,
            ),
        )
        assertFalse(
            OttaiConstants.shouldScheduleFirstUseActivation(
                commandStatus = 4,
                explicitlyRequested = true,
                activateCommandIssued = false,
                advancedActivate = false,
                activationInFlight = false,
                activationBusy = false,
            ),
        )
        assertFalse(
            OttaiConstants.shouldScheduleFirstUseActivation(
                commandStatus = 2,
                explicitlyRequested = true,
                activateCommandIssued = true,
                advancedActivate = false,
                activationInFlight = false,
                activationBusy = false,
            ),
        )
        assertTrue(
            OttaiConstants.shouldScheduleFirstUseActivation(
                commandStatus = 2,
                explicitlyRequested = false,
                activateCommandIssued = true,
                advancedActivate = true,
                activationInFlight = false,
                activationBusy = false,
            ),
        )
    }

    @Test
    fun dropBeforeIssuedResumesWithoutResettingLadderAndDoesNotFailClosed() {
        assertTrue(
            OttaiConstants.shouldReconnectToResumeActivation(
                activateCommandIssued = false,
                activationNegotiationActive = true,
                activationRetryPending = false,
                hasCandidates = true,
                activationInFlight = false,
            ),
        )
        assertFalse(
            OttaiConstants.shouldBeginActivationNegotiation(
                activationNegotiationActive = true,
                candidatesEmpty = false,
            ),
        )
        assertTrue(
            OttaiConstants.shouldBeginActivationNegotiation(
                activationNegotiationActive = false,
                candidatesEmpty = true,
            ),
        )
        assertFalse(
            OttaiConstants.autoFailClosedBeforeActivationAck(
                commandNeedsActivation = true,
                activateCommandIssued = false,
                activationCommandSentAtMs = 0L,
                waitingForActivateCommandAck = true,
            ),
        )
        assertTrue(
            OttaiConstants.autoFailClosedBeforeActivationAck(
                commandNeedsActivation = true,
                activateCommandIssued = true,
                activationCommandSentAtMs = 0L,
                waitingForActivateCommandAck = true,
            ),
        )
        assertFalse(
            OttaiConstants.autoFailClosedBeforeActivationAck(
                commandNeedsActivation = true,
                activateCommandIssued = true,
                activationCommandSentAtMs = 1L,
                waitingForActivateCommandAck = true,
            ),
        )
        assertFalse(
            OttaiConstants.autoFailClosedBeforeActivationAck(
                commandNeedsActivation = true,
                activateCommandIssued = true,
                activationCommandSentAtMs = 0L,
                waitingForActivateCommandAck = false,
            ),
        )
        assertFalse(
            OttaiConstants.writeErrorShouldFailActivation(
                actStepActive = true,
                actStepIsActivateCommand = false,
                gattStatus = 133,
            ),
        )
        assertFalse(
            OttaiConstants.shouldScanForActivationCandidateOn147(
                resumeActivation = true,
                activationRetryPending = false,
                gattStatus = 147,
            ),
        )
        assertTrue(
            OttaiConstants.shouldScanForActivationCandidateOn147(
                resumeActivation = true,
                activationRetryPending = true,
                gattStatus = 147,
            ),
        )
        assertFalse(
            OttaiConstants.writeErrorShouldFailActivation(
                actStepActive = true,
                actStepIsActivateCommand = false,
                gattStatus = 133,
            ),
        )
        assertTrue(
            OttaiConstants.writeErrorShouldFailActivation(
                actStepActive = true,
                actStepIsActivateCommand = false,
                gattStatus = 1,
            ),
        )
        assertTrue(
            OttaiConstants.writeErrorShouldFailActivation(
                actStepActive = true,
                actStepIsActivateCommand = true,
                gattStatus = 133,
            ),
        )
        assertFalse(
            OttaiConstants.shouldRetryMaxActiveOnWriteError(133),
        )
        assertFalse(
            OttaiConstants.shouldRetryMaxActiveOnWriteError(8),
        )
        assertFalse(
            OttaiConstants.shouldRetryMaxActiveOnWriteError(19),
        )
        assertTrue(
            OttaiConstants.shouldRetryMaxActiveOnWriteError(1),
        )
        assertFalse(
            OttaiConstants.writeErrorShouldFailActivation(
                actStepActive = true,
                actStepIsActivateCommand = false,
                gattStatus = 8,
            ),
        )
        assertFalse(
            OttaiConstants.shouldReconnectToResumeActivation(
                activateCommandIssued = true,
                activationNegotiationActive = true,
                activationRetryPending = true,
                hasCandidates = true,
                activationInFlight = true,
            ),
        )
        assertFalse(
            OttaiConstants.staleActivationStartShouldFailClosed(
                requestStarted = false,
                hasLiveAuthenticatedGatt = false,
                mayEnterWrites = true,
            ),
        )
        assertTrue(
            OttaiConstants.staleActivationStartShouldFailClosed(
                requestStarted = false,
                hasLiveAuthenticatedGatt = true,
                mayEnterWrites = true,
            ),
        )
    }

    @Test
    fun issuedActivateBlocksAutomaticWritesUntilAdvancedGesture() {
        assertFalse(
            OttaiConstants.mayEnterActivationWrites(
                commandStatus = 2,
                activateCommandIssued = true,
                advancedActivate = false,
            ),
        )
        assertTrue(
            OttaiConstants.mayEnterActivationWrites(
                commandStatus = 2,
                activateCommandIssued = true,
                advancedActivate = true,
            ),
        )
        assertFalse(
            OttaiConstants.shouldIgnoreAlreadyStartedActivation(
                forceActivation = true,
                advancedActivate = false,
                hasOfficialStart = true,
            ),
        )
        assertTrue(
            OttaiConstants.shouldIgnoreAlreadyStartedActivation(
                forceActivation = false,
                advancedActivate = false,
                hasOfficialStart = true,
            ),
        )
        assertFalse(
            OttaiConstants.shouldIgnoreAlreadyStartedActivation(
                forceActivation = false,
                advancedActivate = true,
                hasOfficialStart = true,
            ),
        )
        assertFalse(
            OttaiConstants.mayEnterActivationWrites(
                commandStatus = 3,
                activateCommandIssued = true,
                advancedActivate = true,
            ),
        )
        assertTrue(
            OttaiConstants.setupConnectingShouldReturnToSensor(activationFailed = true),
        )
        assertFalse(
            OttaiConstants.setupConnectingShouldReturnToSensor(activationFailed = false),
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
        assertTrue(
            OttaiConstants.shouldProbeActivationAdvertisement(
                discoveryPending = true,
                scannedAddress = rotated,
                expectedAddress = expected,
                advertisedName = "Syai CGM",
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

    @Test
    fun zeroStartOrPreheatInIncomingMaterialsKeepsWhatIsStored() {
        // The 2026-08-07 sensor: real start 2026-07-23, recovered locally. A vendor-activated
        // sensor's cloud answer and a pre-activation export both carry activeTime = 0.
        val storedStart = 1_784_764_800_000L
        val stored = lifetimeMaterials(
            activeTimeMs = storedStart,
            preheatPeriodMs = 2L * HOUR_MS,
            retainTimeMs = 2L * DAY_MS,
        )
        val merged = mergeOttaiLifetimeFields(
            stored,
            lifetimeMaterials(activeTimeMs = 0L, preheatPeriodMs = 0L, retainTimeMs = 0L),
            temporaryBindAtMs = 0L,
        )

        assertEquals(storedStart, merged.activeTimeMs)
        assertEquals(2L * HOUR_MS, merged.preheatPeriodMs)
        // retainTimeMs feeds the activation's destruction write; the merge leaves it as sent.
        assertEquals(0L, merged.retainTimeMs)
    }

    @Test
    fun positiveIncomingStartReplacesTheStoredOneInMilliseconds() {
        val stored = lifetimeMaterials(activeTimeMs = 1_784_764_800_000L)

        // CN V3 bind echoes epoch seconds.
        assertEquals(
            1_787_489_036_000L,
            mergeOttaiLifetimeFields(stored, lifetimeMaterials(activeTimeMs = 1_787_489_036L), 0L).activeTimeMs,
        )
        assertEquals(
            0L,
            mergeOttaiLifetimeFields(lifetimeMaterials(activeTimeMs = 0L), lifetimeMaterials(activeTimeMs = 0L), 0L)
                .activeTimeMs,
        )
    }

    /**
     * The #20/#33 seam. A file exported by a device that stored a temporary bind's stamp must not
     * bring it back here, over a real start or into an empty slot; a real start in the file that
     * predates the bind is taken.
     */
    @Test
    fun anImportedTemporaryBindStampIsUnknown() {
        val realStart = 1_784_764_800_000L
        val boundAt = realStart + 10L * DAY_MS
        val stamp = lifetimeMaterials(activeTimeMs = boundAt)
        val empty = lifetimeMaterials(activeTimeMs = 0L)
        assertEquals(realStart, mergeOttaiLifetimeFields(lifetimeMaterials(activeTimeMs = realStart), stamp, boundAt).activeTimeMs)
        assertEquals(0L, mergeOttaiLifetimeFields(empty, stamp, boundAt).activeTimeMs)
        assertEquals(realStart, mergeOttaiLifetimeFields(empty, lifetimeMaterials(activeTimeMs = realStart), boundAt).activeTimeMs)
        // Without a bind on record the same value is an ordinary start.
        assertEquals(boundAt, mergeOttaiLifetimeFields(empty, stamp, 0L).activeTimeMs)
    }

    @Test
    fun anImportWithoutAUsableAcceptedLifetimeKeepsTheStoredOne() {
        val stored = 28L * DAY_MS
        assertEquals(stored, acceptedMaxActiveAfterImport(fileValueMs = 0L, storedMs = stored))
        assertEquals(stored, acceptedMaxActiveAfterImport(fileValueMs = 4_204_901_547_000L, storedMs = stored))
        assertEquals(30L * DAY_MS, acceptedMaxActiveAfterImport(fileValueMs = 30L * DAY_MS, storedMs = stored))
        assertEquals(0L, acceptedMaxActiveAfterImport(fileValueMs = 0L, storedMs = 0L))
    }

    // A retry after an acknowledged attempt that did not start the sensor writes a newer command;
    // the gate has to start from that one, whichever of the two fields holds it.
    @Test
    fun theWarmupAnchorIsTheConfirmedStartOrTheLaterCommandInstant() {
        val t1 = 1_700_000_000_000L
        val t2 = t1 + 15L * 60_000L
        assertEquals(t2, OttaiBleManager.warmupAnchorFor(0L, t1, t2))
        assertEquals(t2, OttaiBleManager.warmupAnchorFor(0L, t2, t1))
        assertEquals(t2, OttaiBleManager.warmupAnchorFor(0L, 0L, t2))
        assertEquals(t1 - DAY_MS, OttaiBleManager.warmupAnchorFor(t1 - DAY_MS, t1, t2))
        assertEquals(0L, OttaiBleManager.warmupAnchorFor(0L, 0L, 0L))
    }

    // A clock step during the ramp re-anchors the stream, so the ramp's records date past the
    // window while the gate's own anchor stays on the confirmed start. The record's own counter
    // does not move, and it is what still says the sensor is settling.
    @Test
    fun theWarmupGateAlsoSuppressesOnTheRecordsOwnCounter() {
        val start = 1_700_000_000_000L
        // Record 3 dated 13 min past the anchor after a +10 min clock step: the counter still holds.
        assertTrue(OttaiBleManager.warmupSuppresses(start, start + 13L * 60_000L, dataNo = 3))
        // Record 10 has run the whole window: out on both criteria.
        assertFalse(OttaiBleManager.warmupSuppresses(start, start + 10L * 60_000L, dataNo = 10))
        // No anchor of our own — a sensor this app did not activate: the whole gate is off, counter
        // included, however low the counter reads.
        assertFalse(OttaiBleManager.warmupSuppresses(0L, start + 13L * 60_000L, dataNo = 3))
        // The date criterion still stands on its own.
        assertTrue(OttaiBleManager.warmupSuppresses(start, start + 60_000L, dataNo = 30_000))
    }

    private fun lifetimeMaterials(
        activeTimeMs: Long,
        preheatPeriodMs: Long = 0L,
        retainTimeMs: Long = 0L,
    ) = OttaiRegistry.DeviceMaterials(
        keyAHex = "",
        method = "",
        coefficient = "",
        activeTimeMs = activeTimeMs,
        deviceVersion = "",
        deviceId = 0,
        retainTimeMs = retainTimeMs,
        preheatPeriodMs = preheatPeriodMs,
    )

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val HOUR_MS = 60L * 60L * 1000L
    }
}
