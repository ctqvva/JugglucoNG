package tk.glucodata.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Reads and writes [ReadingUncertainty] rows. */
@Dao
interface ReadingUncertaintyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ReadingUncertainty>)

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
}
