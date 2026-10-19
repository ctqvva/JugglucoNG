package tk.glucodata

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * "HH:mm" for a timestamp, in the default locale and time zone, at the cost
 * of a table lookup.
 *
 * The history repository formats every reading it emits, and Room emits the
 * whole table on every insert, so this runs once per stored reading per
 * minute. It was cached by minute in an 8192-entry LRU, which is about five
 * and a half days at one reading a minute: past that the emission walks the
 * table end to end and evicts every entry before it is asked for again, and
 * the cache misses on every reading. Exactly the SimpleDateFormat-per-point
 * cost it was written to remove, back as soon as the store outgrew it.
 *
 * "HH:mm" depends on the timestamp only through the local hour and minute,
 * so there are 1440 possible outputs. The table is keyed by local minute of
 * the day and filled by SimpleDateFormat itself on a miss — the string for
 * a minute is what SimpleDateFormat produced for a real instant in that
 * minute, so digit shaping, locale and calendar quirks are all its own — and
 * once full it never misses, whatever the size of the store. It is rebuilt
 * when the locale or the zone changes, since both change what a minute
 * formats to.
 */
object MinuteTimeFormat {
    private const val MINUTES_PER_DAY = 1440
    private val lock = Any()
    private var locale: Locale? = null
    private var zoneId: String? = null
    private var formatter: SimpleDateFormat? = null
    private val byLocalMinute = arrayOfNulls<String>(MINUTES_PER_DAY)

    @JvmStatic
    fun format(timestampMillis: Long): String {
        val currentLocale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        val localMinute = Math.floorMod(
            Math.floorDiv(timestampMillis + zone.getOffset(timestampMillis), 60_000L),
            MINUTES_PER_DAY.toLong()
        ).toInt()
        synchronized(lock) {
            if (locale != currentLocale || zoneId != zone.id) {
                locale = currentLocale
                zoneId = zone.id
                formatter = SimpleDateFormat("HH:mm", currentLocale)
                byLocalMinute.fill(null)
            } else {
                byLocalMinute[localMinute]?.let { return it }
            }
            val format = formatter ?: SimpleDateFormat("HH:mm", currentLocale).also { formatter = it }
            return format.format(Date(timestampMillis)).also { byLocalMinute[localMinute] = it }
        }
    }
}
