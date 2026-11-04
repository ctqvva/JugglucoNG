package tk.glucodata.glucosemeter

import android.content.Context
import tk.glucodata.Applic

/**
 * How soon a Bluetooth meter that never sleeps is dialled again after a link that brought
 * nothing new.
 *
 * The Satellite (#798) advertises all the time and is back within a second of every idle link,
 * so redialling it at once re-read it twice a minute for as long as it was in range. Every other
 * meter sleeps between measurements and advertises only briefly after one; it is always
 * redialled at once, since a closed transport during that window would leave the reading on the
 * meter until the next measurement. Which meters stay on is known per protocol, not guessed from
 * timing: a sleeping meter can keep answering for minutes after a fingerstick. The interval is
 * also how much later a fingerstick on such a meter can arrive.
 */
object MeterSyncPacing {
    private const val PREFS = "tk.glucodata_preferences"
    private const val KEY_MINUTES = "glucose_meter_recheck_minutes"

    const val DEFAULT_MINUTES = 5

    /** 0 reconnects at once, as before. */
    @JvmField
    val CHOICES_MINUTES: List<Int> = listOf(0, 5, 15, 30)

    @JvmStatic
    fun minutes(): Int {
        val stored = Applic.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MINUTES, DEFAULT_MINUTES)
        return if (stored in CHOICES_MINUTES) stored else DEFAULT_MINUTES
    }

    @JvmStatic
    fun setMinutes(minutes: Int) {
        if (minutes !in CHOICES_MINUTES) return
        Applic.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MINUTES, minutes)
            .apply()
    }

    /**
     * Whether a Record Access Control Point indication closes a Report Stored Records request
     * successfully: records sent (`06 00 01 01`) or none stored (`06 00 01 06`). Every request
     * GlucoseMeterGatt writes is that op code, so anything else is a failed transfer.
     */
    @JvmStatic
    fun racpReportSucceeded(value: ByteArray?): Boolean =
        value != null && value.size >= 4 &&
            value[0] == 0x06.toByte() && value[1] == 0x00.toByte() && value[2] == 0x01.toByte() &&
            (value[3] == 0x01.toByte() || value[3] == 0x06.toByte())
}

/** How a link to a meter ended. */
enum class MeterLinkOutcome {
    /** Dropped before the meter answered the record request. */
    UNANSWERED,
    /** Stored at least one reading we did not have. */
    NEW_READINGS,
    /** The meter answered and had nothing we did not have. */
    NOTHING_NEW,
    /** The meter answered with an error, or something we could not read. */
    FAILED,
}

/** One per meter. Not thread-safe: the caller holds the meter's lock. */
class MeterLinkPacer {
    private var failuresInARow = 0

    /**
     * Records a link that ended and returns how long to wait before dialling again. Only a meter
     * that stays on ([staysOn]) ever waits. A single failure is retried at once; one that repeats
     * (a wrong Satellite code) waits instead of being redialled and toasted twice a minute.
     */
    fun linkEnded(outcome: MeterLinkOutcome, staysOn: Boolean, minutes: Int): Long {
        failuresInARow = if (outcome == MeterLinkOutcome.FAILED) failuresInARow + 1 else 0
        if (!staysOn || minutes <= 0) return 0L
        val wait = minutes * 60_000L
        return when (outcome) {
            MeterLinkOutcome.NOTHING_NEW -> wait
            MeterLinkOutcome.FAILED -> if (failuresInARow >= 2) wait else 0L
            MeterLinkOutcome.NEW_READINGS, MeterLinkOutcome.UNANSWERED -> 0L
        }
    }
}
