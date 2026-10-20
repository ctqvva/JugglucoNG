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

    data class Row(val timeMs: Long, val value: Float, val rawValue: Float, val rate: Float?)

    /** One stored reading with the mg/dL a live send would carry for it. */
    data class DisplayedRow(val timeMs: Long, val mgdl: Int)

    private class Pending(var sensorSerial: String) {
        val candidates = HashMap<Long, XdripBackfillTracker.Candidate>()
        var flushScheduled = false
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
        // Rows held back for being newer than the previous live send can be decided now.
        if (synchronized(pending) { pending[id]?.let { it.candidates.isNotEmpty() && claimFlush(it) } == true }) {
            scheduleFlush(id)
        }
    }

    /**
     * History rows for [sensorSerial] were written. The ones live never carried are sent
     * [PROCESS_DELAY_MS] later, batched. The history row of a reading is stored just before that
     * reading goes out live, so a row newer than the last live send is held by the tracker and
     * offered again after the next live send, which either carried it or skipped it.
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
            )
            if (computed.isEmpty()) return
            val schedule = synchronized(pending) {
                val queued = pending.getOrPut(id) { Pending(sensorSerial) }
                queued.sensorSerial = sensorSerial
                computed.forEach { queued.candidates[it.timeMs] = it }
                claimFlush(queued)
            }
            if (schedule) scheduleFlush(id)
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
            candidates(
                sensorSerial, rows, viewMode(sensorSerial),
                DataSmoothing.exchangeSmoothingMinutes(app),
            ).map { DisplayedRow(it.timeMs, Math.round(it.mgdl).toInt()) }
        } catch (error: Throwable) {
            Log.stack(LOG_ID, "displayedRows", error)
            emptyList()
        }
    }

    /** Caller holds the [pending] lock. True when the caller is the one to schedule the flush. */
    private fun claimFlush(queued: Pending): Boolean {
        if (queued.flushScheduled) return false
        queued.flushScheduled = true
        return true
    }

    private fun scheduleFlush(id: String) {
        Applic.scheduler.schedule({ flush(id) }, PROCESS_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun flush(id: String) {
        try {
            val (sensorSerial, batch) = synchronized(pending) {
                val queued = pending[id] ?: return
                queued.flushScheduled = false
                val batch = queued.candidates.values.toList()
                queued.candidates.clear()
                queued.sensorSerial to batch
            }
            // The user may have picked another sensor during the delay.
            if (!Natives.getxbroadcast() || !isMainSensor(sensorSerial)) return
            val app = Applic.app ?: return
            val sensor = tracker.sensor(id) ?: return
            val spacingMs = DataSmoothing.exchangeThrottleIntervalMinutes(app) * 60_000L
            val result = tracker.pick(id, batch, System.currentTimeMillis(), spacingMs)
            if (result.held.isNotEmpty()) {
                // A newer copy of a row may have been queued while this ran; that one wins. A live
                // send in the meantime found nothing queued, so it falls to this to flush again.
                val oldestHeldMs = result.held.minOf { it.timeMs }
                val reschedule = synchronized(pending) {
                    val queued = pending.getOrPut(id) { Pending(sensorSerial) }
                    result.held.forEach { queued.candidates.putIfAbsent(it.timeMs, it) }
                    (tracker.sensor(id)?.liveEdgeMs ?: 0L) >= oldestHeldMs && claimFlush(queued)
                }
                if (reschedule) scheduleFlush(id)
            }
            val picked = result.send
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

    /**
     * The values a live reading would have carried: the primary lane, smoothed when exchange
     * smoothing is on, then calibrated. In the raw view modes a row without a raw value falls back
     * to its auto value, as the live display does. Stored rows are mg/dL, and so is every step
     * here; [NightscoutCalibration] does the display-unit conversion the calibration model needs.
     * A stored rate of 0 is the "no rate" placeholder of backfilled history, so the rate is
     * derived from an earlier reading instead, or left unknown.
     */
    private fun candidates(
        sensorSerial: String,
        rows: List<Row>,
        viewMode: Int,
        smoothingMinutes: Int,
    ): List<XdripBackfillTracker.Candidate> {
        val rawPrimary = viewMode == RAW_ONLY_VIEW_MODE || viewMode == RAW_PRIMARY_VIEW_MODE
        fun usesRaw(row: Row) = rawPrimary && row.rawValue.isFinite() && row.rawValue > 0f
        fun base(row: Row) = if (usesRaw(row)) row.rawValue else row.value
        val valid = rows.sortedBy(Row::timeMs).filter { base(it).isFinite() && base(it) > 0f }
        val smoothed = if (smoothingMinutes > 0 && valid.size >= MIN_SMOOTHING_POINTS) {
            GlucoseSmoothing.smoothLane(valid, smoothingMinutes * 30_000L, Row::timeMs, ::base).toList()
        } else {
            valid.map(::base)
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
            XdripBackfillTracker.Candidate(row.timeMs, shown[index].toDouble(), rate)
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
