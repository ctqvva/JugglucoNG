package tk.glucodata

import android.content.Context

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

    data class Row(val timeMs: Long, val value: Float, val rawValue: Float, val rate: Float?)

    private val tracker = XdripBackfillTracker()

    private fun key(sensorId: String): String = SensorIdentity.resolveAppSensorId(sensorId) ?: sensorId

    /** A live reading went out on the xDrip broadcast. */
    @JvmStatic
    fun recordLiveSent(sensorId: String?, timeMs: Long, sensorGen: Int, sensorStartMs: Long) {
        if (sensorId.isNullOrEmpty() || timeMs <= 0L) return
        val id = key(sensorId)
        // Bring back the saved cutoff first: after a restart the first live send would otherwise
        // become the cutoff and the whole gap before it would never be backfilled.
        if (tracker.sensor(id) == null) restore(id)
        tracker.recordSent(id, timeMs, sensorGen, sensorStartMs)
        runCatching {
            prefs()?.edit()?.putString(id, "$timeMs,$sensorGen,$sensorStartMs")?.apply()
        }.onFailure { Log.stack(LOG_ID, "persist", it) }
    }

    /** History rows for [sensorSerial] were written. Sends the ones that live never carried. */
    @JvmStatic
    fun onHistoryStored(sensorSerial: String, rows: List<Row>) {
        if (rows.isEmpty() || sensorSerial.isEmpty()) return
        try {
            if (!Natives.getxbroadcast() || !isMainSensor(sensorSerial)) return
            val id = key(sensorSerial)
            val sensor = tracker.sensor(id) ?: restore(id) ?: return
            val app = Applic.app ?: return
            val viewMode = viewMode(sensorSerial)
            val candidates = buildCandidates(
                sensorSerial, rows, viewMode, DataSmoothing.exchangeSmoothingMinutes(app),
            )
            val spacingMs = DataSmoothing.exchangeThrottleIntervalMinutes(app) * 60_000L
            val picked = tracker.pick(id, candidates, System.currentTimeMillis(), spacingMs)
            if (picked.isEmpty()) return
            if (Log.doLog) Log.i(LOG_ID, "backfill ${picked.size} readings sensor=$id")
            for (point in picked) {
                SendLikexDrip.broadcastBackfill(id, sensor.sensorGen, point.mgdl, point.timeMs, point.rate, sensor.sensorStartMs)
            }
        } catch (error: Throwable) {
            Log.stack(LOG_ID, "onHistoryStored", error)
        }
    }

    /** Same rule as the live path: a deselected sensor stores history but exchanges nothing. */
    private fun isMainSensor(sensorSerial: String): Boolean {
        val main = runCatching { SensorIdentity.resolveLiveMainSensor(sensorSerial) }.getOrNull()
        return main.isNullOrEmpty() || SensorIdentity.matches(sensorSerial, main)
    }

    /**
     * The values a live reading would have carried: the primary lane, smoothed when exchange
     * smoothing is on, then calibrated. Stored rows are mg/dL, and so is every step here;
     * [NightscoutCalibration] does the display-unit conversion the calibration model needs.
     * A stored rate of 0 is the "no rate" placeholder of backfilled history, so the rate is
     * derived from an earlier reading instead, or left unknown.
     */
    private fun buildCandidates(
        sensorSerial: String,
        rows: List<Row>,
        viewMode: Int,
        smoothingMinutes: Int,
    ): List<XdripBackfillTracker.Candidate> {
        val rawPrimary = viewMode == RAW_ONLY_VIEW_MODE || viewMode == RAW_PRIMARY_VIEW_MODE
        val valid = rows.sortedBy(Row::timeMs).filter {
            val base = if (rawPrimary) it.rawValue else it.value
            base.isFinite() && base > 0f
        }
        val base = valid.map { if (rawPrimary) it.rawValue else it.value }
        val smoothed = if (smoothingMinutes > 0 && valid.size >= MIN_SMOOTHING_POINTS) {
            GlucoseSmoothing.smoothLane(valid, smoothingMinutes * 30_000L, Row::timeMs) {
                if (rawPrimary) it.rawValue else it.value
            }.toList()
        } else {
            base
        }
        val shown = valid.mapIndexed { index, row ->
            val primary = smoothed[index]
            val calibrated = NightscoutCalibration.getCalibratedValueForViewMode(
                sensorSerial, viewMode,
                if (rawPrimary) row.value else primary,
                if (rawPrimary) primary else row.rawValue,
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

    private fun restore(id: String): XdripBackfillTracker.Sensor? {
        val parts = runCatching { prefs()?.getString(id, null)?.split(',') }.getOrNull() ?: return null
        if (parts.size != 3) return null
        val time = parts[0].toLongOrNull() ?: return null
        tracker.restore(id, time, parts[1].toIntOrNull() ?: 0, parts[2].toLongOrNull() ?: 0L)
        return tracker.sensor(id)
    }

    private fun prefs() = Applic.app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
