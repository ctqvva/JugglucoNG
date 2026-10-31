package tk.glucodata.drivers.icanhealth

import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Onboarding finds an iCan by a deliberately broad name check, then has to be able to walk away
 * from a peripheral that turns out not to be a CGM. Trace juggluco-trace-20261004-214248.log:
 * the Anytime transmitter `SN9150002398` passed the name check and the driver reconnected to it
 * every 8 s while the real sensor was never considered.
 */
class ICanHealthOnboardingCandidateTests {
    private fun uuid16(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

    private val gap = uuid16("1800")
    private val gattService = uuid16("1801")
    private val deviceInfo = uuid16("180a")

    @Test
    fun theAnytimeTransmitterFromTheTraceIsConclusivelyNotACgm() {
        val anytime = listOf(
            gattService,
            gap,
            deviceInfo,
            UUID.fromString("00001000-1212-efde-1523-785feabcd123"),
            UUID.fromString("1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0"),
        )
        assertTrue(ICanHealthConstants.isConclusivelyNotCgm(anytime))
    }

    @Test
    fun aPeripheralWithTheCgmServiceIsNeverRejected() {
        assertFalse(ICanHealthConstants.isConclusivelyNotCgm(listOf(gap, gattService, deviceInfo, ICanHealthConstants.CGM_SERVICE)))
        assertFalse(ICanHealthConstants.isConclusivelyNotCgm(listOf(ICanHealthConstants.CGM_SERVICE)))
    }

    @Test
    fun anEmptyOrGenericOnlyDiscoveryProvesNothing() {
        // A partial or stale discovery on a real sensor must not get it blacklisted.
        assertFalse(ICanHealthConstants.isConclusivelyNotCgm(emptyList()))
        assertFalse(ICanHealthConstants.isConclusivelyNotCgm(listOf(gap)))
        assertFalse(ICanHealthConstants.isConclusivelyNotCgm(listOf(gap, gattService)))
    }

    @Test
    fun oneConclusiveDiscoveryIsNotEnoughToReject() {
        assertTrue(ICanHealthConstants.NON_CGM_CANDIDATE_REJECT_STRIKES >= 2)
    }

    @Test
    fun theCarFromTheReportPassesTheNameCheck() {
        // 2026-10-30 report: the bonded car head unit `GeelyVehicle995` passes the name check and
        // never accepts a GATT connection (status 147), so no GATT table ever reaches isConclusivelyNotCgm.
        assertTrue(ICanHealthConstants.isICanHealthDevice("GeelyVehicle995"))
    }

    @Test
    fun nameMatchingStaysBroadSoUnknownRebrandsAreStillTried() {
        // Rejection after connecting is what keeps this safe; the name check is not narrowed.
        assertTrue(ICanHealthConstants.isICanHealthDevice("Sinocare CGM"))
        assertTrue(ICanHealthConstants.isICanHealthDevice("iCGM-1234"))
        assertTrue(ICanHealthConstants.isICanHealthDevice("SN9150002398"))
        assertTrue(ICanHealthConstants.isICanHealthDevice("83D005442574"))
    }

    @Test
    fun aNamedCandidateNeedsGattDiscoveryEvenWhenItsAdvertListsAnotherService() {
        val nus = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        assertTrue(ICanHealthConstants.isICanHealthDevice("SatelliteOnline5885"))
        // A full discovery can disqualify the glucometer, while a real CGM may have
        // advertised the same subset and expose CGM only during discovery.
        assertTrue(ICanHealthConstants.isConclusivelyNotCgm(listOf(gap, gattService, nus)))
        assertFalse(ICanHealthConstants.isConclusivelyNotCgm(listOf(gap, gattService, nus, ICanHealthConstants.CGM_SERVICE)))
    }

    @Test
    fun serviceDiscoveryRetainsCandidatesWhenOnlyGenericServicesArrive() {
        assertFalse(ICanHealthConstants.isConclusivelyNotCgm(emptyList()))
        assertFalse(ICanHealthConstants.isConclusivelyNotCgm(listOf(gap, gattService)))
        assertFalse(ICanHealthConstants.isConclusivelyNotCgm(listOf(ICanHealthConstants.CGM_SERVICE)))
    }

    @Test
    fun aNamedCandidateAdvertisingAnotherSensorsSerialIsSkippedDuringOnboarding() {
        assertTrue(
            ICanHealthConstants.advertisedNameConflictsWithOnboarding(
                "ZA1OR03MSE50",
                "iCGM-0123456789ABCDEF",
            )
        )
    }

    @Test
    fun aNamedCandidateAdvertisingTheOnboardingSerialStaysEligible() {
        // Production layout: ZA1OR03MSE50 (active code) -> 01OR03MS00070101 (DIS serial).
        assertFalse(
            ICanHealthConstants.advertisedNameConflictsWithOnboarding(
                "ZA1OR03MSE50",
                "01OR03MS00070101",
            )
        )
        assertFalse(
            ICanHealthConstants.advertisedNameConflictsWithOnboarding(
                "ZA1OR03MSE50",
                "iCGM-01OR03MS00070101",
            )
        )
    }

    @Test
    fun aGenericNameWithoutASerialStaysPairableDuringOnboarding() {
        assertFalse(ICanHealthConstants.advertisedNameConflictsWithOnboarding("ZA1OR03MSE50", "CGM"))
        assertFalse(ICanHealthConstants.advertisedNameConflictsWithOnboarding("ZA1OR03MSE50", "Sinocare CGM"))
        assertFalse(ICanHealthConstants.advertisedNameConflictsWithOnboarding("ZA1OR03MSE50", null))
        assertFalse(ICanHealthConstants.advertisedNameConflictsWithOnboarding("ZA1OR03MSE50", "  "))
    }

    @Test
    fun aShortSerialShapedNameStaysOnThePostConnectionGate() {
        // The Anytime transmitter from the trace: matched by the broad name check, then
        // rejected once its GATT table shows no CGM service.
        assertFalse(ICanHealthConstants.advertisedNameConflictsWithOnboarding("ZA1OR03MSE50", "SN9150002398"))
    }

    @Test
    fun withoutAnOnboardingSnNoCandidateIsFilteredByName() {
        assertFalse(ICanHealthConstants.advertisedNameConflictsWithOnboarding(null, "iCGM-0123456789ABCDEF"))
        assertFalse(ICanHealthConstants.advertisedNameConflictsWithOnboarding("", "iCGM-0123456789ABCDEF"))
    }
}
