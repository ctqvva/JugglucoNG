package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.GlucoseReadingSource

class ExportPackageProvenanceTests {
    @Test
    fun overlappingRestoreKeepsTheLocallyStoredProvenance() {
        val existing = reading(
            id = 7,
            source = GlucoseReadingSource.CLONE_LOCAL_ICE,
            firstStoredAt = 1_000L,
        )
        val backup = reading(
            id = 0,
            source = GlucoseReadingSource.NIGHTSCOUT,
            firstStoredAt = 9_000L,
        )

        val restored = ExportPackageExporter.preserveImportedHistoryProvenance(
            incoming = listOf(backup),
            existingByKey = mapOf((existing.sensorSerial to existing.timestamp) to existing),
        ).single()

        assertEquals(GlucoseReadingSource.CLONE_LOCAL_ICE, restored.source)
        assertEquals(1_000L, restored.firstStoredAt)
        assertEquals(backup.value, restored.value)
    }

    private fun reading(id: Long, source: String, firstStoredAt: Long) = HistoryReading(
        id = id,
        timestamp = 123_000L,
        sensorSerial = "TEST-SENSOR",
        value = 112f,
        rawValue = 111f,
        rate = null,
        source = source,
        firstStoredAt = firstStoredAt,
    )
}
