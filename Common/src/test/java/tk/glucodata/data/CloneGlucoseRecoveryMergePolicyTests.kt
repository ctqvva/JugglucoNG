package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.CloneRecoveryMode
import tk.glucodata.GlucoseReadingSource

class CloneGlucoseRecoveryMergePolicyTests {
    @Test
    fun onlyMissingDoesNotApplyRemoteDeletionOverExistingReading() {
        val tombstone = DeletedHistoryReading(
            timestamp = 1_000_000L,
            sensorSerial = "test-sensor-alpha",
            deletedAt = 2_000_000L,
        )

        val selected = CloneGlucoseRecoveryMergePolicy.deletedReadingsToInsert(
            mode = CloneRecoveryMode.ONLY_MISSING,
            rows = listOf(tombstone),
            existingReadingKeys = setOf(tombstone.sensorSerial to tombstone.timestamp),
        )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun fullHistoryKeepsDeletionMarkersForAuthoritativeReplacement() {
        val tombstone = DeletedHistoryReading(
            timestamp = 1_000_000L,
            sensorSerial = "test-sensor-alpha",
            deletedAt = 2_000_000L,
        )

        val selected = CloneGlucoseRecoveryMergePolicy.deletedReadingsToInsert(
            mode = CloneRecoveryMode.FULL_HISTORY,
            rows = listOf(tombstone),
            existingReadingKeys = setOf(tombstone.sensorSerial to tombstone.timestamp),
        )

        assertEquals(listOf(tombstone), selected)
    }

    @Test
    fun tombstoneWinsOverIncomingReadingAndRecoveryRouteIsRecorded() {
        val retained = reading("test-sensor-retained", 1_000_000L)
        val deleted = reading("test-sensor-deleted", 2_000_000L)

        val selected = CloneGlucoseRecoveryMergePolicy.readingsToInsert(
            rows = listOf(retained, deleted),
            deletedReadingKeys = setOf(deleted.sensorSerial to deleted.timestamp),
            recoverySource = GlucoseReadingSource.CLONE_TURN,
        )

        assertEquals(listOf(retained.copy(source = GlucoseReadingSource.CLONE_TURN)), selected)
    }

    @Test
    fun metadataWithoutARecoveredOrExistingReadingIsDropped() {
        val uncertainty = ReadingUncertainty(
            timestamp = 1_020_000L,
            sensorSerial = "test-sensor-alpha",
            lowerMgdl = 90f,
            upperMgdl = 110f,
            intervalMass = 0.9f,
            confidence = null,
            artifactProbability = null,
        )
        val display = ReadingDisplay(
            timestamp = 1_023_000L,
            sensorSerial = "test-sensor-alpha",
            displayMgdl = 100f,
            viewMode = 0,
            calibrationFingerprint = 1L,
            recordedAt = 1_100_000L,
        )

        assertTrue(
            CloneGlucoseRecoveryMergePolicy.uncertaintyToInsert(
                rows = listOf(uncertainty),
                existingReadingMinuteKeys = emptySet(),
            ).isEmpty(),
        )
        assertTrue(
            CloneGlucoseRecoveryMergePolicy.displayToInsert(
                rows = listOf(display),
                existingReadingKeys = emptySet(),
            ).isEmpty(),
        )
    }

    private fun reading(sensorSerial: String, timestamp: Long) = HistoryReading(
        timestamp = timestamp,
        sensorSerial = sensorSerial,
        value = 100f,
        rawValue = 101f,
        rate = 0.5f,
        source = GlucoseReadingSource.SENSOR,
        firstStoredAt = timestamp + 1_000L,
    )
}
