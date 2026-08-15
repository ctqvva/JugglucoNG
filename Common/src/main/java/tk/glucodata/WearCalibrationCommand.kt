package tk.glucodata

import java.nio.ByteBuffer
import tk.glucodata.Log.doLog

/**
 * Calibration actions performed on the watch and executed on the phone, where
 * CalibrationManager lives. The watch can already enter a fingerstick value
 * (CALIBRATE_PATH); this covers the rest of the phone's calibration card so the
 * two surfaces offer the same controls.
 *
 * Wire format: [u8 command] followed, for the per-entry commands, by the
 * timestamp of the calibration being acted on (the watch never sees Room ids),
 * the fingerstick value in mg/dL for [ADD] and [EDIT], and the sensor's own
 * uncorrected value at that timestamp where this device produced the reading.
 *
 * That last field is what lets a calibration taken on the watch be fitted at
 * all. For a driver that folds the correction into what it stores, the model
 * runs on stock values, and the phone recovers them by matching against its own
 * replayed source history — which stops growing the moment the watch takes the
 * sensor. Only the device that computed the reading knows it, so it sends it.
 */
object WearCalibrationCommand {
    private const val LOG_ID = "WearCalibrationCmd"

    const val ENABLE = 1
    const val DISABLE = 2
    const val CLEAR = 3
    const val DELETE = 4
    const val EDIT = 5
    const val ADD = 6

    /**
     * Watch side: ask the phone to perform [command].
     *
     * @return whether the message reached the transport. Reporting this matters:
     * the screens used to treat "attempted" as "saved" and closed on a command
     * that was never sent.
     */
    @JvmStatic
    @JvmOverloads
    fun send(command: Int, timestamp: Long = 0L, userValueMgdl: Float = Float.NaN): Boolean {
        if (!Applic.isWearable) return false
        return runCatching {
            val stockMgdl = stockValueMgdlAt(timestamp)
            val buf = ByteBuffer.allocate(17)
            buf.put(command.toByte())
            buf.putLong(timestamp)
            buf.putFloat(userValueMgdl)
            buf.putFloat(stockMgdl)
            val sent = MessageSender.sendSyncMessage(MessageSender.CALIBRATION_CMD_PATH, buf.array())
            if (doLog) {
                Log.i(
                    LOG_ID,
                    "calibration command $command ts=$timestamp value=$userValueMgdl stock=$stockMgdl sent=$sent",
                )
            }
            if (!sent) Log.w(LOG_ID, "no wear transport for calibration command $command")
            sent
        }.onFailure { Log.stack(LOG_ID, "send($command)", it) }.getOrDefault(false)
    }

    /**
     * The stock value this device computed for [timestamp], in mg/dL, or NaN
     * when it did not produce that reading — in which case the phone falls back
     * to matching against its own history, as before.
     */
    private fun stockValueMgdlAt(timestamp: Long): Float {
        if (timestamp <= 0L) return Float.NaN
        val sensorId = runCatching { SensorIdentity.resolveMainSensor() }.getOrNull() ?: return Float.NaN
        val stock = IntegratedStockBaseline.stockAt(sensorId, timestamp)
        if (!stock.isFinite() || stock <= 0f) return Float.NaN
        // Stored calibrations are in the display unit; the wire is canonical.
        return if (runCatching { Applic.unit == 1 }.getOrDefault(false)) stock * MGDL_PER_MMOL else stock
    }

    private const val MGDL_PER_MMOL = 18.0182f

    /** Phone side: execute a command relayed from the watch. */
    @JvmStatic
    fun onCommand(data: ByteArray?) {
        if (Applic.isWearable || data == null || data.isEmpty()) return
        val buf = ByteBuffer.wrap(data)
        val command = buf.get().toInt()
        val timestamp = if (buf.remaining() >= 8) buf.long else 0L
        val userValue = if (buf.remaining() >= 4) buf.float else Float.NaN
        // Absent from an older watch's message; unknown, not zero.
        val stockValue = if (buf.remaining() >= 4) buf.float else Float.NaN

        val applied = runCatching {
            when (command) {
                ENABLE -> CalibrationAccess.setEnabled(true)
                DISABLE -> CalibrationAccess.setEnabled(false)
                CLEAR -> CalibrationAccess.clearAll()
                DELETE -> timestamp > 0L && CalibrationAccess.deleteCalibrationAt(timestamp)
                ADD -> GlucoseValuePlausibility.isPlausibleMgdl(userValue) &&
                    CalibrationAccess.addCalibration(userValue, timestamp, stockValue)
                EDIT -> timestamp > 0L &&
                    GlucoseValuePlausibility.isPlausibleMgdl(userValue) &&
                    CalibrationAccess.updateCalibrationUserValue(timestamp, userValue)
                else -> false
            }
        }.onFailure { Log.stack(LOG_ID, "onCommand($command)", it) }.getOrDefault(false)

        Log.i(LOG_ID, "calibration command $command ts=$timestamp stock=$stockValue applied=$applied")
        if (applied) {
            UiRefreshBus.requestDataRefresh()
            WearSync2.onCalibrationChanged()
        }
    }
}
