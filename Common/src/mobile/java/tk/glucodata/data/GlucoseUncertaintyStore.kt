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

    /** Readings outlive their intervals; 120 days comfortably covers any chart range. */
    private const val RETENTION_MS = 120L * 24L * 60L * 60L * 1000L
    private const val PRUNE_INTERVAL_MS = 12L * 60L * 60L * 1000L

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
                timestamp = minuteBucket(timestamps[index]),
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
            pruneIfDue()
        }
    }

    /**
     * Floors a sample time to its minute.
     *
     * Intervals must be keyed the same way on both sides of the join, and a
     * reading's exact millisecond does not survive the round trip: the driver
     * writes `sampleMs / 1000` into the native stream and the sync reads it
     * back as `sec * 1000`, so any reading whose time was not already
     * second-aligned comes back with a different timestamp than it was stored
     * under. Keying by minute is immune to that and to small drift, and at one
     * reading a minute it is still unambiguous. It also makes the table
     * idempotent across history rebuilds.
     */
    private fun minuteBucket(timestampMs: Long): Long = timestampMs / 60_000L * 60_000L

    /**
     * Keeps the table bounded without a scheduler.
     *
     * Intervals are only useful for as long as the chart can still show the
     * readings they describe, and at one row a minute an unpruned table grows
     * by ~43k rows a month. Pruning piggybacks on writes and runs at most once
     * per [PRUNE_INTERVAL_MS], so it costs nothing on the live path.
     */
    private suspend fun pruneIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastPruneMs < PRUNE_INTERVAL_MS) return
        lastPruneMs = now
        runCatching { dao?.deleteOlderThan(now - RETENTION_MS) }
            .onFailure { Log.w(TAG, "prune failed", it) }
    }

    @Volatile
    private var lastPruneMs = 0L

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

    /** Drops every interval for a sensor; see [GlucoseUncertaintyAccess.clearForSensor]. */
    @JvmStatic
    @Keep
    fun clearForSensor(sensorSerial: String?) {
        val serial = sensorSerial?.trim()?.takeIf { it.isNotEmpty() } ?: return
        scope.launch {
            runCatching { HistoryRepository().clearUncertaintyForSensor(serial) }
                .onFailure { Log.w(TAG, "clearForSensor failed for serial=$serial", it) }
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
