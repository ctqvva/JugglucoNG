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

data class ChartSeriesModel(
    val sensorId: String,
    /** Whether this is the currently selected primary sensor's series. */
    val isPrimary: Boolean,
    val viewMode: Int,
    val colorArgb: Int,
    val runs: List<ChartRun>,
) {
    val isEmpty: Boolean get() = runs.all { it.points.isEmpty() }
}

data class HistoryChartModel(
    val series: List<ChartSeriesModel>,
) {
    val primary: ChartSeriesModel? get() = series.firstOrNull { it.isPrimary }
    val peers: List<ChartSeriesModel> get() = series.filter { !it.isPrimary }

    companion object {
        val EMPTY = HistoryChartModel(emptyList())
    }
}
