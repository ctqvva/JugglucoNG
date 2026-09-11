package tk.glucodata

import tk.glucodata.chart.HistoryChartModel
import tk.glucodata.chart.HistoryChartModelBuilder

/**
 * Assembles the notification's resolved chart from the ingredients Notify
 * already has. Nothing is resolved here: the points carry their recorded
 * values, ownership comes from the record through one bridge call, and
 * [HistoryChartModelBuilder] makes every decision — the same decisions the
 * dashboard makes, because it is the same builder.
 */
object NotificationChartModelSource {

    @JvmStatic
    fun build(
        primaryPoints: List<GlucosePoint>,
        primarySensorId: String?,
        primaryViewMode: Int,
        hasCalibration: Boolean,
        peers: List<NotificationChartDrawer.PeerSeries>,
        startTimeMs: Long,
    ): HistoryChartModel? {
        val primary = primarySensorId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (primaryPoints.isEmpty() && peers.isEmpty()) return null

        val ownership = runCatching { HistoryRepositoryAccess.getMainSensorOwnership(startTimeMs) }
            .getOrDefault(tk.glucodata.chart.MainSensorOwnership.NONE)

        val inputs = ArrayList<HistoryChartModelBuilder.SeriesInput>(peers.size + 1)
        inputs.add(
            HistoryChartModelBuilder.SeriesInput(
                sensorId = primary,
                isPrimary = true,
                viewMode = primaryViewMode,
                colorArgb = SensorVisuals.colorArgb(primary),
                points = primaryPoints,
            )
        )
        peers.forEach { peer ->
            inputs.add(
                HistoryChartModelBuilder.SeriesInput(
                    sensorId = peer.sensorId,
                    isPrimary = false,
                    viewMode = peer.viewMode,
                    colorArgb = peer.color,
                    points = peer.points,
                )
            )
        }

        val calibration = HistoryChartModelBuilder.Calibration { base, timestamp, isRaw, sensorId ->
            if (!hasCalibration) return@Calibration null
            if (!CalibrationAccess.hasActiveCalibration(isRaw, sensorId)) return@Calibration null
            CalibrationAccess.getCalibratedValue(base, timestamp, isRaw, false, sensorId)
        }
        return HistoryChartModelBuilder.build(
            inputs,
            ownership,
            calibration,
            hasCalibration = hasCalibration,
            hideInitialWhenCalibrated = CalibrationAccess.shouldHideInitialWhenCalibrated(),
        )
    }
}
