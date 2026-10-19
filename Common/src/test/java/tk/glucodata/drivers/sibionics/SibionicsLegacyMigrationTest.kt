package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SibionicsLegacyMigrationTest {
    @Test
    fun `sibionics 2 migration preserves identity and legacy defaults`() {
        val candidate = SibionicsLegacyMigration.LegacySnapshot(
            nativeName = "0683013AQT9",
            address = "e0:63:0d:82:9c:9e",
            subtype = 3,
            shortCode = "0683013A",
            bleName = "P225043JMV",
            startTimeMs = 1_784_000_000_000L,
            viewMode = 2,
            autoResetDays = 0,
        ).run { SibionicsLegacyMigration.run { toCandidate() } }

        requireNotNull(candidate)
        assertEquals(SibionicsConstants.Variant.SIBIONICS2, candidate.variant)
        assertEquals("0683013AQT9", candidate.nativeName)
        assertEquals("E0:63:0D:82:9C:9E", candidate.address)
        assertEquals("0683013A", candidate.shortCode)
        assertEquals("P225043JMV", candidate.bleName)
        assertEquals(22, candidate.autoResetDays)
        assertEquals(SibionicsConstants.ProtocolMode.V120, candidate.protocolMode)
        assertEquals(0L, candidate.managedStartTimeMs())
    }

    @Test
    fun `chinese migration uses chinese protocol and disabled auto reset`() {
        val candidate = SibionicsLegacyMigration.LegacySnapshot(
            nativeName = "46HU804EBJ4",
            address = null,
            subtype = 2,
            shortCode = "46HU804E",
            bleName = null,
            startTimeMs = -1L,
            viewMode = 99,
            autoResetDays = 0,
        ).run { SibionicsLegacyMigration.run { toCandidate() } }

        requireNotNull(candidate)
        assertEquals(SibionicsConstants.Variant.CHINESE, candidate.variant)
        assertEquals(SibionicsConstants.ProtocolMode.CHINESE, candidate.protocolMode)
        assertEquals(300, candidate.autoResetDays)
        assertEquals(0L, candidate.startTimeMs)
        assertEquals(0L, candidate.managedStartTimeMs())
        assertEquals(3, candidate.viewMode)
    }

    @Test
    fun `sibionics 2 migration preserves an explicit earlier reset day`() {
        val candidate = SibionicsLegacyMigration.LegacySnapshot(
            nativeName = "0683013AQT9",
            address = null,
            subtype = 3,
            shortCode = "0683013A",
            bleName = "P225043JMV",
            startTimeMs = 1_784_000_000_000L,
            viewMode = 0,
            autoResetDays = 9,
        ).run { SibionicsLegacyMigration.run { toCandidate() } }

        requireNotNull(candidate)
        assertEquals(9, candidate.autoResetDays)
    }

    @Test
    fun `gs3 and already managed shells are not migrated`() {
        val gs3 = snapshot(name = "GS3ABC123", subtype = 4)
        val managed = snapshot(name = "SIBI:46HU804EBJ4", subtype = 2)

        assertNull(gs3.run { SibionicsLegacyMigration.run { toCandidate() } })
        assertNull(managed.run { SibionicsLegacyMigration.run { toCandidate() } })
    }

    @Test
    fun `unknown subtype is not guessed as eu`() {
        assertNull(
            snapshot(name = "UNKNOWN123", subtype = 99)
                .run { SibionicsLegacyMigration.run { toCandidate() } },
        )
    }

    private fun snapshot(name: String, subtype: Int) = SibionicsLegacyMigration.LegacySnapshot(
        nativeName = name,
        address = null,
        subtype = subtype,
        shortCode = null,
        bleName = null,
        startTimeMs = 0L,
        viewMode = 0,
        autoResetDays = 300,
    )
}
