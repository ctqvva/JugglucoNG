package tk.glucodata.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Which sensor owned the main line for one minute. */
data class ReadingDisplayOwner(
    val timestamp: Long,
    val sensorSerial: String,
)

/**
 * Reads and writes [ReadingDisplay] rows.
 *
 * Two writes, and no others. [sealAll] records minutes that have none and
 * ignores those that do. [reviseIfUnsealed] revises a minute that is still
 * inside its grace window, and refuses — in SQL — to touch one that is not.
 * There is no REPLACE and no unconditional update, so no caller can move a
 * sealed value whatever it passes.
 */
@Dao
interface ReadingDisplayDao {

    /**
     * Records main values for minutes that do not have one yet.
     *
     * `IGNORE`, not `REPLACE`: replaying a seal pass over minutes that are
     * already recorded must be a no-op, whatever today's settings would compute
     * for them.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun sealAll(rows: List<ReadingDisplay>): List<Long>

    /**
     * Revises a record that has not sealed yet.
     *
     * The seal is enforced here, in SQL, rather than by whichever caller
     * remembers to check: `timestamp > :sealHorizon` is the whole guarantee, and
     * a caller that gets the horizon wrong can only fail to revise a live
     * minute, never rewrite a sealed one. There is deliberately no other update
     * and no REPLACE anywhere in this DAO.
     */
    @Query(
        """
        UPDATE reading_display
        SET displayMgdl = :displayMgdl,
            sensorSerial = :sensorSerial,
            viewMode = :viewMode,
            calibrationFingerprint = :calibrationFingerprint,
            recordedAt = :recordedAt
        WHERE timestamp = :timestamp AND timestamp > :sealHorizon
        """
    )
    suspend fun reviseIfUnsealed(
        timestamp: Long,
        sensorSerial: String,
        displayMgdl: Float,
        viewMode: Int,
        calibrationFingerprint: Long,
        recordedAt: Long,
        sealHorizon: Long,
    ): Int

    @Query("SELECT * FROM reading_display WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    suspend fun getAllSince(startTime: Long): List<ReadingDisplay>

    @Query(
        "SELECT * FROM reading_display WHERE timestamp >= :startTime AND timestamp <= :endTime " +
            "ORDER BY timestamp ASC"
    )
    suspend fun getBetween(startTime: Long, endTime: Long): List<ReadingDisplay>

    @Query("SELECT * FROM reading_display WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    fun getFlow(startTime: Long): Flow<List<ReadingDisplay>>

    /** Which sensor owned the main line, minute by minute, since [startTime]. */
    @Query("SELECT timestamp, sensorSerial FROM reading_display WHERE timestamp >= :startTime")
    suspend fun mainLineOwners(startTime: Long): List<ReadingDisplayOwner>

    /** The newest minute already recorded, so a seal pass resumes rather than rescans. */
    @Query("SELECT MAX(timestamp) FROM reading_display")
    suspend fun getNewestSealedMinute(): Long?

    @Query("SELECT COUNT(*) FROM reading_display")
    suspend fun getCount(): Int

    @Query("DELETE FROM reading_display WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM reading_display")
    suspend fun deleteAll()

    @Query(
        """
        SELECT * FROM reading_display display
        WHERE display.timestamp > :afterTimestamp
        ORDER BY display.timestamp ASC
        LIMIT :limit
        """
    )
    suspend fun getRecoveryPage(
        afterTimestamp: Long,
        limit: Int,
    ): List<ReadingDisplay>
}
