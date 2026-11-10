package tk.glucodata

import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * Sends the readings a disconnect left out of the live stream to the xDrip-broadcast receivers
 * (AAPS), one broadcast per reading under its own timestamp, the way xDrip does.
 *
 * The live path ([SuperGattCallback.emitExchangeOutputs]) only ever carries the newest reading, so
 * the backfill a sensor delivers on reconnect reached the database and the chart but never AAPS.
 * History writers call [onHistoryStored]; the live path calls [recordLiveSent]. What is a gap and
 * what is already delivered is decided by [XdripBackfillTracker].
 */
object XdripBackfill {
    private const val LOG_ID = "XdripBackfill"
    private const val PREFS = "xdrip_backfill"
    private const val RAW_ONLY_VIEW_MODE = 1
    private const val RAW_PRIMARY_VIEW_MODE = 3
    private const val MIN_SMOOTHING_POINTS = 3
    private const val MAX_RATE_LOOKBACK_MS = 60L * 60L * 1000L
    private const val PROCESS_DELAY_MS = 5_000L

    /** [storedAtMs]: when the row first reached Room, or 0 when unknown. */
    data class Row(
        val timeMs: Long,
        val value: Float,
        val rawValue: Float,
        val rate: Float?,
        val storedAtMs: Long = 0L,
    )

    /** One stored reading with the mg/dL a live send would carry for it. */
    data class DisplayedRow(val timeMs: Long, val mgdl: Int)

    private class Pending(val sensorSerial: String) {
        val candidates = HashMap<Long, XdripBackfillTracker.Candidate>()
    }

    private val tracker = XdripBackfillTracker()
    private val pending = HashMap<String, Pending>()

    private fun key(sensorId: String): String = SensorIdentity.resolveAppSensorId(sensorId) ?: sensorId

    /** A live reading went out on the xDrip broadcast. */
    @JvmStatic
    fun recordLiveSent(sensorId: String?, timeMs: Long, sensorGen: Int, sensorStartMs: Long) {
        if (sensorId.isNullOrEmpty() || timeMs <= 0L) return
        val id = key(sensorId)
        // Bring back what was sent before a restart first, or the first live send would become the
        // cutoff and the gap before it would never be backfilled.
        if (tracker.sensor(id) == null) restore(id)
        tracker.recordSent(id, timeMs, sensorGen, sensorStartMs)
        persist(id)
    }

    /**
     * History rows for [sensorSerial] were written. The ones live never carried are sent
     * [PROCESS_DELAY_MS] later: the history row of a reading is stored just before that reading
     * goes out live, and sending it straight away doubled it in AAPS. Waiting lets the live send
     * land first, so the deduplication sees it (xDrip processes backfill after a delay too).
     */
    @JvmStatic
    fun onHistoryStored(sensorSerial: String, rows: List<Row>) {
        if (rows.isEmpty() || sensorSerial.isEmpty()) return
        try {
            if (!Natives.getxbroadcast() || !isMainSensor(sensorSerial)) return
            val id = key(sensorSerial)
            if (tracker.sensor(id) == null && restore(id) == null) return
            val app = Applic.app ?: return
            val computed = candidates(
                sensorSerial, rows, viewMode(sensorSerial), DataSmoothing.exchangeSmoothingMinutes(app),
                backfillCausalHistoryFor(app, sensorSerial, rows),
            )
            if (computed.isEmpty()) return
            val schedule = synchronized(pending) {
                val queued = pending.getOrPut(id) { Pending(sensorSerial) }
                val first = queued.candidates.isEmpty()
                computed.forEach { queued.candidates[it.timeMs] = it }
                first
            }
            if (schedule) {
                Applic.scheduler.schedule({ flush(id) }, PROCESS_DELAY_MS, TimeUnit.MILLISECONDS)
            }
        } catch (error: Throwable) {
            Log.stack(LOG_ID, "onHistoryStored", error)
        }
    }

    /**
     * The mg/dL each of [rows] would carry on an exchange send: the primary lane, smoothed when
     * exchange smoothing is on, then calibrated. Rows without a usable value are left out.
     *
     * A batch rather than a single row because the smoothing window spans the rows; the xDrip
     * backfill uses the same resolution through [onHistoryStored], and Dexcom Share uses this so a
     * backfilled reading shows the user the number the chart and the broadcast showed.
     */
    internal fun displayedRows(sensorSerial: String, rows: List<Row>): List<DisplayedRow> {
        if (rows.isEmpty() || sensorSerial.isEmpty()) return emptyList()
        return try {
            val app = Applic.app ?: return emptyList()
            displayedRows(
                sensorSerial, rows, viewMode(sensorSerial),
                DataSmoothing.exchangeSmoothingMinutes(app),
                causalHistoryFor(app, sensorSerial, rows),
            )
        } catch (error: Throwable) {
            Log.stack(LOG_ID, "displayedRows", error)
            emptyList()
        }
    }

    /** [displayedRows] with everything it reads from the phone already resolved. */
    internal fun displayedRows(
        sensorSerial: String,
        rows: List<Row>,
        viewMode: Int,
        smoothingMinutes: Int,
        causalHistory: List<GlucosePoint>?,
    ): List<DisplayedRow> =
        candidates(sensorSerial, rows, viewMode, smoothingMinutes, causalHistory)
            .map { DisplayedRow(it.timeMs, Math.round(it.mgdl).toInt()) }

    private fun flush(id: String) {
        try {
            val queued = synchronized(pending) { pending.remove(id) } ?: return
            // The user may have picked another sensor during the delay.
            if (!Natives.getxbroadcast() || !isMainSensor(queued.sensorSerial)) return
            val app = Applic.app ?: return
            val sensor = tracker.sensor(id) ?: return
            val spacingMs = DataSmoothing.exchangeThrottleIntervalMinutes(app) * 60_000L
            val picked = tracker.pick(id, queued.candidates.values.toList(), System.currentTimeMillis(), spacingMs)
            if (picked.isEmpty()) return
            if (Log.doLog) Log.i(LOG_ID, "backfill ${picked.size} readings sensor=$id")
            for (point in picked) {
                SendLikexDrip.broadcastBackfill(id, sensor.sensorGen, point.mgdl, point.timeMs, point.rate, sensor.sensorStartMs)
            }
            persist(id)
        } catch (error: Throwable) {
            Log.stack(LOG_ID, "flush", error)
        }
    }

    /** Same rule as the live path: a deselected sensor stores history but exchanges nothing. */
    private fun isMainSensor(sensorSerial: String): Boolean {
        val main = runCatching { SensorIdentity.resolveLiveMainSensor(sensorSerial) }.getOrNull()
        return main.isNullOrEmpty() || SensorIdentity.matches(sensorSerial, main)
    }

    /** Room keeps the arrival millisecond; a row and its stored point can differ by that much. */
    private const val MATCH_TOLERANCE_MS = 30_000L

    /**
     * The lane value the live path would have sent for each of [rowTimes], given the stored
     * [history] (ascending, mg/dL). A row with no stored point within [MATCH_TOLERANCE_MS] is
     * left out, and the caller sends its own value as before.
     */
    internal fun causalLaneValues(
        history: List<GlucosePoint>,
        rowTimes: Collection<Long>,
        rawLane: Boolean,
    ): Map<Long, Float> {
        if (history.isEmpty() || rowTimes.isEmpty()) return emptyMap()
        val lane = CausalGlucoseFilter.filterLane(
            history, { it.timestamp }, { if (rawLane) it.rawValue else it.value }, mgdlPerUnit = 1f,
            sameSource = DataSmoothing::sameCausalSource,
        )
        val indexByTime = java.util.TreeMap<Long, Int>()
        history.forEachIndexed { index, point -> indexByTime[point.timestamp] = index }
        val result = HashMap<Long, Float>()
        for (rowTime in rowTimes) {
            val nearest = listOfNotNull(indexByTime.floorEntry(rowTime), indexByTime.ceilingEntry(rowTime))
                .minByOrNull { kotlin.math.abs(it.key - rowTime) } ?: continue
            if (kotlin.math.abs(nearest.key - rowTime) > MATCH_TOLERANCE_MS) continue
            val value = lane[nearest.value]
            if (value.isFinite() && value > 0f) result[rowTime] = value
        }
        return result
    }

    /**
     * Stored history around [rows] for the causal filter, or null when it is off. Rows are
     * already committed, so this is what the live path would have seen for each of them.
     *
     * Never consults the xDrip tracker: Dexcom Share uses this path even when the xDrip broadcast
     * is off, and must still carry the number the live stream showed.
     */
    private fun causalHistoryFor(app: Context, sensorSerial: String, rows: List<Row>): List<GlucosePoint>? {
        if (rows.isEmpty() || !DataSmoothing.useCausalFilter(app)) return null
        if (!DataSmoothing.shouldSmoothExchangeOutputs(app)) return null
        val start = rows.minOf { it.timeMs } - CausalGlucoseFilter.WINDOW_MS
        return NotificationHistorySource.getRawHistory(start, sensorSerial)
    }

    /**
     * [causalHistoryFor] for the xDrip backfill only, and only when the tracker would still send
     * something from [rows]. A batch that is already covered would pay for a Room read and a
     * filter for nothing.
     */
    private fun backfillCausalHistoryFor(app: Context, sensorSerial: String, rows: List<Row>): List<GlucosePoint>? {
        val spacingMs = DataSmoothing.exchangeThrottleIntervalMinutes(app) * 60_000L
        val needed = tracker.needsBackfill(
            key(sensorSerial),
            rows.map { XdripBackfillTracker.Candidate(it.timeMs, it.value.toDouble(), 0f, it.storedAtMs) },
            System.currentTimeMillis(),
            spacingMs,
        )
        if (!needed) return null
        return causalHistoryFor(app, sensorSerial, rows)
    }

    /**
     * The values a live reading would have carried: the primary lane, smoothed when exchange
     * smoothing is on, then calibrated. In the raw view modes a row without a raw value falls back
     * to its auto value, as the live display does. Stored rows are mg/dL, and so is every step
     * here; [NightscoutCalibration] does the display-unit conversion the calibration model needs.
     * A stored rate of 0 is the "no rate" placeholder of backfilled history, so the rate is
     * derived from an earlier reading instead, or left unknown.
     *
     * [causalHistory] replaces the window with [CausalGlucoseFilter] so a backfilled minute carries
     * the number the live path sent for it; a row the filter cannot name falls back to its value.
     */
    internal fun candidates(
        sensorSerial: String,
        rows: List<Row>,
        viewMode: Int,
        smoothingMinutes: Int,
        causalHistory: List<GlucosePoint>? = null,
    ): List<XdripBackfillTracker.Candidate> {
        val rawPrimary = viewMode == RAW_ONLY_VIEW_MODE || viewMode == RAW_PRIMARY_VIEW_MODE
        fun usesRaw(row: Row) = rawPrimary && row.rawValue.isFinite() && row.rawValue > 0f
        fun base(row: Row) = if (usesRaw(row)) row.rawValue else row.value
        val valid = rows.sortedBy(Row::timeMs).filter { base(it).isFinite() && base(it) > 0f }
        val smoothed = when {
            smoothingMinutes <= 0 -> valid.map(::base)
            causalHistory != null -> {
                val times = valid.map(Row::timeMs)
                val auto = causalLaneValues(causalHistory, times, rawLane = false)
                val raw = if (rawPrimary) causalLaneValues(causalHistory, times, rawLane = true) else emptyMap()
                // The same per-reading lane choice the live path makes: a row without a usable
                // raw value takes the filtered auto lane, never its unfiltered stored value.
                valid.map { row -> (if (usesRaw(row)) raw[row.timeMs] else auto[row.timeMs]) ?: base(row) }
            }
            valid.size >= MIN_SMOOTHING_POINTS ->
                GlucoseSmoothing.smoothLane(valid, smoothingMinutes * 30_000L, Row::timeMs, ::base).toList()
            else -> valid.map(::base)
        }
        val shown = valid.mapIndexed { index, row ->
            val primary = smoothed[index]
            val calibrated = NightscoutCalibration.getCalibratedValueForViewMode(
                sensorSerial, viewMode,
                if (usesRaw(row)) row.value else primary,
                if (usesRaw(row)) primary else row.rawValue,
                row.timeMs,
            )
            if (calibrated.isFinite() && calibrated > 0f) calibrated else primary
        }
        return valid.mapIndexed { index, row ->
            val rate = row.rate?.takeIf { it.isFinite() && it != 0f }
                ?: derivedRate(valid, shown, index)
            XdripBackfillTracker.Candidate(row.timeMs, shown[index].toDouble(), rate, row.storedAtMs)
        }
    }

    /**
     * Rate from the nearest earlier reading far enough back for [ExchangeTrend.deriveRate] to
     * accept: minute-spaced rows are too close together to say anything about direction.
     */
    private fun derivedRate(rows: List<Row>, mgdl: List<Float>, index: Int): Float {
        val row = rows[index]
        for (earlier in index - 1 downTo 0) {
            if (row.timeMs - rows[earlier].timeMs > MAX_RATE_LOOKBACK_MS) break
            val rate = ExchangeTrend.deriveRate(
                Math.round(mgdl[earlier]), rows[earlier].timeMs, Math.round(mgdl[index]), row.timeMs,
            )
            if (!rate.isNaN()) return rate
        }
        return Float.NaN
    }

    private fun viewMode(sensorSerial: String): Int =
        runCatching { Natives.getSensorUiSnapshot(sensorSerial)?.takeIf { it.size >= 2 }?.get(1)?.toInt() }
            .getOrNull() ?: 0

    private fun persist(id: String) {
        runCatching {
            val coverage = tracker.coverage(id, System.currentTimeMillis()) ?: return
            prefs()?.edit()?.putString(id, XdripBackfillCoverageFormat.encode(coverage))?.apply()
        }.onFailure { Log.stack(LOG_ID, "persist", it) }
    }

    private fun restore(id: String): XdripBackfillTracker.Sensor? {
        val coverage = runCatching { XdripBackfillCoverageFormat.decode(prefs()?.getString(id, null)) }
            .getOrNull() ?: return null
        tracker.restore(id, coverage)
        return tracker.sensor(id)
    }

    private fun prefs() = Applic.app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
