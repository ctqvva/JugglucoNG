package tk.glucodata.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import tk.glucodata.CloneRecoveryRecord

class CloneGlucoseRecoveryRecordsTests {
    @Test
    fun readingRoundTripExcludesLocalDatabaseId() {
        val reading = HistoryReading(
            id = 73L,
            timestamp = 1_800_000L,
            sensorSerial = "test-sensor-alpha",
            value = 108.5f,
            rawValue = 110f,
            rate = -1.25f,
            source = "sensor",
            firstStoredAt = 1_900_000L,
        )

        val payload = CloneGlucoseRecoveryRecords.encode(reading)
        val decoded = CloneGlucoseRecoveryRecords.decodeReading(payload)

        assertEquals(0L, decoded.id)
        assertEquals(reading.copy(id = 0L), decoded)
        assertEquals(false, payload.has("id"))
    }

    @Test
    fun nullableReadingRateRoundTrips() {
        val reading = validReading().copy(rate = null)

        val decoded = CloneGlucoseRecoveryRecords.decodeReading(
            CloneGlucoseRecoveryRecords.encode(reading),
        )

        assertNull(decoded.rate)
    }

    @Test
    fun deletedReadingRoundTrips() {
        val deleted = DeletedHistoryReading(
            timestamp = 2_000_000L,
            sensorSerial = "test-sensor-deleted",
            deletedAt = 2_500_000L,
        )

        assertEquals(
            deleted,
            CloneGlucoseRecoveryRecords.decodeDeletedReading(
                CloneGlucoseRecoveryRecords.encode(deleted),
            ),
        )
    }

    @Test
    fun uncertaintyRoundTrips() {
        val uncertainty = ReadingUncertainty(
            timestamp = 3_000_000L,
            sensorSerial = "test-sensor-uncertainty",
            lowerMgdl = 92f,
            upperMgdl = 118f,
            intervalMass = 0.9f,
            confidence = 0.85f,
            artifactProbability = null,
        )

        assertEquals(
            uncertainty,
            CloneGlucoseRecoveryRecords.decodeUncertainty(
                CloneGlucoseRecoveryRecords.encode(uncertainty),
            ),
        )
    }

    @Test
    fun displayRoundTrips() {
        val display = ReadingDisplay(
            timestamp = 4_000_000L,
            sensorSerial = "test-sensor-display",
            displayMgdl = 121f,
            viewMode = 2,
            calibrationFingerprint = -42L,
            recordedAt = 4_100_000L,
        )

        assertEquals(
            display,
            CloneGlucoseRecoveryRecords.decodeDisplay(
                CloneGlucoseRecoveryRecords.encode(display),
            ),
        )
    }

    @Test
    fun unknownOrMissingFieldsAreRejected() {
        val withUnknown = CloneGlucoseRecoveryRecords.encode(validReading())
            .put("unexpected", true)
        val withoutSource = CloneGlucoseRecoveryRecords.encode(validReading())
            .apply { remove("source") }

        assertIllegalArgument { CloneGlucoseRecoveryRecords.decodeReading(withUnknown) }
        assertIllegalArgument { CloneGlucoseRecoveryRecords.decodeReading(withoutSource) }
    }

    @Test
    fun invalidValuesAndControlCharactersAreRejected() {
        val zeroValues = CloneGlucoseRecoveryRecords.encode(validReading())
            .put("valueMgDl", 0.0)
            .put("rawValueMgDl", 0.0)
        val controlCharacter = CloneGlucoseRecoveryRecords.encode(validReading())
            .put("sensorSerial", "test-sensor\u0000bad")
        val invalidProbability = CloneGlucoseRecoveryRecords.encode(validUncertainty())
            .put("artifactProbability", 1.1)

        assertIllegalArgument { CloneGlucoseRecoveryRecords.decodeReading(zeroValues) }
        assertIllegalArgument { CloneGlucoseRecoveryRecords.decodeReading(controlCharacter) }
        assertIllegalArgument {
            CloneGlucoseRecoveryRecords.decodeUncertainty(invalidProbability)
        }
    }

    @Test
    fun validatorRequiresTombstonesBeforeReadingsAndMetadata() {
        runBlocking {
            val validator = CloneGlucoseRecoveryRecords.orderedValidator()
            validator(
                CloneRecoveryRecord(
                    CloneGlucoseRecoveryRecords.READING,
                    CloneGlucoseRecoveryRecords.encode(validReading()),
                ),
            )

            assertIllegalArgumentSuspend {
                validator(
                    CloneRecoveryRecord(
                        CloneGlucoseRecoveryRecords.DELETED_READING,
                        CloneGlucoseRecoveryRecords.encode(
                            DeletedHistoryReading(1_000_000L, "test-sensor-beta", 2_000_000L),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun validatorRejectsNonGlucoseRecordTypes() {
        runBlocking {
            val validator = CloneGlucoseRecoveryRecords.orderedValidator()

            assertIllegalArgumentSuspend {
                validator(CloneRecoveryRecord("journal", JSONObject().put("value", 1)))
            }
        }
    }

    private fun validReading() = HistoryReading(
        timestamp = 1_000_000L,
        sensorSerial = "test-sensor-alpha",
        value = 100f,
        rawValue = 101f,
        rate = 0.5f,
        source = "clone_turn",
        firstStoredAt = 1_100_000L,
    )

    private fun validUncertainty() = ReadingUncertainty(
        timestamp = 1_000_000L,
        sensorSerial = "test-sensor-alpha",
        lowerMgdl = 90f,
        upperMgdl = 110f,
        intervalMass = 0.9f,
        confidence = null,
        artifactProbability = null,
    )

    private fun assertIllegalArgument(block: () -> Unit): IllegalArgumentException = try {
        block()
        fail("Expected IllegalArgumentException")
        throw AssertionError("unreachable")
    } catch (error: IllegalArgumentException) {
        error
    }

    private suspend fun assertIllegalArgumentSuspend(
        block: suspend () -> Unit,
    ): IllegalArgumentException = try {
        block()
        fail("Expected IllegalArgumentException")
        throw AssertionError("unreachable")
    } catch (error: IllegalArgumentException) {
        error
    }
}
