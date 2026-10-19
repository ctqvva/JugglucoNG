package tk.glucodata.data

import androidx.room.Entity
import androidx.room.Index

/**
 * Per-reading credible interval, stored beside the readings rather than on them.
 *
 * A separate table on purpose. `history_readings` rows are rewritten by native
 * re-sync and by history rebuilds; uncertainty is produced once by whichever
 * estimator emitted the value and cannot be recomputed later from the stored
 * number alone. Keeping it in its own table keyed by (sensorSerial, timestamp)
 * means a re-sync cannot silently drop it, and a reading with no row here simply
 * has no uncertainty — which is the correct answer for every reading written
 * before this existed and for every sensor whose algorithm does not model it.
 *
 * Bounds are stored in mg/dL, matching how readings are stored, and converted
 * for display like any other value.
 */
@Entity(
    tableName = "reading_uncertainty",
    primaryKeys = ["sensorSerial", "timestamp"],
    indices = [Index(value = ["timestamp"])],
)
data class ReadingUncertainty(
    val sensorSerial: String,
    val timestamp: Long,
    val lowerMgdl: Float,
    val upperMgdl: Float,
    /** Interval mass, e.g. 0.9 for a 90% credible interval. */
    val intervalMass: Float,
    /** Estimator confidence in [0,1], or null when the source does not model it. */
    val confidence: Float?,
    /** Posterior artifact probability, or null when the source does not model it. */
    val artifactProbability: Float?,
) {
    /** Rows whose bounds are nonsense are dropped rather than drawn. */
    val isUsable: Boolean
        get() = lowerMgdl.isFinite() && upperMgdl.isFinite() &&
            upperMgdl >= lowerMgdl && lowerMgdl > 0f
}

/** Adapts a stored row into the sensor-agnostic UI type. */
fun ReadingUncertainty.toGlucoseUncertainty(): tk.glucodata.GlucoseUncertainty? =
    if (!isUsable) {
        null
    } else {
        tk.glucodata.GlucoseUncertainty(
            lower = lowerMgdl,
            upper = upperMgdl,
            intervalMass = intervalMass,
            confidence = confidence,
            artifactProbability = artifactProbability,
        )
    }
