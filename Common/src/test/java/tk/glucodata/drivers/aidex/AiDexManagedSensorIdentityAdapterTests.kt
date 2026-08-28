package tk.glucodata.drivers.aidex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDexManagedSensorIdentityAdapterTests {

    @Test
    fun advertisedFGenerationName_usesCanonicalXIdentity() {
        assertEquals(
            "X-22222FZXKT",
            AiDexSerialIdentity.canonicalFromAdvertisement("AiDEX F-22222FZXKT")
        )
        assertEquals("X-22222FZXKT", AiDexSerialIdentity.canonicalFromAdvertisement("F-22222FZXKT"))
        assertEquals(
            "X-2222267V4E",
            AiDexSerialIdentity.canonicalFromAdvertisement("AiDEX sensor X-2222267V4E")
        )
    }

    @Test
    fun macFallback_usesAdvertisedSerialForProtocolOnlyWhenIdentityMatchesAddress() {
        assertEquals(
            "22222FZXKT",
            AiDexSerialIdentity.advertisedProtocolSerialForMacFallback(
                storedSensorId = "X-6083DA152F2D",
                address = "60:83:DA:15:2F:2D",
                advertisedName = "AiDEX F-22222FZXKT",
            )
        )
        assertNull(
            AiDexSerialIdentity.advertisedProtocolSerialForMacFallback(
                storedSensorId = "X-2222267V4E",
                address = "60:83:DA:15:2F:2D",
                advertisedName = "AiDEX F-22222FZXKT",
            )
        )
    }

    @Test
    fun nativeAlias_stripsManagedPrefix() {
        assertEquals("222227JR7C", AiDexManagedSensorIdentityAdapter.nativeAlias("X-222227JR7C"))
    }

    @Test
    fun resolveCanonicalSensorId_normalizesManagedIdsToUppercase() {
        assertEquals(
            "X-222227JR7C",
            AiDexManagedSensorIdentityAdapter.resolveCanonicalSensorId("x-222227jr7c")
        )
    }

    @Test
    fun matchesCallbackId_acceptsNativeAliasForManagedCallback() {
        assertTrue(
            AiDexManagedSensorIdentityAdapter.matchesCallbackId("X-222227JR7C", "222227JR7C")
        )
    }
}
