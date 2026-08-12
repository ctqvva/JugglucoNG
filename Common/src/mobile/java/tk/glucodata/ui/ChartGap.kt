package tk.glucodata.ui

import androidx.compose.ui.geometry.Offset

/**
 * How far apart two readings may be before the chart stops connecting them.
 *
 * The native renderer behind the notification chart (`JCurve::histcurve` in
 * `curve/curve.cpp`) walks history *positions* and breaks the path only on a missing
 * slot — elapsed time never enters into it, so consecutive 15-minute records always
 * connect. This chart only has a flat list of timestamps from mixed sources, so the
 * equivalent rule has to be expressed in time:
 *
 *   [HISTORY_INTERVAL_MS] — the coarsest cadence the app stores, Libre 1/2 NFC history
 *   + [WRITE_DRIFT_MS]    — a slot's stored time is `writeTime - (currentId - slotId) * 60`,
 *                           and adjacent slots get written by different scans or BLE
 *                           packets, so their second-offsets differ by up to a minute
 *   + margin
 *
 * At 17 minutes two adjacent history slots always connect however they drifted, while a
 * genuinely missing slot — 30 minutes or more — still breaks the curve. That distinction
 * is the point: a hole in the data has to look like a hole, so this must not be raised
 * to cover one.
 *
 * [ChartLineRun] handles what remains, mirroring the `nvgCircle` upstream draws for a
 * one-point run: a reading with no neighbour close enough to join becomes a dot rather
 * than nothing at all.
 */
internal object ChartGap {
    // The derivation above now lives with the constant in
    // tk.glucodata.GlucoseChartGap, which the watch's smoothing needs too.
    const val THRESHOLD_MS = tk.glucodata.GlucoseChartGap.THRESHOLD_MS
}

/**
 * Tracks one polyline run so a run holding a single point stays visible.
 *
 * `Path.moveTo` with no following `lineTo` strokes nothing, so a reading with no
 * neighbour close enough to connect to would silently disappear. Runs of length one are
 * collected here and drawn as dots instead.
 */
internal class ChartLineRun {
    private var startX = 0f
    private var startY = 0f
    private var open = false
    private var connected = false

    val isolatedPoints = ArrayList<Offset>()

    /** Starts a new run at a `moveTo`, closing whatever run was open. */
    fun begin(x: Float, y: Float) {
        flush()
        startX = x
        startY = y
        open = true
        connected = false
    }

    /** Records that the open run got a `lineTo`, so it strokes on its own. */
    fun extend() {
        connected = true
    }

    /** Closes the open run; call once more after the last point. */
    fun flush() {
        if (open && !connected) {
            isolatedPoints.add(Offset(startX, startY))
        }
        open = false
    }
}
