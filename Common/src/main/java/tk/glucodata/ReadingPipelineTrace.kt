package tk.glucodata

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One trace line per stage a live reading passes on its way to the dashboard, so a trace shows
 * where a minute waited: when the sensor's frame arrived and what time it was given, when Room
 * committed it, when the dashboard's history flow emitted it, when the chart resolved it, and
 * which reading the dashboard then showed.
 *
 * Each stage logs only when its newest reading changes, so a steady stream costs a few lines a
 * minute. "after arrival" is measured from the frame that carried that reading; a stage whose
 * newest reading is not a live frame (a collapsed or smoothed point, history) logs without it.
 */
object ReadingPipelineTrace {
    private const val TAG = "Pipeline"
    private const val REMEMBERED_ARRIVALS = 32

    private val arrivals = object : LinkedHashMap<Long, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean = size > REMEMBERED_ARRIVALS
    }
    private val lastLogged = HashMap<String, Any>()

    private fun clock(ms: Long): String =
        if (ms <= 0L) "-" else SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(ms))

    private fun sinceArrival(sampleMs: Long, atMs: Long): String =
        synchronized(arrivals) { arrivals[sampleMs] }?.let { " ${atMs - it}ms after arrival" } ?: ""

    /** Logs [line] unless this [stage] already logged [key] last time. */
    private inline fun once(stage: String, key: Any, line: () -> String) {
        if (!Log.doLog) return
        synchronized(lastLogged) {
            if (lastLogged[stage] == key) return
            lastLogged[stage] = key
        }
        Log.i(TAG, line())
    }

    /** A driver dated a live frame: [lifeCount] arrived at [arrivalMs] and was given [sampleMs]. */
    @JvmStatic
    fun live(sensorId: String, lifeCount: Int, arrivalMs: Long, sampleMs: Long) {
        synchronized(arrivals) { arrivals[sampleMs] = arrivalMs }
        once("live:$sensorId", sampleMs) {
            "live $sensorId life=$lifeCount arrived=${clock(arrivalMs)} sample=${clock(sampleMs)} " +
                "sample-arrival=${sampleMs - arrivalMs}ms"
        }
    }

    /** Room committed readings of [sensorId] up to [newestSampleMs]. */
    @JvmStatic
    fun roomCommitted(sensorId: String, newestSampleMs: Long, count: Int) {
        val now = System.currentTimeMillis()
        once("room:$sensorId", newestSampleMs) {
            "room $sensorId committed $count, newest sample=${clock(newestSampleMs)} at ${clock(now)}" +
                sinceArrival(newestSampleMs, now)
        }
    }

    /** The dashboard's history flow handed the chart a list ending at [newestMs]. */
    @JvmStatic
    fun historyEmitted(newestMs: Long, size: Int) {
        val now = System.currentTimeMillis()
        once("history", newestMs) {
            "history emitted $size, newest=${clock(newestMs)} at ${clock(now)}" + sinceArrival(newestMs, now)
        }
    }

    /**
     * The chart finished resolving inputs ending at [newestMs] after [tookMs]; [superseded]
     * newer inputs had restarted it since the last resolution it published.
     */
    @JvmStatic
    fun chartResolved(newestMs: Long, tookMs: Long, superseded: Int) {
        val now = System.currentTimeMillis()
        once("chart", newestMs) {
            "chart resolved newest=${clock(newestMs)} at ${clock(now)} took=${tookMs}ms restarts=$superseded" +
                sinceArrival(newestMs, now)
        }
    }

    /**
     * The dashboard shows the reading at [snapshotMs] ([source], [value]) and its chart list ends at
     * [chartNewestMs]; resolving the shown reading took [resolveMs] on the main thread. The
     * smoothing settings that can hold either back are logged with it.
     */
    @JvmStatic
    fun displayed(snapshotMs: Long, value: String?, source: String?, chartNewestMs: Long, resolveMs: Long) {
        val now = System.currentTimeMillis()
        once("display", snapshotMs to chartNewestMs) {
            "display shows ${value ?: "-"} sample=${clock(snapshotMs)} ($source) chart newest=${clock(chartNewestMs)} " +
                "at ${clock(now)}" + sinceArrival(snapshotMs, now) + " resolve=${resolveMs}ms " + smoothing()
        }
    }

    /** Data smoothing as it applies to the current value and the chart: "fewer" holds either back by design. */
    private fun smoothing(): String {
        val context = Applic.app ?: return ""
        return runCatching {
            "smoothing=${DataSmoothing.getMinutes(context)}min" +
                " current(smooth=${DataSmoothing.localSmoothingMinutes(context)} fewer=${DataSmoothing.localCollapseMinutes(context)})" +
                " chart(smooth=${DataSmoothing.graphSmoothingMinutes(context)} fewer=${DataSmoothing.graphCollapseMinutes(context)})" +
                " causal=${DataSmoothing.useCausalFilter(context)}"
        }.getOrDefault("")
    }
}
