package tk.glucodata.data.prediction.analysis

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One forecast exactly as it was made: the curve the app computed at [originTime] from what it
 * knew then. Nothing here is ever recomputed. Accuracy is worked out later by reading the
 * glucose that actually followed, which is why the actual values are not stored here.
 *
 * Values are mg/dL whatever the display unit, packed as little-endian floats one per
 * [stepMinutes] (the point at the origin itself is [baselineMgdl]).
 */
@Entity(
    tableName = "recorded_forecasts",
    indices = [
        Index(value = ["model", "originTime"], unique = true),
        Index(value = ["originTime"]),
    ],
)
class RecordedForecastEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Timestamp of the reading the forecast starts from. */
    val originTime: Long,
    val recordedAt: Long,
    val sensorSerial: String,
    /** [ForecastLane] code: which line of the chart the forecast extends. */
    val lane: Int,
    /** [ForecastModel] storage id. */
    val model: String,
    val modelVersion: Int,
    /** Identity of the settings, profile or trained model behind the forecast. */
    val configHash: Long,
    /** Whether this was the curve on the chart at the time; false for a shadow forecast. */
    val shown: Boolean,
    /** The adaptive forecast answered with its fallback (no model yet, unreliable, or a gap). */
    val fallback: Boolean,
    val baselineMgdl: Float,
    val stepMinutes: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val valuesMgdl: ByteArray,
    /** The arrow's rate at the origin, mg/dL per minute. */
    val trendMgdlPerMin: Float?,
    /** Journal entries dated after the origin that the forecast already included (planned meals or doses). */
    val plannedEntries: Int,
    /** Journal entries the forecast read at all. */
    val journalEntries: Int,
) {
    val values: FloatArray get() = ForecastValues.decode(valuesMgdl)

    /** The forecast value [minutes] after the origin, or NaN past the stored curve. */
    fun valueAt(minutes: Int): Float {
        if (minutes == 0) return baselineMgdl
        if (minutes % stepMinutes != 0) return Float.NaN
        return values.getOrElse(minutes / stepMinutes - 1) { Float.NaN }
    }
}

object ForecastValues {
    fun encode(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach(buffer::putFloat)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.float }
    }
}

/** Which line of the chart a forecast extends, as stored. */
object ForecastLane {
    const val AUTO = 0
    const val RAW = 1
    /** The calibrated line drawn over the auto readings. */
    const val CALIBRATED_AUTO = 2
    /** The calibrated line drawn over the raw readings. */
    const val CALIBRATED_RAW = 3
}

enum class ForecastModel(val storageId: String) {
    STANDARD("standard"),
    ADAPTIVE("adaptive");

    companion object {
        fun fromStorage(id: String): ForecastModel? = entries.firstOrNull { it.storageId == id }
    }
}

@Dao
interface RecordedForecastDao {
    /** Ignores a second forecast for the same model and origin: one moment is recorded once. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(forecast: RecordedForecastEntity): Long

    @Query("SELECT MAX(originTime) FROM recorded_forecasts WHERE model = :model")
    suspend fun latestOrigin(model: String): Long?

    @Query("SELECT * FROM recorded_forecasts WHERE originTime BETWEEN :fromMs AND :toMs ORDER BY originTime")
    suspend fun between(fromMs: Long, toMs: Long): List<RecordedForecastEntity>

    @Query("SELECT DISTINCT originTime FROM recorded_forecasts WHERE originTime BETWEEN :fromMs AND :toMs ORDER BY originTime")
    suspend fun origins(fromMs: Long, toMs: Long): List<Long>

    @Query("SELECT COUNT(*) FROM recorded_forecasts")
    suspend fun count(): Int

    @Query("SELECT MIN(originTime) FROM recorded_forecasts")
    suspend fun oldestOrigin(): Long?

    @Query("DELETE FROM recorded_forecasts WHERE originTime < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM recorded_forecasts")
    suspend fun deleteAll(): Int
}
