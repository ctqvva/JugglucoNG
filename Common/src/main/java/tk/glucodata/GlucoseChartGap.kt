package tk.glucodata

/**
 * How far apart two readings may be before a chart stops connecting them.
 *
 * The native renderer behind the notification chart (`JCurve::histcurve` in
 * `curve/curve.cpp`) walks history *positions* and breaks the path only on a
 * missing slot — elapsed time never enters into it, so consecutive 15-minute
 * records always connect. The Compose charts only have a flat list of
 * timestamps from mixed sources, so the equivalent rule has to be expressed in
 * time:
 *
 *   [HISTORY_INTERVAL_MS] — the coarsest cadence the app stores, Libre 1/2 NFC history
 *   + [WRITE_DRIFT_MS]    — a slot's stored time is `writeTime - (currentId - slotId) * 60`,
 *                           and adjacent slots get written by different scans or BLE
 *                           packets, so their second-offsets differ by up to a minute
 *   + margin
 *
 * At 17 minutes two adjacent history slots always connect however they drifted,
 * while a genuinely missing slot — 30 minutes or more — still breaks the curve.
 * That distinction is the point: a hole in the data has to look like a hole, so
 * this must not be raised to cover one.
 *
 * Lives in the shared source set because segment splitting is what keeps
 * smoothing from averaging across a gap, and the watch smooths too.
 */
object GlucoseChartGap {
    private const val HISTORY_INTERVAL_MS = 15L * 60L * 1000L
    private const val WRITE_DRIFT_MS = 60L * 1000L
    private const val MARGIN_MS = 60L * 1000L

    const val THRESHOLD_MS = HISTORY_INTERVAL_MS + WRITE_DRIFT_MS + MARGIN_MS
}
