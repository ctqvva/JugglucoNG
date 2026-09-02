package tk.glucodata.data.journal

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import tk.glucodata.CloneRecoveryRecord

class CloneJournalRecoveryRecordsTests {
    @Test
    fun entryRoundTripExcludesLocalIdsAndUploadTimestamp() {
        val record = CloneJournalEntryRecord(
            recoveryId = TEST_RECOVERY_ID,
            legacyStableId = "clone:test-origin-alpha:journal:42:insulin",
            entry = entry(),
            insulinPreset = preset(),
            food = null,
        )

        val payload = CloneJournalRecoveryRecords.encode(record)
        val decoded = CloneJournalRecoveryRecords.decodeEntry(payload)

        assertEquals(record.recoveryId, decoded.recoveryId)
        assertEquals(record.legacyStableId, decoded.legacyStableId)
        assertEquals(
            record.entry.copy(
                id = 0L,
                insulinPresetId = null,
                nsUploadedAt = null,
            ),
            decoded.entry,
        )
        assertEquals(record.insulinPreset?.copy(id = 0L), decoded.insulinPreset)
        assertNull(decoded.food)
        assertFalse(payload.has("id"))
        assertFalse(payload.has("insulinPresetId"))
        assertFalse(payload.has("nsUploadedAt"))
    }

    @Test
    fun foodDependencyRoundTripsWithoutItsLocalId() {
        val record = CloneJournalEntryRecord(
            recoveryId = TEST_RECOVERY_ID,
            legacyStableId = "clone:test-origin-alpha:journal:51:carbs",
            entry = entry().copy(
                id = 51L,
                entryType = JournalEntryType.CARBS.storageValue,
                amount = 25f,
                insulinPresetId = null,
                foodId = 8L,
            ),
            insulinPreset = null,
            food = food(),
        )

        val decoded = CloneJournalRecoveryRecords.decodeEntry(
            CloneJournalRecoveryRecords.encode(record),
        )

        assertEquals(record.food?.copy(id = 0L), decoded.food)
        assertNull(decoded.entry.foodId)
    }

    @Test
    fun tombstoneRoundTripsAsStableBaseIdentity() {
        val tombstone = CloneJournalTombstoneRecord(
            recoveryId = TEST_RECOVERY_ID,
            legacyStableBaseId = "clone:test-origin-alpha:journal:42",
            deletedAt = 2_000_000L,
        )

        assertEquals(
            tombstone,
            CloneJournalRecoveryRecords.decodeTombstone(
                CloneJournalRecoveryRecords.encode(tombstone),
            ),
        )
    }

    @Test
    fun stableIdentityMustMatchEntryType() {
        val payload = CloneJournalRecoveryRecords.encode(
            CloneJournalEntryRecord(
                recoveryId = TEST_RECOVERY_ID,
                legacyStableId = "clone:test-origin-alpha:journal:42:insulin",
                entry = entry(),
                insulinPreset = preset(),
                food = null,
            ),
        ).put("entryType", JournalEntryType.NOTE.storageValue)

        assertIllegalArgument { CloneJournalRecoveryRecords.decodeEntry(payload) }
    }

    @Test
    fun malformedRecoveryIdentityIsRejected() {
        val entry = CloneJournalRecoveryRecords.encode(validRecord())
            .put("recoveryId", "not-a-recovery-id")
        val tombstone = CloneJournalRecoveryRecords.encode(
            CloneJournalTombstoneRecord(
                recoveryId = "not-a-recovery-id",
                legacyStableBaseId = "clone:test-origin-alpha:journal:42",
                deletedAt = 2_000_000L,
            )
        )

        assertIllegalArgument { CloneJournalRecoveryRecords.decodeEntry(entry) }
        assertIllegalArgument { CloneJournalRecoveryRecords.decodeTombstone(tombstone) }
    }

    @Test
    fun legacyTombstoneWithoutRecoveryIdentityRemainsReadable() {
        val tombstone = CloneJournalTombstoneRecord(
            recoveryId = null,
            legacyStableBaseId = "clone:test-origin-alpha:journal:42",
            deletedAt = 2_000_000L,
        )

        assertEquals(
            tombstone,
            CloneJournalRecoveryRecords.decodeTombstone(
                CloneJournalRecoveryRecords.encode(tombstone)
            ),
        )
    }

    @Test
    fun malformedFieldsAndDependenciesAreRejected() {
        val unknownField = CloneJournalRecoveryRecords.encode(validRecord())
            .put("unexpected", true)
        val invalidDuration = CloneJournalRecoveryRecords.encode(validRecord()).apply {
            getJSONObject("insulinPreset").put("durationMinutes", -1)
        }
        val invalidFloat = CloneJournalRecoveryRecords.encode(validRecord())
            .put("amount", "not-a-number")

        assertIllegalArgument { CloneJournalRecoveryRecords.decodeEntry(unknownField) }
        assertIllegalArgument { CloneJournalRecoveryRecords.decodeEntry(invalidDuration) }
        assertIllegalArgument { CloneJournalRecoveryRecords.decodeEntry(invalidFloat) }
    }

    @Test
    fun validatorRequiresTombstonesBeforeEntries() {
        runBlocking {
            val validator = CloneJournalRecoveryRecords.orderedValidator()
            validator(
                CloneRecoveryRecord(
                    CloneJournalRecoveryRecords.ENTRY,
                    CloneJournalRecoveryRecords.encode(validRecord()),
                ),
            )

            assertIllegalArgumentSuspend {
                validator(
                    CloneRecoveryRecord(
                        CloneJournalRecoveryRecords.TOMBSTONE,
                        CloneJournalRecoveryRecords.encode(
                            CloneJournalTombstoneRecord(
                                recoveryId = TEST_RECOVERY_ID,
                                legacyStableBaseId = "clone:test-origin-alpha:journal:42",
                                deletedAt = 2_000_000L,
                            ),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun validatorRejectsOtherCategoryRecords() {
        runBlocking {
            val validator = CloneJournalRecoveryRecords.orderedValidator()
            assertIllegalArgumentSuspend {
                validator(CloneRecoveryRecord("glucose_reading", JSONObject()))
            }
        }
    }

    private fun validRecord() = CloneJournalEntryRecord(
        recoveryId = TEST_RECOVERY_ID,
        legacyStableId = "clone:test-origin-alpha:journal:42:insulin",
        entry = entry(),
        insulinPreset = preset(),
        food = null,
    )

    private fun entry() = JournalEntryEntity(
        id = 42L,
        timestamp = 1_000_000L,
        sensorSerial = "test-sensor-journal",
        entryType = JournalEntryType.INSULIN.storageValue,
        title = "Test insulin",
        note = "Synthetic test entry",
        amount = 1.5f,
        glucoseValueMgDl = null,
        durationMinutes = null,
        intensity = null,
        insulinPresetId = 7L,
        foodId = null,
        proteinGrams = null,
        fatGrams = null,
        source = JournalEntrySource.PEN.storageValue,
        originSource = JournalEntrySource.PEN.storageValue,
        sourceRecordId = "test-pen-record",
        recoveryId = TEST_RECOVERY_ID,
        createdAt = 1_000_100L,
        updatedAt = 1_000_200L,
        nsUploadedAt = 1_000_300L,
        nsRemoteId = "test-nightscout-record",
    )

    private fun preset() = JournalInsulinPresetEntity(
        id = 7L,
        displayName = "Test rapid insulin",
        onsetMinutes = 10,
        durationMinutes = 300,
        accentColor = 0xFF123456.toInt(),
        curveJson = "0:0;30:1;300:0",
        isBuiltIn = false,
        isArchived = false,
        countsTowardIob = true,
        sortOrder = 20,
        useForCalculation = true,
    )

    private fun food() = JournalFoodEntity(
        id = 8L,
        displayName = "Test meal",
        carbsGrams = 25f,
        proteinGrams = 5f,
        fatGrams = 4f,
        absorptionMinutes = 120,
        accentColor = 0xFF654321.toInt(),
        isBuiltIn = false,
        isArchived = false,
        sortOrder = 30,
        createdAt = 900_000L,
        updatedAt = 950_000L,
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

    private companion object {
        const val TEST_RECOVERY_ID = "0123456789abcdef0123456789abcdef"
    }
}
