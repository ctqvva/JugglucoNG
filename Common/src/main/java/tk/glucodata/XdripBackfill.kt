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

    data class Row(val timeMs: Long, val value: Float, val rawValue: Float, val rate: Float?)

    private val tracker = XdripBackfillTracker()

    private fun key(sensorId: String): String = SensorIdentity.resolveAppSensorId(sensorId) ?: sensorId

    /** A live reading went out on the xDrip broadcast. */
    @JvmStatic
    fun recordLiveSent(sensorId: String?, timeMs: Long, sensorGen: Int, sensorStartMs: Long) {
        if (sensorId.isNullOrEmpty() || timeMs <= 0L) return
        val id = key(sensorId)
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
            if (!Natives.getxbroadcast()) return
            val id = key(sensorSerial)
            val sensor = tracker.sensor(id) ?: restore(id) ?: return
            val app = Applic.app ?: return
            val isRawPrimary = rawPrimary(sensorSerial)
            val candidates = rows.mapNotNull { row ->
                val base = if (isRawPrimary) row.rawValue else row.value
                if (!base.isFinite() || base <= 0f) return@mapNotNull null
                val shown = CalibrationAccess.getCalibratedValue(base, row.timeMs, isRawPrimary, false, sensorSerial)
                XdripBackfillTracker.Candidate(row.timeMs, shown.toDouble(), row.rate ?: Float.NaN)
            }
            val spacingMs = DataSmoothing.exchangeThrottleIntervalMinutes(app) * 60_000L
            val picked = tracker.pick(id, candidates, System.currentTimeMillis(), spacingMs)
            if (picked.isEmpty()) return
            if (Log.doLog) Log.i(LOG_ID, "backfill ${picked.size} readings sensor=$id")
            var previous: XdripBackfillTracker.Candidate? = null
            for (point in picked) {
                val rate = when {
                    point.rate.isFinite() -> point.rate
                    previous != null -> ExchangeTrend.deriveRate(
                        previous.mgdl.toInt(), previous.timeMs, point.mgdl.toInt(), point.timeMs,
                    )
                    else -> Float.NaN
                }
                SendLikexDrip.broadcastBackfill(id, sensor.sensorGen, point.mgdl, point.timeMs, rate, sensor.sensorStartMs)
                previous = point
            }
        } catch (error: Throwable) {
            Log.stack(LOG_ID, "onHistoryStored", error)
        }
    }

    private fun rawPrimary(sensorSerial: String): Boolean {
        val viewMode = runCatching { Natives.getSensorUiSnapshot(sensorSerial)?.takeIf { it.size >= 2 }?.get(1)?.toInt() }
            .getOrNull() ?: 0
        return viewMode == RAW_ONLY_VIEW_MODE || viewMode == RAW_PRIMARY_VIEW_MODE
    }

    private fun restore(id: String): XdripBackfillTracker.Sensor? {
        val parts = runCatching { prefs()?.getString(id, null)?.split(',') }.getOrNull() ?: return null
        if (parts.size != 3) return null
        val time = parts[0].toLongOrNull() ?: return null
        tracker.restore(id, time, parts[1].toIntOrNull() ?: 0, parts[2].toLongOrNull() ?: 0L)
        return tracker.sensor(id)
    }

    private fun prefs() = Applic.app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
