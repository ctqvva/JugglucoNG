package tk.glucodata.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A reading and its credible interval, returned by one query.
 *
 * The two live in separate tables, but the chart must never see one without
 * the other: combining two flows lets a new reading arrive before its interval
 * and draws a point with no band, which reads as the ribbon stopping short of
 * the live edge. A LEFT JOIN makes them atomic at the source.
 */
data class HistoryReadingWithUncertainty(
    val id: Long,
    val timestamp: Long,
    val sensorSerial: String,
    val value: Float,
    val rawValue: Float,
    val rate: Float?,
    val lowerMgdl: Float?,
    val upperMgdl: Float?,
    val intervalMass: Float?,
    val confidence: Float?,
    val artifactProbability: Float?,
)

/** Reads and writes [ReadingUncertainty] rows. */
@Dao
interface ReadingUncertaintyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ReadingUncertainty>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoring(rows: List<ReadingUncertainty>): List<Long>

    @Query(
        "SELECT * FROM reading_uncertainty WHERE sensorSerial IN (:serials) " +
            "AND timestamp >= :startTime ORDER BY timestamp ASC"
    )
    fun getFlowForSensors(serials: List<String>, startTime: Long): Flow<List<ReadingUncertainty>>

    @Query(
        "SELECT * FROM reading_uncertainty WHERE sensorSerial IN (:serials) " +
            "AND timestamp >= :startTime ORDER BY timestamp ASC"
    )
    suspend fun getForSensors(serials: List<String>, startTime: Long): List<ReadingUncertainty>

    @Query("SELECT * FROM reading_uncertainty WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    fun getFlow(startTime: Long): Flow<List<ReadingUncertainty>>

    @Query("DELETE FROM reading_uncertainty WHERE sensorSerial = :serial AND timestamp > :timestamp")
    suspend fun deleteForSensorAfter(serial: String, timestamp: Long)

    @Query("DELETE FROM reading_uncertainty WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM reading_uncertainty WHERE sensorSerial IN (:serials)")
    suspend fun deleteForSensors(serials: List<String>)

    @Query("DELETE FROM reading_uncertainty")
    suspend fun deleteAll()

    @Query(
        """
        SELECT * FROM reading_uncertainty uncertainty
        WHERE (
                uncertainty.timestamp > :afterTimestamp
                OR (uncertainty.timestamp = :afterTimestamp
                    AND uncertainty.sensorSerial > :afterSensorSerial)
              )
          AND EXISTS (
              SELECT 1 FROM history_readings reading
              WHERE reading.sensorSerial = uncertainty.sensorSerial
                AND (reading.timestamp / 60000) * 60000 = uncertainty.timestamp
          )
        ORDER BY uncertainty.timestamp ASC, uncertainty.sensorSerial ASC
        LIMIT :limit
        """
    )
    suspend fun getRecoveryPage(
        afterTimestamp: Long,
        afterSensorSerial: String,
        limit: Int,
    ): List<ReadingUncertainty>

    /**
     * Readings joined to their intervals in one query.
     *
     * The join is on the minute bucket, not the exact timestamp: a reading's
     * millisecond does not survive the native round trip (the driver writes
     * `sampleMs / 1000`, the sync reads back `sec * 1000`), so matching on
     * equality drops the interval for almost every reading. At one reading a
     * minute the bucket is unambiguous.
     */
    @Query(
"""
        SELECT r.id AS id, r.timestamp AS timestamp, r.sensorSerial AS sensorSerial,
               r.value AS value, r.rawValue AS rawValue, r.rate AS rate,
               u.lowerMgdl AS lowerMgdl, u.upperMgdl AS upperMgdl,
               u.intervalMass AS intervalMass, u.confidence AS confidence,
               u.artifactProbability AS artifactProbability
        FROM history_readings r
        LEFT JOIN reading_uncertainty u
          ON u.sensorSerial = r.sensorSerial
         AND u.timestamp = (r.timestamp / 60000) * 60000
        WHERE r.sensorSerial IN (:serials) AND r.timestamp >= :startTime
        ORDER BY r.timestamp ASC
        """
    )
    fun getReadingsWithUncertaintyFlow(
        serials: List<String>,
        startTime: Long
    ): Flow<List<HistoryReadingWithUncertainty>>

    @Query(
"""
        SELECT r.id AS id, r.timestamp AS timestamp, r.sensorSerial AS sensorSerial,
               r.value AS value, r.rawValue AS rawValue, r.rate AS rate,
               u.lowerMgdl AS lowerMgdl, u.upperMgdl AS upperMgdl,
               u.intervalMass AS intervalMass, u.confidence AS confidence,
               u.artifactProbability AS artifactProbability
        FROM history_readings r
        LEFT JOIN reading_uncertainty u
          ON u.sensorSerial = r.sensorSerial
         AND u.timestamp = (r.timestamp / 60000) * 60000
        WHERE r.sensorSerial IN (:serials) AND r.timestamp >= :startTime
        ORDER BY r.timestamp ASC
        """
    )
    suspend fun getReadingsWithUncertainty(
        serials: List<String>,
        startTime: Long
    ): List<HistoryReadingWithUncertainty>
}
