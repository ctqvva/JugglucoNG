package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ICanHealthConstantsExtraTests {

    @Test
    fun bundledKeyFamilyUsesHexSelectorAtOffsetTen() {
        assertTrue(ICanHealthConstants.usesNewBundledCrypto("AAAAAAAAAA05"))
        assertFalse(ICanHealthConstants.usesNewBundledCrypto("AAAAAAAAAA03"))
        assertFalse(ICanHealthConstants.usesNewBundledCrypto("too-short"))
        assertFalse(ICanHealthConstants.usesNewBundledCrypto(null))
    }

    @Test
    fun launcherSerialTakesPrecedenceOverSoftwareVersion() {
        assertTrue(ICanHealthConstants.usesNewBundledCrypto("AAAAAAAAAA05", "AAAAAAAAAA03"))
        assertFalse(ICanHealthConstants.usesNewBundledCrypto("AAAAAAAAAA03", "AAAAAAAAAA05"))
    }

    @Test
    fun bundledKeysFollowFamily() {
        assertEquals(
            ICanHealthConstants.DEFAULT_NEW_GLUCOSE_AES_KEY_ASCII,
            ICanHealthConstants.resolveBundledGlucoseKey("AAAAAAAAAA05"),
        )
        assertEquals(
            ICanHealthConstants.DEFAULT_OLD_GLUCOSE_AES_KEY_ASCII,
            ICanHealthConstants.resolveBundledGlucoseKey("AAAAAAAAAA03"),
        )
    }

    @Test
    fun configuredAesKeyPrefersExact16CharCandidate() {
        assertEquals("0123456789abcdef", ICanHealthConstants.resolveConfiguredAesKey("0123456789abcdef"))
        assertEquals(
            ICanHealthConstants.resolveBundledGlucoseKey(null, null),
            ICanHealthConstants.resolveConfiguredAesKey("short"),
        )
    }

    @Test
    fun configuredAuthUserIdStripsWhitespaceAndControls() {
        assertEquals("abc", ICanHealthConstants.normalizeConfiguredAuthUserId(" a b\tc "))
        assertEquals("ab", ICanHealthConstants.normalizeConfiguredAuthUserId("a\u0001b"))
        assertEquals("", ICanHealthConstants.normalizeConfiguredAuthUserId(null))
    }

    @Test
    fun standaloneUserIdPadsOrTruncatesToTwelve() {
        assertEquals("123456789000", ICanHealthConstants.normalizeStandaloneUserId("123456789"))
        assertEquals("efghijklmnop", ICanHealthConstants.normalizeStandaloneUserId("abcdefghijklmnop"))
        assertEquals("000000000000", ICanHealthConstants.normalizeStandaloneUserId(null))
    }

    @Test
    fun racpBuildersUseModernPrefixAndClampOffset() {
        assertEquals(
            listOf(0xF1.toByte(), 0x01, 0x02),
            ICanHealthConstants.buildRacpReportAllGlucose().toList(),
        )
        assertEquals(
            listOf(0x01.toByte(), 0x01.toByte(), 0x02.toByte()),
            ICanHealthConstants.buildRacpReportAllGlucose(modernPrefix = false).toList(),
        )
        assertEquals(
            listOf(0xF1.toByte(), 0x03.toByte(), 0x02.toByte(), 0x01.toByte(), 0x34.toByte(), 0x12.toByte()),
            ICanHealthConstants.buildRacpReportFromGlucose(0x1234).toList(),
        )
        assertEquals(
            listOf(0xF1.toByte(), 0x03.toByte(), 0x01.toByte(), 0x01.toByte(), 0x01, 0x00),
            ICanHealthConstants.buildRacpReportFromOriginal(0).toList(),
        )
        assertEquals(
            listOf(0xF1.toByte(), 0x03.toByte(), 0x01.toByte(), 0x01.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            ICanHealthConstants.buildRacpReportFromOriginal(70_000).toList(),
        )
    }

    @Test
    fun launcherStateActivity() {
        assertTrue(ICanHealthConstants.isActiveLauncherState(ICanHealthConstants.LAUNCHER_STATE_RUNNING))
        assertTrue(ICanHealthConstants.isActiveLauncherState(ICanHealthConstants.LAUNCHER_STATE_WARMUP))
        assertFalse(ICanHealthConstants.isActiveLauncherState(ICanHealthConstants.LAUNCHER_STATE_ENDED))
    }

    @Test
    fun deviceNameDetection() {
        assertTrue(ICanHealthConstants.isICanHealthDevice("iCGM-x"))
        assertTrue(ICanHealthConstants.isICanHealthDevice("P123456789ABC"))
        assertTrue(ICanHealthConstants.isICanHealthDevice("abcdefghijkl"))
        assertFalse(ICanHealthConstants.isICanHealthDevice("nope"))
        assertFalse(ICanHealthConstants.isICanHealthDevice(null))
    }

    @Test
    fun persistedSensorNamePatterns() {
        assertTrue(ICanHealthConstants.isLikelyPersistedSensorName("ABCDEFGHIJKL"))
        assertTrue(ICanHealthConstants.isLikelyPersistedSensorName("LT123456AB"))
        assertFalse(ICanHealthConstants.isLikelyPersistedSensorName("ABC"))
        assertFalse(ICanHealthConstants.isLikelyPersistedSensorName(null))
    }

    @Test
    fun provisionalSensorIds() {
        assertTrue(ICanHealthConstants.isProvisionalSensorId("ICN-ABC"))
        assertTrue(ICanHealthConstants.isProvisionalSensorId("ican-abc"))
        assertFalse(ICanHealthConstants.isProvisionalSensorId("ABC"))
        assertFalse(ICanHealthConstants.isProvisionalSensorId(null))
    }

    @Test
    fun deriveInitialSensorIdPrefersNameThenSnThenAddress() {
        assertEquals("ABCDEFGHIJKLMNOP", ICanHealthConstants.deriveInitialSensorId("abcdefghijklmnop", ""))
        assertEquals("ICN-AABBCCDDEEFF", ICanHealthConstants.deriveInitialSensorId(null, "AA:BB:CC:DD:EE:FF"))
        assertEquals("ICN-X", ICanHealthConstants.deriveInitialSensorId("x", null, null))
    }

    @Test
    fun canonicalSensorIdUppercasesOnlyFullLengthAlnum() {
        assertEquals("ABCDEFGHIJKLMNOP", ICanHealthConstants.canonicalSensorId("abcdefghijklmnop"))
        assertEquals("short", ICanHealthConstants.canonicalSensorId("short"))
        assertEquals("", ICanHealthConstants.canonicalSensorId(null))
    }

    @Test
    fun nativeAliasAndLegacyBrokenAlias() {
        assertEquals("56789ABCDEF", ICanHealthConstants.nativeShortSensorAlias("0123456789ABCDEF"))
        assertEquals("ABCDEF", ICanHealthConstants.legacyBrokenNativeAlias("0123456789ABCDEF"))
        assertNull(ICanHealthConstants.nativeShortSensorAlias("short"))
    }

    @Test
    fun byteArrayToHexStringIsSpaceSeparatedUppercase() {
        assertEquals("", byteArrayOf().toHexString())
        assertEquals("00 0A FF", byteArrayOf(0, 0x0A, 0xFF.toByte()).toHexString())
    }
}
