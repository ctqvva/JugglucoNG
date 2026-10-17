package tk.glucodata.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tk.glucodata.Applic
import tk.glucodata.SensorIdentity

/**
 * When each sensor's main line switched between the auto and the raw lane.
 *
 * "Keep displayed values" promises that a raw/auto switch does not rewrite the
 * main line. The record ([ReadingDisplay]) keeps that promise for every minute
 * that was put on screen, but only for those: a minute nobody had looked at yet
 * used to follow whichever lane was selected when it was first drawn. Switching
 * and then opening a wider range froze the newly drawn stretch in the new lane
 * beside a stretch frozen in the old one, so the line jumped at the edges of
 * whatever had happened to be on screen before.
 *
 * This log closes that gap. A minute with no record is shown in the lane that
 * was selected when it sealed — the value the dashboard showed, or would have
 * shown, then — so a switch moves the grace hour and everything after it, as a
 * calibration does, and the sealed past keeps one lane per stretch whatever is
 * viewed afterwards.
 *
 * Only lane changes are logged: modes 0 and 2 both put auto on the main line,
 * 1 and 3 raw. Every user-facing switch goes through
 * [tk.glucodata.drivers.ManagedSensorViewModeStore.write], which logs here.
 * The log is cleared whenever "Keep displayed values" is turned on, so turning
 * it on changes nothing on screen.
 */
object DisplayLaneHistory {

    /** One switch: from [atMs] on, the main line moved away from [fromMode]. */
    data class Switch(val atMs: Long, val fromMode: Int, val toMode: Int)

    private const val PREFS_NAME = "display_lane_history"
    private const val KEY_PREFIX = "switches_"

    /** Switches kept per sensor; older ones fall off. */
    internal const val MAX_SWITCHES = 256

    private val lock = Any()
    private var cache: MutableMap<String, List<Switch>>? = null

    private val _revision = MutableStateFlow(0L)

    /** Bumps on every logged switch and on [clear], so history flows re-emit. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun isLaneChange(fromMode: Int, toMode: Int): Boolean = (fromMode and 1) != (toMode and 1)

    /**
     * The view mode in force at [timestampMs], if the sensor has switched lanes
     * since then; null when it has not, and the current mode applies.
     */
    fun viewModeAt(sensorId: String?, timestampMs: Long, context: Context? = Applic.app): Int? =
        viewModeAt(switchesFor(sensorId, context), timestampMs)

    /** The first switch after [timestampMs] says what was on before it. */
    internal fun viewModeAt(switches: List<Switch>, timestampMs: Long): Int? {
        if (switches.isEmpty() || switches.last().atMs <= timestampMs) return null
        var low = 0
        var high = switches.lastIndex
        while (low < high) {
            val mid = (low + high) ushr 1
            if (switches[mid].atMs > timestampMs) high = mid else low = mid + 1
        }
        return switches[low].fromMode
    }

    fun switchesFor(sensorId: String?, context: Context? = Applic.app): List<Switch> {
        val id = sensorId?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val all = loaded(context) ?: return emptyList()
        all[id]?.let { return it }
        return all.entries.firstOrNull { (key, _) -> SensorIdentity.matches(id, key) }?.value.orEmpty()
    }

    /** Called when the stored view mode of [sensorId] changes from [fromMode] to [toMode]. */
    fun noteSwitch(
        sensorId: String?,
        fromMode: Int,
        toMode: Int,
        atMs: Long = System.currentTimeMillis(),
        context: Context? = Applic.app,
    ) {
        if (!isLaneChange(fromMode, toMode)) return
        val id = sensorId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val prefs = prefs(context) ?: return
        synchronized(lock) {
            val all = loaded(context) ?: return
            val updated = appended(all[id].orEmpty(), Switch(atMs, fromMode, toMode))
            all[id] = updated
            prefs.edit().putString(KEY_PREFIX + id, encode(updated)).apply()
        }
        _revision.value = _revision.value + 1
    }

    fun clear(context: Context? = Applic.app) {
        val prefs = prefs(context) ?: return
        synchronized(lock) {
            prefs.edit().clear().apply()
            cache = HashMap()
        }
        _revision.value = _revision.value + 1
    }

    /** Appends in time order; a clock that stepped back cannot reorder the log. */
    internal fun appended(switches: List<Switch>, next: Switch): List<Switch> {
        val atMs = maxOf(next.atMs, switches.lastOrNull()?.atMs ?: Long.MIN_VALUE)
        return (switches + next.copy(atMs = atMs)).takeLast(MAX_SWITCHES)
    }

    internal fun encode(switches: List<Switch>): String =
        switches.joinToString(";") { "${it.atMs}:${it.fromMode}:${it.toMode}" }

    internal fun decode(text: String?): List<Switch> =
        text.orEmpty().split(';').mapNotNull { entry ->
            val parts = entry.split(':')
            if (parts.size != 3) return@mapNotNull null
            val atMs = parts[0].toLongOrNull() ?: return@mapNotNull null
            val from = parts[1].toIntOrNull()?.takeIf { it in 0..3 } ?: return@mapNotNull null
            val to = parts[2].toIntOrNull()?.takeIf { it in 0..3 } ?: return@mapNotNull null
            Switch(atMs, from, to)
        }.sortedBy { it.atMs }

    /** For tests. */
    internal fun resetCache() = synchronized(lock) { cache = null }

    private fun loaded(context: Context?): MutableMap<String, List<Switch>>? = synchronized(lock) {
        cache?.let { return it }
        val prefs = prefs(context) ?: return null
        val read = HashMap<String, List<Switch>>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(KEY_PREFIX) && value is String) {
                read[key.removePrefix(KEY_PREFIX)] = decode(value)
            }
        }
        cache = read
        read
    }

    private fun prefs(context: Context?) =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
