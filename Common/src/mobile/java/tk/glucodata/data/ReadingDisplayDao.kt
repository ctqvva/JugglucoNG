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
 * There is deliberately **no** update or replace-on-conflict write here, and no
 * write that names a single row. A recorded main value is the guarantee this
 * table exists to make; a write that could overwrite one would be the
 * guarantee's only hole. [sealAll] ignores minutes that already have a record,
 * and rows are only ever written for minutes already past the grace window, so
 * the settling tail has nothing stored to contradict it.
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

    @Query("SELECT * FROM reading_display WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    suspend fun getAllSince(startTime: Long): List<ReadingDisplay>

    @Query(
        "SELECT * FROM reading_display WHERE timestamp >= :startTime AND timestamp <= :endTime " +
            "ORDER BY timestamp ASC"
    )
    suspend fun getBetween(startTime: Long, endTime: Long): List<ReadingDisplay>

    @Query("SELECT * FROM reading_display WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    fun getFlow(startTime: Long): Flow<List<ReadingDisplay>>

    /**
     * Which sensor owns the main line, minute by minute, since [startTime].
     *
     * The main line's owner changes over time — that is the whole point of the
     * record — so "is this sensor a peer?" is a question per minute, not per
     * sensor. Asked once per sensor it produced two contradictions at the same
     * time: the sensor that owns a past minute was drawn as the main line *and*
     * as a peer, so its value appeared twice; and the sensor that owns the
     * present was drawn nowhere across the past.
     */
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
