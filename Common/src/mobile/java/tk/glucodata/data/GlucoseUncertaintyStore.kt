package tk.glucodata.data

import android.util.Log
import androidx.annotation.Keep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tk.glucodata.Applic

/**
 * Sensor-agnostic store for per-reading credible intervals.
 *
 * Drivers live in `src/main` and cannot see Room, so they reach this through
 * `tk.glucodata.GlucoseUncertaintyAccess` by reflection, the same bridge
 * pattern the history sync uses. The static entry points below are that
 * bridge's target — renaming or re-signing them breaks it silently in
 * minified builds unless `proguard-rules.my` is updated to match.
 */
@Keep
object GlucoseUncertaintyStore {

    private const val TAG = "GlucoseUncertainty"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dao by lazy {
        runCatching { HistoryDatabase.getInstance(Applic.app).readingUncertaintyDao() }
            .onFailure { Log.w(TAG, "uncertainty dao unavailable", it) }
            .getOrNull()
    }

    /**
     * Stores a batch of intervals. Arrays are parallel and must agree in
     * length; anything unusable is dropped rather than stored, so a bad sample
     * simply has no uncertainty instead of a nonsense band.
     *
     * @param confidences NaN entries are stored as null.
     * @param artifactProbabilities NaN entries are stored as null.
     */
    @JvmStatic
    @Keep
    fun storeBatch(
        sensorSerial: String?,
        timestamps: LongArray,
        lowerMgdl: FloatArray,
        upperMgdl: FloatArray,
        intervalMass: Float,
        confidences: FloatArray,
        artifactProbabilities: FloatArray,
    ) {
        val serial = sensorSerial?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val size = timestamps.size
        if (size == 0) return
        if (lowerMgdl.size != size || upperMgdl.size != size ||
            confidences.size != size || artifactProbabilities.size != size
        ) {
            Log.w(TAG, "storeBatch array size mismatch for serial=$serial")
            return
        }
        val rows = ArrayList<ReadingUncertainty>(size)
        for (index in 0 until size) {
            val row = ReadingUncertainty(
                sensorSerial = serial,
                timestamp = timestamps[index],
                lowerMgdl = lowerMgdl[index],
                upperMgdl = upperMgdl[index],
                intervalMass = intervalMass,
                confidence = confidences[index].takeIf { it.isFinite() },
                artifactProbability = artifactProbabilities[index].takeIf { it.isFinite() },
            )
            if (row.timestamp > 0L && row.isUsable) rows += row
        }
        if (rows.isEmpty()) return
        scope.launch {
            runCatching { dao?.insertAll(rows) }
                .onFailure { Log.w(TAG, "storeBatch failed for serial=$serial size=${rows.size}", it) }
        }
    }

    /** Single-reading convenience for the live path. */
    @JvmStatic
    @Keep
    fun storeReading(
        sensorSerial: String?,
        timestamp: Long,
        lowerMgdl: Float,
        upperMgdl: Float,
        intervalMass: Float,
        confidence: Float,
        artifactProbability: Float,
    ) = storeBatch(
        sensorSerial = sensorSerial,
        timestamps = longArrayOf(timestamp),
        lowerMgdl = floatArrayOf(lowerMgdl),
        upperMgdl = floatArrayOf(upperMgdl),
        intervalMass = intervalMass,
        confidences = floatArrayOf(confidence),
        artifactProbabilities = floatArrayOf(artifactProbability),
    )

    /**
     * Drops intervals a rebuild is about to invalidate. Called before an
     * algorithm replay so stale bands cannot outlive the values they described.
     */
    @JvmStatic
    @Keep
    fun deleteForSensorAfter(sensorSerial: String?, timestamp: Long) {
        val serial = sensorSerial?.trim()?.takeIf { it.isNotEmpty() } ?: return
        scope.launch {
            runCatching { dao?.deleteForSensorAfter(serial, timestamp) }
                .onFailure { Log.w(TAG, "deleteForSensorAfter failed for serial=$serial", it) }
        }
    }

    /** Prunes rows older than the retention window; readings outlive their bands. */
    @JvmStatic
    @Keep
    fun pruneOlderThan(cutoffMs: Long) {
        scope.launch {
            runCatching { dao?.deleteOlderThan(cutoffMs) }
                .onFailure { Log.w(TAG, "pruneOlderThan failed", it) }
        }
    }
}
