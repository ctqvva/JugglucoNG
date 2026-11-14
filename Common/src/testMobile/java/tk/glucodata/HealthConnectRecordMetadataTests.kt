package tk.glucodata

import androidx.health.connect.client.records.metadata.Metadata
import org.junit.Assert.*
import org.junit.Test

class HealthConnectRecordMetadataTests {
    @Test fun recoveredMinuteKeepsItsIdAcrossRetriesAndTimestampJitter() {
        val base = Metadata.unknownRecordingMethod()
        val first = HealthConnectRecordMetadata.forReading(base, "libre3:serial:1000:", 1000, 1120, 10)
        val retried = HealthConnectRecordMetadata.forReading(base, "libre3:serial:1000:", 1000, 1150, 20)
        assertEquals(first.clientRecordId, retried.clientRecordId)
        assertEquals("libre3:serial:1000:2", first.clientRecordId)
        assertTrue(retried.clientRecordVersion > first.clientRecordVersion)
        val next = HealthConnectRecordMetadata.forReading(base, "libre3:serial:1000:", 1000, 1180, 20)
        assertNotEquals(first.clientRecordId, next.clientRecordId)
        val replacement = HealthConnectRecordMetadata.forReading(base, "libre3:serial:2000:", 2000, 2120, 20)
        assertNotEquals(first.clientRecordId, replacement.clientRecordId)
    }

    @Test fun otherFamiliesKeepTheirExistingMetadata() {
        val base = Metadata.unknownRecordingMethod()
        assertSame(base, HealthConnectRecordMetadata.forReading(base, null, 1000, 1120, 20))
    }
}
