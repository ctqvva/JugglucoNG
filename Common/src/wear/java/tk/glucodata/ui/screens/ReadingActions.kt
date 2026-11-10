package tk.glucodata.ui.screens

import tk.glucodata.CalibrationAccess
import tk.glucodata.NotificationHistorySource
import tk.glucodata.ui.WearGlucoseStore

/**
 * What tapping a reading offers, mirroring the phone: a reading already carrying
 * a calibration is edited rather than calibrated again, and the journal action
 * only appears where the journal is actually available.
 */
data class ReadingAction(
    /** Timestamp of the calibration recorded against this reading, if any. */
    val calibrationTimestamp: Long = 0L,
    /** Stored fingerstick value of that calibration, canonical mg/dL. */
    val calibrationUserValueMgdl: Float = Float.NaN,
) {
    val hasCalibration: Boolean get() = calibrationTimestamp > 0L
}

object ReadingActions {
    /** A calibration counts as belonging to a reading within this window. */
    private const val MATCH_WINDOW_MS = 90_000L

    /**
     * Resolves the calibration attached to [timestampMs], if any. Anchors are
     * packed as [sensorValue, userValue, timestamp] triples in canonical mg/dL.
     */
    @JvmStatic
    fun resolve(timestampMs: Long, isRawMode: Boolean = false): ReadingAction {
        if (timestampMs <= 0L) return ReadingAction()
        // Anchors come from the shared snapshot when it has them: one row per
        // reading meant one native call per row, repeated on every scroll.
        val snapshot = WearGlucoseStore.snapshot.value
        val anchors = if (snapshot.isLoaded && snapshot.isRawMode == isRawMode) {
            snapshot.anchors
        } else {
            val sensor = WearGlucoseStore.currentSensor()
            runCatching {
                CalibrationAccess.getActiveCalibrationAnchors(sensor, isRawMode)
            }.getOrDefault(DoubleArray(0))
        }
        var bestDelta = Long.MAX_VALUE
        var best = ReadingAction()
        for (offset in anchors.indices step 3) {
            if (offset + 2 >= anchors.size) break
            val anchorTime = anchors[offset + 2].toLong()
            val delta = kotlin.math.abs(anchorTime - timestampMs)
            if (delta <= MATCH_WINDOW_MS && delta < bestDelta) {
                bestDelta = delta
                best = ReadingAction(anchorTime, anchors[offset + 1].toFloat())
            }
        }
        return best
    }

    /**
     * True when the phone has told the watch its journal is enabled. The watch
     * caches the last serve, so this answers correctly before a fresh sync lands.
     */
    @JvmStatic
    fun journalAvailable(): Boolean =
        runCatching { tk.glucodata.WearJournalSync.cached().enabled }.getOrDefault(false)

    /** Readings for the History screen: a longer window than the home list. */
    @JvmStatic
    fun historyReadings(isMmol: Boolean, hours: Int = 24) = runCatching {
        val sensor = WearGlucoseStore.currentSensor()
        NotificationHistorySource
            .getDisplayHistory(System.currentTimeMillis() - hours * 3_600_000L, isMmol, sensor)
            .asReversed()
    }.getOrDefault(emptyList())
}
