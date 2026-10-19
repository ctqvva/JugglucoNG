package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsManagedSensorIdentityAdapterTests {
    @Test
    fun resolveCanonicalSensorId_doesNotClaimUnownedOttaiId() {
        assertNull(SibionicsManagedSensorIdentityAdapter.resolveCanonicalSensorId("E4B2B48"))
    }

    @Test
    fun resolveCanonicalSensorId_keepsExplicitSibionicsNamespace() {
        assertEquals(
            "SIBI:0683013AQT9",
            SibionicsManagedSensorIdentityAdapter.resolveCanonicalSensorId("sibi:0683013aqt9"),
        )
    }

    @Test
    fun matchesCallbackId_doesNotUseSibionicsSuffixRulesForForeignIds() {
        assertFalse(
            SibionicsManagedSensorIdentityAdapter.matchesCallbackId(
                "C09B9E4B2B48",
                "E4B2B48",
            ),
        )
    }

    @Test
    fun matchesCallbackId_matchesExplicitSibionicsIds() {
        assertTrue(
            SibionicsManagedSensorIdentityAdapter.matchesCallbackId(
                "SIBI:0683013AQT9",
                "SIBI:0683013AQT9",
            ),
        )
    }
}
