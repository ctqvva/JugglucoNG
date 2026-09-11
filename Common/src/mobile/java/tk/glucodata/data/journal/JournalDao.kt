package tk.glucodata.data.journal

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface JournalDao {
    @Query("SELECT * FROM journal_entries ORDER BY timestamp DESC, id DESC")
    fun observeEntries(): Flow<List<JournalEntryEntity>>

    @Query("SELECT * FROM journal_entries WHERE id = :id LIMIT 1")
    suspend fun getEntryById(id: Long): JournalEntryEntity?

    @Query("SELECT * FROM journal_entries WHERE sourceRecordId = :sourceRecordId LIMIT 1")
    suspend fun getEntryBySourceRecordId(sourceRecordId: String): JournalEntryEntity?

    @Query("SELECT * FROM journal_entries ORDER BY timestamp ASC, id ASC")
    suspend fun getEntries(): List<JournalEntryEntity>

    @Query("SELECT * FROM journal_entries WHERE timestamp BETWEEN :startMillis AND :endMillis ORDER BY timestamp ASC, id ASC")
    suspend fun getEntriesBetween(startMillis: Long, endMillis: Long): List<JournalEntryEntity>

    @Query(
        "SELECT * FROM journal_entries WHERE source = :source AND timestamp BETWEEN :startMillis AND :endMillis " +
            "ORDER BY timestamp ASC, id ASC"
    )
    suspend fun getEntriesBySourceBetween(
        source: String,
        startMillis: Long,
        endMillis: Long
    ): List<JournalEntryEntity>

    @Query(
        "SELECT * FROM journal_entries WHERE glucoseValueMgDl IS NOT NULL AND timestamp >= :startMillis " +
            "ORDER BY timestamp ASC, id ASC"
    )
    suspend fun getGlucoseEntriesSince(startMillis: Long): List<JournalEntryEntity>

    @Query("SELECT COUNT(*) FROM journal_entries WHERE entryType = :entryType")
    suspend fun countEntriesByType(entryType: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(entry: JournalEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntries(entries: List<JournalEntryEntity>)

    @Delete
    suspend fun deleteEntry(entry: JournalEntryEntity)

    @Query("DELETE FROM journal_entries WHERE id = :id")
    suspend fun deleteEntryById(id: Long)

    @Query("DELETE FROM journal_entries WHERE sourceRecordId IN (:sourceRecordIds)")
    suspend fun deleteEntriesBySourceRecordIds(sourceRecordIds: List<String>)

    @Query("DELETE FROM journal_entries")
    suspend fun deleteAllEntries()

    @Query("SELECT * FROM journal_insulin_presets ORDER BY isArchived ASC, sortOrder ASC, displayName COLLATE NOCASE ASC")
    fun observeInsulinPresets(): Flow<List<JournalInsulinPresetEntity>>

    @Query("SELECT * FROM journal_insulin_presets WHERE id = :id LIMIT 1")
    suspend fun getInsulinPresetById(id: Long): JournalInsulinPresetEntity?

    @Query("SELECT * FROM journal_insulin_presets")
    suspend fun getInsulinPresets(): List<JournalInsulinPresetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInsulinPreset(preset: JournalInsulinPresetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInsulinPresets(presets: List<JournalInsulinPresetEntity>)

    @Query("DELETE FROM journal_insulin_presets")
    suspend fun deleteAllInsulinPresets()

    @Query("SELECT COUNT(*) FROM journal_insulin_presets")
    suspend fun countInsulinPresets(): Int

    @Query("DELETE FROM journal_insulin_presets WHERE id = :id")
    suspend fun deleteInsulinPresetById(id: Long)

    @Query("SELECT * FROM journal_foods ORDER BY isArchived ASC, sortOrder ASC, displayName COLLATE NOCASE ASC")
    fun observeFoods(): Flow<List<JournalFoodEntity>>

    @Query("SELECT * FROM journal_foods WHERE id = :id LIMIT 1")
    suspend fun getFoodById(id: Long): JournalFoodEntity?

    @Query("SELECT * FROM journal_foods")
    suspend fun getFoods(): List<JournalFoodEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFood(food: JournalFoodEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFoods(foods: List<JournalFoodEntity>)

    @Query("DELETE FROM journal_foods")
    suspend fun deleteAllFoods()

    @Query("SELECT COUNT(*) FROM journal_foods")
    suspend fun countFoods(): Int

    @Query("DELETE FROM journal_foods WHERE id = :id")
    suspend fun deleteFoodById(id: Long)

    @Query(
        """
        SELECT * FROM journal_entries
         WHERE timestamp >= :sinceMillis
           AND (nsUploadedAt IS NULL OR updatedAt > nsUploadedAt)
         ORDER BY timestamp ASC, id ASC
        """
    )
    suspend fun getEntriesNeedingNightscoutUpload(sinceMillis: Long): List<JournalEntryEntity>

    @Query("UPDATE journal_entries SET nsUploadedAt = :uploadedAt, nsRemoteId = :remoteId WHERE id = :id")
    suspend fun markEntryUploadedToNightscout(id: Long, remoteId: String, uploadedAt: Long)

    @Query("SELECT nsRemoteId FROM journal_entries WHERE nsUploadedAt IS NOT NULL AND nsRemoteId IS NOT NULL")
    suspend fun getOwnUploadedNightscoutRemoteIds(): List<String>

    @Query(
        """
        SELECT * FROM journal_entries
         WHERE timestamp >= :sinceMillis
           AND (lvUploadedAt IS NULL OR updatedAt > lvUploadedAt)
         ORDER BY timestamp ASC, id ASC
        """
    )
    suspend fun getEntriesNeedingLibreviewUpload(sinceMillis: Long): List<JournalEntryEntity>

    @Query("UPDATE journal_entries SET lvUploadedAt = :uploadedAt WHERE id IN (:ids)")
    suspend fun markEntriesUploadedToLibreview(ids: List<Long>, uploadedAt: Long)

    @Query("SELECT * FROM journal_pending_deletes ORDER BY deletedAt ASC")
    suspend fun getPendingNightscoutDeletes(): List<JournalPendingDeleteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueuePendingNightscoutDelete(tombstone: JournalPendingDeleteEntity)

    @Query("DELETE FROM journal_pending_deletes WHERE entryId = :entryId")
    suspend fun clearPendingNightscoutDelete(entryId: Long)

    @Query(
        "UPDATE journal_pending_deletes SET attempts = :attempts, lastAttemptAt = :attemptedAt " +
            "WHERE entryId = :entryId"
    )
    suspend fun recordFailedNightscoutDelete(entryId: Long, attempts: Int, attemptedAt: Long)
}
