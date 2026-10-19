package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ICanHealthConstantsTests {

    @Test
    fun canonicalSensorId_normalizesHexIdsToUppercase() {
        assertEquals(
            "8760080A00070000",
            ICanHealthConstants.canonicalSensorId("8760080a00070000")
        )
    }

    @Test
    fun nativeShortSensorAlias_returnsTrailingNativeAliasForCanonicalId() {
        assertEquals(
            "80A00070000",
            ICanHealthConstants.nativeShortSensorAlias("8760080A00070000")
        )
    }

    @Test
    fun matchesCanonicalOrKnownNativeAlias_acceptsCanonicalAndShortAlias() {
        assertTrue(
            ICanHealthConstants.matchesCanonicalOrKnownNativeAlias(
                "8760080A00070000",
                "80A00070000"
            )
        )
    }

    @Test
    fun matchesCanonicalOrKnownNativeAlias_rejectsUnrelatedIds() {
        assertFalse(
            ICanHealthConstants.matchesCanonicalOrKnownNativeAlias(
                "8760080A00070000",
                "X-222227JR7C"
            )
        )
    }

    @Test
    fun matchesOnboardingIdentity_acceptsLauncherCodeForObservedDisSerial() {
        assertEquals("8760080A", ICanHealthConstants.onboardingIdentityPrefix("8760080A2604"))
        assertTrue(
            ICanHealthConstants.matchesOnboardingIdentity(
                "8760080A2604",
                "8760080a0007000000000000"
            )
        )
        assertTrue(
            ICanHealthConstants.matchesOnboardingIdentity(
                "8760080A2604",
                "8760080A00070000"
            )
        )
    }

    @Test
    fun matchesOnboardingIdentity_acceptsExactShortSerialFamily() {
        assertTrue(
            ICanHealthConstants.matchesOnboardingIdentity(
                "726022F50005",
                "726022f50005"
            )
        )
    }

    @Test
    fun matchesOnboardingIdentity_acceptsShiftedI6ActiveCodeFamily() {
        assertTrue(
            ICanHealthConstants.matchesOnboardingIdentity(
                "ZA1OR03MSE50",
                "01OR03MS00070101"
            )
        )
    }

    @Test
    fun matchesOnboardingIdentity_rejectsNearbyShiftedI6Sensor() {
        assertFalse(
            ICanHealthConstants.matchesOnboardingIdentity(
                "ZA1OR03MSE50",
                "01OR03MT00070101"
            )
        )
    }

    @Test
    fun matchesOnboardingIdentity_rejectsAnotherNearbySensor() {
        assertFalse(
            ICanHealthConstants.matchesOnboardingIdentity(
                "8760080A2604",
                "8760080B00070000"
            )
        )
    }

    @Test
    fun matchesOnboardingIdentity_rejectsWeakPartialIdentity() {
        assertFalse(ICanHealthConstants.matchesOnboardingIdentity("8760", "8760080A00070000"))
    }

    @Test
    fun onboardingIdentityPrefix_mapsChineseI6CodeToObservedDisPrefix() {
        assertEquals("01OV04NA", ICanHealthConstants.onboardingIdentityPrefix("ZP1OV04NA550"))
        assertTrue(
            ICanHealthConstants.matchesOnboardingIdentity(
                "ZP1OV04NA550",
                "01OV04NA0003010100000000"
            )
        )
    }

    @Test
    fun matchesOnboardingIdentity_rejectsAnotherChineseI6() {
        assertFalse(
            ICanHealthConstants.matchesOnboardingIdentity(
                "ZP1OV04NA550",
                "01R9089R0003010100000000"
            )
        )
    }

    @Test
    fun isEndedStatusSequenceCap_onlyMatchesEndedStateAtVendorCap() {
        assertFalse(
            ICanHealthConstants.isEndedStatusSequenceCap(
                ICanHealthConstants.LAUNCHER_STATE_ENDED,
                ICanHealthConstants.LAUNCHER_ENDED_STATUS_SEQUENCE_CAP_MINUTES - 1
            )
        )
        assertFalse(
            ICanHealthConstants.isEndedStatusSequenceCap(
                ICanHealthConstants.LAUNCHER_STATE_RUNNING,
                ICanHealthConstants.LAUNCHER_ENDED_STATUS_SEQUENCE_CAP_MINUTES
            )
        )
        assertTrue(
            ICanHealthConstants.isEndedStatusSequenceCap(
                ICanHealthConstants.LAUNCHER_STATE_ENDED,
                ICanHealthConstants.LAUNCHER_ENDED_STATUS_SEQUENCE_CAP_MINUTES
            )
        )
    }

    @Test
    fun endedStatusEndTimestamp_usesObservedStatusSequence() {
        val sessionStart = 1_000_000L
        val cap = ICanHealthConstants.LAUNCHER_ENDED_STATUS_SEQUENCE_CAP_MINUTES

        assertEquals(
            sessionStart + cap * 60_000L,
            ICanHealthConstants.endedStatusEndTimestampMs(sessionStart, cap)
        )
        assertEquals(
            sessionStart + (cap + 3) * 60_000L,
            ICanHealthConstants.endedStatusEndTimestampMs(sessionStart, cap + 3)
        )
        assertEquals(
            null,
            ICanHealthConstants.endedStatusEndTimestampMs(sessionStart, cap - 1)
        )
    }

    @Test
    fun hasCompleteEndedStatusHistory_requiresTailAtObservedEnd() {
        val sessionStart = 1_000_000L
        val cap = ICanHealthConstants.LAUNCHER_ENDED_STATUS_SEQUENCE_CAP_MINUTES
        val end = sessionStart + cap * 60_000L
        val tolerance = 2 * 60_000L

        assertTrue(
            ICanHealthConstants.hasCompleteEndedStatusHistory(
                sessionStartEpochMs = sessionStart,
                sequenceNumber = cap,
                tailTimestampMs = end,
                toleranceMs = tolerance,
            )
        )
        assertTrue(
            ICanHealthConstants.hasCompleteEndedStatusHistory(
                sessionStartEpochMs = sessionStart,
                sequenceNumber = cap,
                tailTimestampMs = end - tolerance,
                toleranceMs = tolerance,
            )
        )
        assertFalse(
            ICanHealthConstants.hasCompleteEndedStatusHistory(
                sessionStartEpochMs = sessionStart,
                sequenceNumber = cap,
                tailTimestampMs = end - tolerance - 1L,
                toleranceMs = tolerance,
            )
        )
        assertFalse(
            ICanHealthConstants.hasCompleteEndedStatusHistory(
                sessionStartEpochMs = 0L,
                sequenceNumber = cap,
                tailTimestampMs = end,
                toleranceMs = tolerance,
            )
        )
    }
}
