package tk.glucodata.data.calibration

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CalibrationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(calibration: CalibrationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(calibrations: List<CalibrationEntity>)

    @Update
    suspend fun update(calibration: CalibrationEntity)

    @Delete
    suspend fun delete(calibration: CalibrationEntity)

    @Query("SELECT * FROM calibrations ORDER BY timestamp DESC")
    fun getAll(): Flow<List<CalibrationEntity>>

    @Query("SELECT * FROM calibrations ORDER BY timestamp DESC")
    suspend fun getAllSync(): List<CalibrationEntity>

    @Query("SELECT * FROM calibrations WHERE sensorId = :sensorId ORDER BY timestamp DESC")
    fun getAllForSensor(sensorId: String): Flow<List<CalibrationEntity>>

    @Query("DELETE FROM calibrations WHERE sensorId = :sensorId")
    suspend fun deleteForSensor(sensorId: String): Int

    @Query("SELECT * FROM calibrations WHERE TRIM(sensorId) = '' ORDER BY timestamp DESC")
    suspend fun getBlankSensorIdRows(): List<CalibrationEntity>

    @Query("UPDATE calibrations SET sensorId = :sensorId WHERE id = :id AND TRIM(sensorId) = ''")
    suspend fun assignBlankSensorId(id: Int, sensorId: String): Int

    @Query("UPDATE calibrations SET sensorId = :sensorId WHERE id = :id")
    suspend fun updateSensorId(id: Int, sensorId: String): Int
    
    @Query("DELETE FROM calibrations WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)

    @Query("DELETE FROM calibrations WHERE journalEntryId IS NOT NULL")
    suspend fun deleteJournalDerived(): Int

    @Query("DELETE FROM calibrations")
    suspend fun deleteAll()
}
