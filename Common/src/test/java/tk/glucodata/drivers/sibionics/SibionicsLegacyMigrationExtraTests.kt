package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SibionicsLegacyMigrationExtraTests {

    private fun candidate(
        name: String = "0683013AQT9",
        address: String? = null,
        subtype: Int = 3,
        shortCode: String? = null,
        bleName: String? = null,
        startTimeMs: Long = 0L,
        viewMode: Int = 0,
        autoResetDays: Int = 0,
    ) = SibionicsLegacyMigration.LegacySnapshot(
        nativeName = name,
        address = address,
        subtype = subtype,
        shortCode = shortCode,
        bleName = bleName,
        startTimeMs = startTimeMs,
        viewMode = viewMode,
        autoResetDays = autoResetDays,
    ).run { SibionicsLegacyMigration.run { toCandidate() } }

    @Test
    fun blankAndManagedNamesAreRejected() {
        assertNull(candidate(name = ""))
        assertNull(candidate(name = "   "))
        assertNull(candidate(name = "sibi:0683013AQT9"))
    }

    @Test
    fun malformedShortCodeAndBleNameBecomeNull() {
        val short = candidate(shortCode = "123")
        assertEquals(null, short?.shortCode)
        val longName = candidate(bleName = "AB")
        assertEquals(null, longName?.bleName)
        val tooLong = candidate(bleName = "P225043JMV1234567")
        assertEquals(null, tooLong?.bleName)
    }

    @Test
    fun sibionics2KeepsDisabledResetSentinel() {
        assertEquals(SibionicsResetPolicy.DISABLED_DAYS, candidate(autoResetDays = SibionicsResetPolicy.DISABLED_DAYS)?.autoResetDays)
        assertEquals(SibionicsResetPolicy.ENABLED_DAYS, candidate(autoResetDays = SibionicsResetPolicy.ENABLED_DAYS)?.autoResetDays)
    }

    @Test
    fun nonChineseVariantsUseV120Protocol() {
        val eu = candidate(subtype = 0, address = "e0630d829c9e")
        assertEquals(SibionicsConstants.Variant.EU, eu?.variant)
        assertEquals(SibionicsConstants.ProtocolMode.V120, eu?.protocolMode)
        assertEquals("E0:63:0D:82:9C:9E", eu?.address)

        val hematonix = candidate(subtype = 1)
        assertEquals(SibionicsConstants.Variant.HEMATONIX, hematonix?.variant)
        assertEquals(SibionicsConstants.ProtocolMode.V120, hematonix?.protocolMode)
    }

    @Test
    fun addressIsNullForGarbageAndStartTimeNeverNegative() {
        assertNull(candidate(address = "not-an-address")?.address)
        assertEquals(0L, candidate(startTimeMs = -500L)?.startTimeMs)
    }
}
