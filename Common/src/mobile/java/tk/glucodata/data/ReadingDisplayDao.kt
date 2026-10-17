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
 * Rows are keyed by (minute, lane): each main line keeps its own record of
 * a minute — see [ReadingDisplay].
 *
 * Two writes, and no others. [sealAll] records minutes that have none in
 * their lane and ignores those that do. [reviseIfUnsealed] revises a minute that is still
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
     * the caller supplies the horizon from the current clock and grace window.
     * Identical presentations are no-ops: changing only recordedAt must not
     * invalidate every chart query observing the table.
     * A stale or incorrect horizon would weaken this bound. There is no other update
     * and no REPLACE anywhere in this DAO.
     *
     * Scoped to the row's own lane: a raw-lane presentation cannot revise what
     * the auto line showed, or the other way round. Switching between 0 and 2
     * (or 1 and 3) is the same main line and revises its record.
     */
    @Query(
        """
        UPDATE reading_display
        SET displayMgdl = :displayMgdl,
            sensorSerial = :sensorSerial,
            viewMode = :viewMode,
            calibrationFingerprint = :calibrationFingerprint,
            recordedAt = :recordedAt
        WHERE timestamp = :timestamp AND lane = :lane AND timestamp > :sealHorizon
          AND (displayMgdl != :displayMgdl OR sensorSerial != :sensorSerial
               OR viewMode != :viewMode OR calibrationFingerprint != :calibrationFingerprint)
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
        lane: Int,
    ): Int

    @Query("SELECT * FROM reading_display WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    suspend fun getAllSince(startTime: Long): List<ReadingDisplay>

    /** Rows after the (minute, lane) cursor; a minute can hold two rows, so the cursor needs both. */
    @Query(
        "SELECT * FROM reading_display " +
            "WHERE timestamp > :afterTimestamp OR (timestamp = :afterTimestamp AND lane > :afterLane) " +
            "ORDER BY timestamp ASC, lane ASC LIMIT :limit"
    )
    suspend fun getPageAfter(afterTimestamp: Long, afterLane: Int, limit: Int): List<ReadingDisplay>

    @Query(
        "SELECT * FROM reading_display WHERE timestamp >= :startTime AND timestamp <= :endTime " +
            "ORDER BY timestamp ASC"
    )
    suspend fun getBetween(startTime: Long, endTime: Long): List<ReadingDisplay>

    @Query("SELECT * FROM reading_display WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    fun getFlow(startTime: Long): Flow<List<ReadingDisplay>>

    @Query(
        "SELECT * FROM reading_display WHERE timestamp >= :startTime AND timestamp <= :endTime " +
            "ORDER BY timestamp ASC"
    )
    fun getBetweenFlow(startTime: Long, endTime: Long): Flow<List<ReadingDisplay>>

    /**
     * Which sensor owned the main line, minute by minute, since [startTime].
     *
     * Both lanes of a minute normally name the same sensor. Where they do not,
     * the earlier recording comes last, so a caller that keys by minute keeps
     * the first presentation's owner.
     */
    @Query(
        "SELECT timestamp, sensorSerial FROM reading_display WHERE timestamp >= :startTime " +
            "ORDER BY timestamp ASC, recordedAt DESC"
    )
    suspend fun mainLineOwners(startTime: Long): List<ReadingDisplayOwner>

    /** The newest minute already recorded, so a seal pass resumes rather than rescans. */
    @Query("SELECT MAX(timestamp) FROM reading_display")
    suspend fun getNewestSealedMinute(): Long?

    @Query("SELECT COUNT(*) FROM reading_display")
    suspend fun getCount(): Int


    /**
     * Recovered display rows in (minute, lane) order. A minute can hold a row
     * per lane, so the page cursor is the pair: a cursor on the minute alone
     * would skip the second lane of a minute split across two pages.
     */
    @Query(
        """
        SELECT * FROM reading_display display
        WHERE display.timestamp > :afterTimestamp
           OR (display.timestamp = :afterTimestamp AND display.lane > :afterLane)
        ORDER BY display.timestamp ASC, display.lane ASC
        LIMIT :limit
        """
    )
    suspend fun getRecoveryPage(
        afterTimestamp: Long,
        afterLane: Int,
        limit: Int,
    ): List<ReadingDisplay>
    @Query("DELETE FROM reading_display WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    /** A removed sensor takes its recorded display values with it when its history goes. */
    @Query("DELETE FROM reading_display WHERE sensorSerial IN (:serials)")
    suspend fun deleteForSensors(serials: List<String>)

    /**
     * Voids records whose sensor has rewritten the number they were drawn from —
     * see [tk.glucodata.data.RecordedDisplayVoiding]. A deletion, not a
     * revision: the minute goes back to having no record in [lane], and is
     * recorded again the next time it is presented there.
     */
    @Query("DELETE FROM reading_display WHERE lane = :lane AND timestamp IN (:minutes)")
    suspend fun deleteAtMinutes(lane: Int, minutes: List<Long>): Int

    @Query("DELETE FROM reading_display")
    suspend fun deleteAll()

}
