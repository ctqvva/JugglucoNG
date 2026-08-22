package tk.glucodata.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A user- or detector-attributed classification for one hypo episode. Episodes
 * themselves are recomputed from the recorded trace (StatsAnalytics.detectEpisodes);
 * only what cannot be recomputed is stored — who called it what, and when. Raw readings
 * are never rewritten, matching the delete-tombstone precedent: flipping a mark back
 * restores the statistics because nothing was ever removed.
 *
 * [episodeKeyMs] is the episode's start timestamp rounded down to the minute. A later
 * re-scan may move a boundary (backfill, a changed low target), so readers match a
 * recomputed episode to its mark by OVERLAP of the stored window, never by key equality
 * — see [findFor]. The key is only the primary key.
 */
@Entity(tableName = "hypo_episode_marks")
data class HypoEpisodeMark(
    @PrimaryKey val episodeKeyMs: Long,
    val endMs: Long,
    val nadirMgdl: Float,
    /** [CLASSIFICATION_PRESSURE] or [CLASSIFICATION_REAL]. */
    val classification: String,
    /** [SOURCE_MANUAL], [SOURCE_DETECTOR] or [SOURCE_HOLD]. */
    val source: String,
    val updatedAt: Long
) {
    companion object {
        const val CLASSIFICATION_PRESSURE = "pressure"
        const val CLASSIFICATION_REAL = "real"
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_DETECTOR = "detector"
        const val SOURCE_HOLD = "hold"

        fun keyFor(startMs: Long): Long = startMs - startMs % 60_000L

        /**
         * The mark belonging to an episode spanning [startMs, endMs]: the stored window
         * that overlaps it. Overlap, not key equality, so a boundary that drifted after
         * the mark was written still finds its mark and the toggle can undo it.
         */
        fun findFor(marks: Collection<HypoEpisodeMark>, startMs: Long, endMs: Long): HypoEpisodeMark? =
            marks.firstOrNull { it.episodeKeyMs <= endMs && it.endMs >= startMs }
    }
}

@Dao
interface HypoEpisodeDao {
    @Query("SELECT * FROM hypo_episode_marks ORDER BY episodeKeyMs DESC")
    suspend fun getAll(): List<HypoEpisodeMark>

    @Query("SELECT * FROM hypo_episode_marks WHERE classification = 'pressure' ORDER BY episodeKeyMs")
    fun observePressureMarks(): Flow<List<HypoEpisodeMark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mark: HypoEpisodeMark)

    @Query("DELETE FROM hypo_episode_marks WHERE episodeKeyMs = :episodeKeyMs")
    suspend fun delete(episodeKeyMs: Long)
}
