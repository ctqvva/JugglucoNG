package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5 regression: the existing `AnytimeManagedSensorIdentityAdapter` is
 * family-agnostic — it recognizes CT2 sensors through `AnytimeConstants`
 * (resolveFamily → CT2, isAnytimeDevice, provisional id). No new production
 * adapter is needed; these assertions pin the pure seam the adapter relies on
 * (see docs/ct14-implementation-plan.md §7).
 */
class AnytimeCt2IdentityTests {

    private val ct2Prefixes = listOf(
        "SN04", "SN06", "SN08", "SN12", "SN18", "SN20", "SN22", "SN48", "SN50", "SN52",
    )

    @Test
    fun everyCt2PrefixResolvesToCt2Family() {
        for (prefix in ct2Prefixes) {
            assertEquals(
                "$prefix must resolve to CT2",
                AnytimeConstants.Family.CT2,
                AnytimeConstants.resolveFamily("$prefix-test").family,
            )
        }
    }

    @Test
    fun liveCaptureNameResolvesToCt2() {
        assertEquals(
            AnytimeConstants.Family.CT2,
            AnytimeConstants.resolveFamily("SN08402458").family,
        )
    }

    @Test
    fun ct2NamesAreRecognizedAsAnytimeDevices() {
        for (prefix in ct2Prefixes) {
            assertTrue("$prefix must be recognized", AnytimeConstants.isAnytimeDevice("$prefix-abcd"))
        }
    }

    @Test
    fun nonCt2PrefixesDoNotResolveToCt2() {
        // The CT-14 scan filter keys off family; other Anytime prefixes must not.
        for (name in listOf("SN16", "SN26", "SN30", "SN72", "Anytime")) {
            assertFalse(
                "$name must not be CT2",
                AnytimeConstants.resolveFamily(name).family == AnytimeConstants.Family.CT2,
            )
        }
    }

    @Test
    fun provisionalSensorIdIsFamilyAgnostic() {
        // deriveInitialSensorId falls back to the shared ANY- prefix only when no
        // MAC/hex is available; the adapter's mayBeAnytimeAlias accepts it for any family.
        val derived = AnytimeConstants.deriveInitialSensorId("SN08402458", null)
        assertFalse(derived.isBlank())
        assertTrue(AnytimeConstants.isProvisionalSensorId(derived))
    }

    @Test
    fun macAddressDerivesStableCanonicalSensorId() {
        val canonical = AnytimeConstants.canonicalSensorId("94:A9:A8:47:89:0E")
        assertEquals("94A9A847890E", canonical)
        assertEquals("94:A9:A8:47:89:0E", AnytimeConstants.macAddressFromSensorId(canonical))
    }
}
