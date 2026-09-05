package tk.glucodata.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Reads and writes [ReadingDisplay] rows. */
@Dao
interface ReadingDisplayDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ReadingDisplay>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoring(rows: List<ReadingDisplay>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: ReadingDisplay)

    @Query(
        "SELECT * FROM reading_display WHERE sensorSerial IN (:serials) " +
            "AND timestamp >= :startTime ORDER BY timestamp ASC"
    )
    suspend fun getForSensors(serials: List<String>, startTime: Long): List<ReadingDisplay>

    @Query("SELECT * FROM reading_display WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    fun getFlow(startTime: Long): Flow<List<ReadingDisplay>>

    @Query(
        "SELECT * FROM reading_display WHERE sensorSerial IN (:serials) " +
            "AND timestamp >= :startTime ORDER BY timestamp ASC"
    )
    fun getFlowForSensors(serials: List<String>, startTime: Long): Flow<List<ReadingDisplay>>

    @Query("SELECT * FROM reading_display WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    suspend fun getAllSince(startTime: Long): List<ReadingDisplay>

    @Query("SELECT COUNT(*) FROM reading_display")
    suspend fun getCount(): Int

    @Query("DELETE FROM reading_display WHERE sensorSerial IN (:serials)")
    suspend fun deleteForSensors(serials: List<String>)

    @Query("DELETE FROM reading_display")
    suspend fun deleteAll()

    @Query(
        """
        SELECT * FROM reading_display display
        WHERE (
                display.timestamp > :afterTimestamp
                OR (display.timestamp = :afterTimestamp
                    AND display.sensorSerial > :afterSensorSerial)
              )
          AND EXISTS (
              SELECT 1 FROM history_readings reading
              WHERE reading.sensorSerial = display.sensorSerial
                AND reading.timestamp = display.timestamp
          )
        ORDER BY display.timestamp ASC, display.sensorSerial ASC
        LIMIT :limit
        """
    )
    suspend fun getRecoveryPage(
        afterTimestamp: Long,
        afterSensorSerial: String,
        limit: Int,
    ): List<ReadingDisplay>

    @Query("DELETE FROM reading_display WHERE sensorSerial = :serial AND timestamp > :timestamp")
    suspend fun deleteForSensorAfter(serial: String, timestamp: Long)

    @Query("DELETE FROM reading_display WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    /**
     * Drops the records a recompute is about to replace.
     *
     * Only the unsealed ones, unless the caller explicitly asks to go further
     * back — rewriting sealed history is a deliberate act, never a side effect
     * of flipping a switch.
     */
    @Query(
        "DELETE FROM reading_display WHERE sensorSerial IN (:serials) AND timestamp >= :fromTimestamp"
    )
    suspend fun deleteForSensorsFrom(serials: List<String>, fromTimestamp: Long)
}
