package tk.glucodata.chart

/**
 * The resolved chart: what to draw, already decided.
 *
 * Every renderer of glucose history — the dashboard, the notification, the
 * widget, the watch — consumes this and paints it. None of them resolves a
 * value or decides who the main sensor is. That resolution is not something a
 * renderer can safely reproduce from whatever repository state exists at the
 * time it happens to paint: a recorded value has to win over today's
 * calibration, the main sensor at a minute is a fact the record holds, and
 * both were repeatedly got wrong when each surface approximated them on its
 * own. Deciding once, here, is what makes the surfaces agree.
 *
 * Values are in the display unit the model was built for.
 */
enum class ChartLook {
    /** Drawn as the main line: full weight, range-coloured where the chart tints. */
    MAIN,
    /** Drawn as a secondary line: the sensor's identity colour, thinner, faded. */
    SECONDARY,
}

data class ChartPointModel(
    val timestamp: Long,
    val value: Float,
    /** The reading's owner; a merged primary series can contain several sensors. */
    val sensorId: String? = null,
    /** A shared boundary point retains its own look in both adjacent runs. */
    val look: ChartLook = ChartLook.MAIN,
)

/**
 * A stretch of one series drawn with one look. Consecutive runs of a series
 * share their boundary point, so a change of look is a change of stroke on a
 * continuous line rather than a break in it.
 */
data class ChartRun(
    val look: ChartLook,
    val points: List<ChartPointModel>,
)

enum class ChartLaneKind {
    RAW,
    AUTO,
    /**
     * What the calibration in force would make of the primary, where that
     * differs from the main line. The main line does not move under a
     * calibration edit — it draws the recorded value — which makes a
     * calibration entered against a past fingerstick invisible, and seeing
     * what it does to the curve is the whole reason for entering one there.
     * So the projection is its own faint lane beside the record, present only
     * where the two disagree; where they agree there is nothing to preview.
     */
    CALIBRATION_PREVIEW,
}

/**
 * A lane drawn thin beside the main line: the other signal in a dual-lane
 * view mode, the uncalibrated source behind a calibrated main line, or the
 * calibration preview. Never the main line itself — the builder leaves that
 * out so a renderer cannot draw it twice.
 */
data class ChartLane(
    val kind: ChartLaneKind,
    val runs: List<ChartRun>,
)

data class ChartSeriesModel(
    val sensorId: String,
    /** Whether this is the currently selected primary sensor's series. */
    val isPrimary: Boolean,
    val viewMode: Int,
    val colorArgb: Int,
    val runs: List<ChartRun>,
    /** Lanes drawn thin beside this series' main run: the other signal in a dual mode, a source lane, a preview. */
    val secondaryLanes: List<ChartLane> = emptyList(),
) {
    val isEmpty: Boolean get() = runs.all { it.points.isEmpty() }

    /** The main-line value at a timestamp, for lookups that are not drawing. */
    private val valueByTimestamp: Map<Long, Float> by lazy(LazyThreadSafetyMode.NONE) {
        HashMap<Long, Float>().also { map ->
            runs.forEach { run -> run.points.forEach { p -> map.putIfAbsent(p.timestamp, p.value) } }
        }
    }

    fun valueAt(timestamp: Long): Float? = valueByTimestamp[timestamp]
}

data class HistoryChartModel(
    val series: List<ChartSeriesModel>,
) {
    val primary: ChartSeriesModel? get() = series.firstOrNull { it.isPrimary }
    val peers: List<ChartSeriesModel> get() = series.filter { !it.isPrimary }

    /** Main points actually inside the viewport, excluding borrowed stroke boundaries. */
    fun presentedMainValues(startTime: Long, endTime: Long): List<PresentedChartValue> {
        val values = LinkedHashMap<Long, PresentedChartValue>()
        for (s in series) for (run in s.runs) {
            if (run.look != ChartLook.MAIN) continue
            val pts = run.points
            val from = pts.binarySearchBy(startTime) { it.timestamp }.let { if (it >= 0) it else -it - 1 }
            val to = pts.binarySearchBy(endTime) { it.timestamp }.let { if (it >= 0) it + 1 else -it - 1 }
            for (i in from until to) {
                val p = pts[i]
                if (p.look != ChartLook.MAIN) continue
                val minute = MainSensorOwnership.minuteOf(p.timestamp)
                values.putIfAbsent(minute, PresentedChartValue(minute, p.value, p.sensorId ?: s.sensorId, s.viewMode))
            }
        }
        return values.values.toList()
    }

    companion object {
        val EMPTY = HistoryChartModel(emptyList())
    }
}

data class PresentedChartValue(
    val minuteMs: Long,
    val value: Float,
    val sensorId: String,
    val viewMode: Int,
)
