// MedtrumSensorIdentity.kt — sensor life window and short-name aliases, free of Android types
// so the rules can be tested without a driver.

package tk.glucodata.drivers.medtrum

/**
 * The patch reports the seconds since insertion in every readState record; EasySense counts its
 * 14 days down from that same value (checked against the vendor app: 5 h 56 min elapsed showed
 * "13 d 18 h 4 min" left).
 */
object MedtrumSensorLife {

    const val LIFETIME_MS = 14L * 24L * 60L * 60L * 1000L

    /** Elapsed time shorter than this is a patch that has just been inserted or a bad record. */
    private const val MIN_ELAPSED_SECONDS = 1L

    /** A recomputed start within this of the stored one is the same start with a few seconds of jitter. */
    internal const val RESTART_TOLERANCE_MS = 2L * 60L * 1000L

    fun startFromElapsed(nowMs: Long, elapsedSeconds: Long): Long {
        if (elapsedSeconds < MIN_ELAPSED_SECONDS) return 0L
        return nowMs - elapsedSeconds * 1000L
    }

    fun endFromStart(startMs: Long): Long = if (startMs > 0L) startMs + LIFETIME_MS else 0L

    /**
     * Store a new start when none is known, or when it moved by more than the jitter (a patch that
     * was replaced under the same serial). Every readState would otherwise rewrite the prefs.
     */
    fun shouldReplaceStart(storedMs: Long, candidateMs: Long): Boolean {
        if (candidateMs <= 0L) return false
        if (storedMs <= 0L) return true
        return kotlin.math.abs(candidateMs - storedMs) > RESTART_TOLERANCE_MS
    }

    fun ageHours(startMs: Long, nowMs: Long): Int =
        if (startMs > 0L && nowMs >= startMs) ((nowMs - startMs) / 3_600_000L).toInt() else -1

    fun remainingHours(startMs: Long, nowMs: Long): Int {
        if (startMs <= 0L) return -1
        val left = endFromStart(startMs) - nowMs
        return if (left > 0L) (left / 3_600_000L).toInt() else 0
    }
}

/**
 * The serial of the transmitter outlives one disposable sensor, so a replaced sensor is told apart
 * by its insertion. Software calibration belongs to the electrode it was taken on: carried over, an
 * old single-point offset would be applied to the replacement until the user cleared it.
 */
object MedtrumSensorSession {

    fun key(sensorId: Int, insertTime: Long): String = "$sensorId:$insertTime"

    /**
     * A new disposable sensor needs both signs: the insertion key changed, and the elapsed time
     * restarted, so the recomputed start is later than the stored one by more than clock jitter.
     * Either alone could be a drifting counter, and archiving calibrations on a false positive
     * would silently drop the user's work.
     */
    fun isNewSession(storedKey: String, key: String, storedStartMs: Long, candidateStartMs: Long): Boolean {
        if (storedKey.isBlank() || storedKey == key) return false
        if (storedStartMs <= 0L || candidateStartMs <= 0L) return false
        return candidateStartMs - storedStartMs > MedtrumSensorLife.RESTART_TOLERANCE_MS
    }

    /** The id the previous session's calibrations are filed under, which no live sensor matches. */
    fun archiveId(serial: String, previousStartMs: Long): String = "$serial@$previousStartMs"
}

/**
 * The native layer keeps a mirror of a patch under a shortened name: the serial 2329895446 came
 * back as `95446`. Unrecognised, that name was built into a generic Libre callback, which showed
 * up as a second, phantom sensor.
 *
 * Only that name is an alias. Every valid serial has at least [MIN_SERIAL_LENGTH] digits, so a
 * five-digit name cannot be another patch's full serial, whereas treating any shorter numeric
 * ending as the same patch would merge `2329895446` with `329895446`.
 */
object MedtrumSerialAlias {

    const val NATIVE_SHORT_NAME_LENGTH = 5
    const val MIN_SERIAL_LENGTH = 6

    fun isShortForm(candidate: String?, serial: String?): Boolean {
        val short = candidate?.trim().orEmpty()
        val full = serial?.trim().orEmpty()
        if (short.length != NATIVE_SHORT_NAME_LENGTH || full.length < MIN_SERIAL_LENGTH) return false
        if (!short.all { it.isDigit() } || !full.all { it.isDigit() }) return false
        return full.endsWith(short)
    }
}
