package tk.glucodata.drivers.aidex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDexManagedSensorIdentityAdapterTests {

    @Test
    fun advertisedGenerationName_usesCanonicalXIdentityRegardlessOfLetter() {
        assertEquals(
            "X-22222FZXKT",
            AiDexSerialIdentity.canonicalFromAdvertisement("AiDEX F-22222FZXKT")
        )
        assertEquals("X-22222FZXKT", AiDexSerialIdentity.canonicalFromAdvertisement("F-22222FZXKT"))
        assertEquals("X-22222GZXKT", AiDexSerialIdentity.canonicalFromAdvertisement("AiDEX G-22222GZXKT"))
        assertEquals("X-22222QZXKT", AiDexSerialIdentity.canonicalFromAdvertisement("Q-22222QZXKT"))
        assertEquals(
            "X-2222267V4E",
            AiDexSerialIdentity.canonicalFromAdvertisement("AiDEX sensor X-2222267V4E")
        )
    }

    @Test
    fun bareSerial_stripsAnyAdvertisedGenerationPrefix() {
        assertEquals("22222FZXKT", AiDexSerialIdentity.bareSerial("AiDEX F-22222FZXKT"))
        assertEquals("22222GZXKT", AiDexSerialIdentity.bareSerial("G-22222GZXKT"))
        assertEquals("22222QZXKT", AiDexSerialIdentity.bareSerial("AiDEX Q-22222QZXKT"))
        assertEquals("ABCDEFGHJK", AiDexSerialIdentity.bareSerial("ABCDEFGHJK"))
    }

    @Test
    fun smartAdvertisement_usesRealSerialForCanonicalAndProtocolIdentity() {
        for (name in listOf("Smart-22222BZTJ3", "SMART-22222BZTJ3", " smart-22222bztj3 ")) {
            assertEquals("X-22222BZTJ3", AiDexSerialIdentity.canonicalFromAdvertisement(name))
            assertEquals("22222BZTJ3", AiDexSerialIdentity.bareSerial(name))
        }
    }

    @Test
    fun smartAdvertisement_rejectsMalformedSerialsAndUnknownWordPrefixes() {
        for (name in listOf(
            "Smart-1234567",
            "Smart-123456789012345",
            "Smart-22222BZTJ3!",
            "Smart-22222BZTJ3-extra",
            "Smartwatch-22222BZTJ3",
            "Unknown-22222BZTJ3",
        )) {
            assertNull(name, AiDexSerialIdentity.canonicalFromAdvertisement(name))
        }
    }

    @Test
    fun smartMacFallback_recoversProtocolSerialWithoutRenamingStoredIdentity() {
        val storedSensorId = AiDexSerialIdentity.fallbackCanonicalFromAddress("6C:A0:42:3B:65:E2")
        assertEquals("X-6CA0423B65E2", storedSensorId)
        assertEquals(
            "22222BZTJ3",
            AiDexSerialIdentity.advertisedProtocolSerialForMacFallback(
                storedSensorId = storedSensorId,
                address = "6C:A0:42:3B:65:E2",
                advertisedName = "Smart-22222BZTJ3",
            )
        )
    }

    @Test
    fun smartMacFallback_doesNotOverrideRealSerialOrDifferentAddress() {
        for (storedSensorId in listOf("X-2222267V4E", "X-6CA0423B65E3")) {
            assertNull(
                AiDexSerialIdentity.advertisedProtocolSerialForMacFallback(
                    storedSensorId = storedSensorId,
                    address = "6C:A0:42:3B:65:E2",
                    advertisedName = "Smart-22222BZTJ3",
                )
            )
        }
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
