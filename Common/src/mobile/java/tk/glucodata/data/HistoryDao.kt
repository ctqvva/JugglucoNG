package tk.glucodata.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for glucose history readings.
 * Multi-sensor: queries can filter by sensorSerial or return all sensors.
 */
@Dao
interface HistoryDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reading: HistoryReading)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(readings: List<HistoryReading>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoring(readings: List<HistoryReading>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDeletedReadings(readings: List<DeletedHistoryReading>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDeletedReadingsForRecovery(
        readings: List<DeletedHistoryReading>,
    ): List<Long>

    // ── Per-sensor queries (used for dashboard, chart, current reading) ──

    @Query("SELECT * FROM history_readings WHERE sensorSerial = :serial AND timestamp >= :startTime ORDER BY timestamp ASC")
    fun getHistoryFlowForSensor(serial: String, startTime: Long): Flow<List<HistoryReading>>

    @Query("SELECT * FROM history_readings WHERE sensorSerial = :serial AND timestamp >= :startTime ORDER BY timestamp ASC")
    suspend fun getReadingsSinceForSensor(serial: String, startTime: Long): List<HistoryReading>

    @Query("SELECT * FROM history_readings WHERE sensorSerial IN (:serials) AND timestamp >= :startTime ORDER BY timestamp ASC")
    fun getHistoryFlowForSensors(serials: List<String>, startTime: Long): Flow<List<HistoryReading>>

    @Query("SELECT * FROM history_readings WHERE sensorSerial IN (:serials) AND timestamp >= :startTime ORDER BY timestamp ASC")
    suspend fun getReadingsSinceForSensors(serials: List<String>, startTime: Long): List<HistoryReading>

    @Query("""
        SELECT * FROM history_readings
        WHERE sensorSerial = :sensorSerial
          AND timestamp >= :startTimeInclusive
          AND timestamp < :endTimeExclusive
        ORDER BY timestamp ASC
    """)
    suspend fun getSensorReadingsInTimeRange(
        sensorSerial: String,
        startTimeInclusive: Long,
        endTimeExclusive: Long
    ): List<HistoryReading>

    @Query("SELECT * FROM history_readings WHERE sensorSerial = :sensorSerial AND timestamp IN (:timestamps)")
    suspend fun getSensorReadingsAtTimestamps(
        sensorSerial: String,
        timestamps: List<Long>
    ): List<HistoryReading>

    @Query("""
        SELECT timestamp FROM history_readings
        WHERE sensorSerial IN (:serials)
          AND timestamp >= :startTime
          AND timestamp <= :endTime
        ORDER BY timestamp ASC
    """)
    suspend fun getTimestampsForSensors(
        serials: List<String>,
        startTime: Long,
        endTime: Long
    ): List<Long>

    @Query("SELECT * FROM history_readings WHERE sensorSerial = :serial ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestReadingForSensor(serial: String): HistoryReading?

    @Query("SELECT * FROM history_readings WHERE sensorSerial = :serial ORDER BY timestamp DESC LIMIT 1")
    fun getLatestReadingFlowForSensor(serial: String): Flow<HistoryReading?>

    @Query("SELECT * FROM history_readings WHERE sensorSerial IN (:serials) ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestReadingForSensors(serials: List<String>): HistoryReading?

    @Query("SELECT * FROM history_readings WHERE sensorSerial IN (:serials) ORDER BY timestamp DESC LIMIT 1")
    fun getLatestReadingFlowForSensors(serials: List<String>): Flow<HistoryReading?>

    @Query("SELECT COUNT(*) FROM history_readings WHERE sensorSerial = :serial")
    suspend fun getCountForSensor(serial: String): Int

    @Query("SELECT MIN(timestamp) FROM history_readings WHERE sensorSerial = :serial")
    suspend fun getOldestTimestampForSensor(serial: String): Long?

    @Query("SELECT COUNT(*) FROM history_readings WHERE sensorSerial IN (:serials)")
    suspend fun getCountForSensors(serials: List<String>): Int

    @Query("SELECT MIN(timestamp) FROM history_readings WHERE sensorSerial IN (:serials)")
    suspend fun getOldestTimestampForSensors(serials: List<String>): Long?

    // ── All-sensor queries (used for export, global count, migration) ──

    @Query("SELECT * FROM history_readings WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    fun getHistoryFlow(startTime: Long): Flow<List<HistoryReading>>
    
    @Query("SELECT * FROM history_readings WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    suspend fun getReadingsSince(startTime: Long): List<HistoryReading>

    @Query("""
        SELECT * FROM history_readings
        WHERE timestamp >= :startTime
          AND timestamp <= :endTime
        ORDER BY timestamp ASC
    """)
    suspend fun getReadingsBetween(startTime: Long, endTime: Long): List<HistoryReading>
    
    @Query("SELECT * FROM history_readings ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestReading(): HistoryReading?

    @Query("SELECT * FROM history_readings ORDER BY timestamp DESC LIMIT 1")
    fun getLatestReadingFlow(): Flow<HistoryReading?>
    
    @Query("SELECT COUNT(*) FROM history_readings")
    suspend fun getCount(): Int
    
    @Query("SELECT MIN(timestamp) FROM history_readings")
    suspend fun getOldestTimestamp(): Long?

    @Query("SELECT DISTINCT sensorSerial FROM history_readings")
    suspend fun getAllSensorSerials(): List<String>

    @Query(
        """
        SELECT * FROM history_readings
        WHERE id > :afterId
          AND NOT EXISTS (
              SELECT 1 FROM history_deleted_readings deleted
              WHERE deleted.sensorSerial = history_readings.sensorSerial
                AND deleted.timestamp = history_readings.timestamp
          )
        ORDER BY id ASC
        LIMIT :limit
        """
    )
    suspend fun getRecoveryReadingsPage(afterId: Long, limit: Int): List<HistoryReading>

    @Query(
        """
        SELECT * FROM history_deleted_readings
        WHERE deletedAt > :afterDeletedAt
           OR (deletedAt = :afterDeletedAt AND timestamp > :afterTimestamp)
           OR (deletedAt = :afterDeletedAt AND timestamp = :afterTimestamp
               AND sensorSerial > :afterSensorSerial)
        ORDER BY deletedAt ASC, timestamp ASC, sensorSerial ASC
        LIMIT :limit
        """
    )
    suspend fun getRecoveryDeletedReadingsPage(
        afterDeletedAt: Long,
        afterTimestamp: Long,
        afterSensorSerial: String,
        limit: Int,
    ): List<DeletedHistoryReading>

    // ── Cleanup queries ──

    @Query("DELETE FROM history_readings WHERE sensorSerial = :serial")
    suspend fun deleteForSensor(serial: String)

    @Query("DELETE FROM history_readings")
    suspend fun deleteAllReadings()

    @Query("DELETE FROM history_deleted_readings")
    suspend fun deleteAllDeletedReadings()

    @Query("""
        DELETE FROM history_readings
        WHERE sensorSerial IN (:serials) AND timestamp = :timestamp
    """)
    suspend fun deleteReadingsAtTimestamp(serials: List<String>, timestamp: Long): Int

    @Query("""
        DELETE FROM history_readings
        WHERE sensorSerial IN (:serials) AND timestamp > :timestampExclusive
    """)
    suspend fun deleteReadingsForSensorsAfter(serials: List<String>, timestampExclusive: Long): Int

    @Query("""
        SELECT COUNT(*) FROM history_deleted_readings
        WHERE sensorSerial = :sensorSerial AND timestamp = :timestamp
    """)
    suspend fun isReadingDeleted(sensorSerial: String, timestamp: Long): Int

    @Query("""
        SELECT timestamp FROM history_deleted_readings
        WHERE sensorSerial = :sensorSerial AND timestamp IN (:timestamps)
    """)
    suspend fun getDeletedTimestampsForSensor(
        sensorSerial: String,
        timestamps: List<Long>
    ): List<Long>

    @Query(
        """
        SELECT DISTINCT (timestamp / 60000) * 60000 FROM history_readings
        WHERE sensorSerial = :sensorSerial
          AND (timestamp / 60000) * 60000 IN (:minuteTimestamps)
        """
    )
    suspend fun getExistingMinuteTimestampsForSensor(
        sensorSerial: String,
        minuteTimestamps: List<Long>,
    ): List<Long>

    // Deliberately absent: there is no query here that overwrites a stored
    // reading's `value` or `rawValue`. Those are what the sensor measured, and
    // the one caller that used to rewrite them — the calibration pass — is what
    // corrupted stores, because it fed on its own output. What a reading
    // *displays* is recorded in `reading_display` instead. If you find yourself
    // wanting an UPDATE here, you almost certainly want ReadingDisplayDao.

    @Query("""
        UPDATE history_readings SET sensorSerial = :newSerial 
        WHERE sensorSerial = :oldSerial
    """)
    suspend fun retagSensor(oldSerial: String, newSerial: String)

    @Query("""
        DELETE FROM history_readings
        WHERE sensorSerial = :sensorSerial
          AND timestamp >= :startTimeInclusive
          AND timestamp < :endTimeExclusive
    """)
    suspend fun deleteSensorRowsInTimeRange(
        sensorSerial: String,
        startTimeInclusive: Long,
        endTimeExclusive: Long
    ): Int
}
