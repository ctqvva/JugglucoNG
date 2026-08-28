package tk.glucodata.drivers.aidex

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This gate gates a probe that writes `0xF3` to a sensor, so the false-positive cases below carry
 * more weight than the recognition cases.
 */
class AiDexFGenerationAdvertisementTests {

    @Test
    fun recognisesTheObservedAdvertisementForm() {
        assertTrue(AiDexSerialIdentity.isFGenerationAdvertisement("AiDEX F-22222FZXKT"))
    }

    @Test
    fun recognisesBarePrefixedForm() {
        assertTrue(AiDexSerialIdentity.isFGenerationAdvertisement("F-22222FZXKT"))
    }

    @Test
    fun isCaseInsensitive() {
        assertTrue(AiDexSerialIdentity.isFGenerationAdvertisement("aidex f-22222fzxkt"))
    }

    @Test
    fun toleratesSurroundingWhitespace() {
        assertTrue(AiDexSerialIdentity.isFGenerationAdvertisement("  AiDEX F-22222FZXKT  "))
    }

    @Test
    fun rejectsXGeneration() {
        assertFalse(AiDexSerialIdentity.isFGenerationAdvertisement("AiDEX X-2222267V4E"))
        assertFalse(AiDexSerialIdentity.isFGenerationAdvertisement("X-2222267V4E"))
    }

    @Test
    fun rejectsSerialsThatMerelyStartWithF() {
        // Without the required separator this would otherwise read "F" + "ZXKT12345678".
        assertFalse(AiDexSerialIdentity.isFGenerationAdvertisement("FZXKT12345678"))
    }

    @Test
    fun rejectsBlankAndNullNames() {
        assertFalse(AiDexSerialIdentity.isFGenerationAdvertisement(null))
        assertFalse(AiDexSerialIdentity.isFGenerationAdvertisement(""))
        assertFalse(AiDexSerialIdentity.isFGenerationAdvertisement("   "))
    }

    @Test
    fun rejectsUnrelatedDeviceNames() {
        assertFalse(AiDexSerialIdentity.isFGenerationAdvertisement("SIBI:0683013AQT9"))
        assertFalse(AiDexSerialIdentity.isFGenerationAdvertisement("Pixel Buds"))
        assertFalse(AiDexSerialIdentity.isFGenerationAdvertisement("F-123"))
    }

    @Test
    fun stillCanonicalisesFGenerationIntoTheXNamespace() {
        // The gate must not disturb the single stored identity namespace.
        assertTrue(
            AiDexSerialIdentity.canonicalFromAdvertisement("AiDEX F-22222FZXKT") == "X-22222FZXKT"
        )
    }
}
