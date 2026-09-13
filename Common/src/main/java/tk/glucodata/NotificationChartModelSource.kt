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

    internal fun smoothPoints(points: List<GlucosePoint>, minutes: Int, collapse: Boolean): List<GlucosePoint> =
        GlucoseSmoothing.smooth(
            points, minutes, collapse,
            timestamp = { it.timestamp }, value = { it.value }, rawValue = { it.rawValue },
            sensorSerial = { it.sensorSerial },
            withValues = { source, auto, raw ->
                GlucosePoint(source.timestamp, auto, raw).also {
                    it.sensorSerial = source.sensorSerial
                    it.sealedDisplayValue = source.sealedDisplayValue
                    it.sealedDisplayViewMode = source.sealedDisplayViewMode
                    it.color = source.color
                }
            },
        )

    @JvmStatic
    @JvmOverloads
    fun build(
        context: android.content.Context,
        primaryPoints: List<GlucosePoint>,
        primarySensorId: String?,
        primaryViewMode: Int,
        hasCalibration: Boolean,
        peers: List<NotificationChartDrawer.PeerSeries>,
        startTimeMs: Long,
        allowCalibration: Boolean = true,
    ): HistoryChartModel? {
        val primary = primarySensorId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (primaryPoints.isEmpty() && peers.isEmpty()) return null

        val ownership = runCatching { HistoryRepositoryAccess.getMainSensorOwnership(startTimeMs) }
            .getOrDefault(tk.glucodata.chart.MainSensorOwnership.NONE)

        val smoothingMinutes = DataSmoothing.graphSmoothingMinutes(context)
        val collapse = smoothingMinutes > 0 && DataSmoothing.collapseChunks(context)
        val inputs = ArrayList<HistoryChartModelBuilder.SeriesInput>(peers.size + 1)
        inputs.add(
            HistoryChartModelBuilder.SeriesInput(
                sensorId = primary,
                isPrimary = true,
                viewMode = primaryViewMode,
                colorArgb = SensorVisuals.colorArgb(primary),
                points = smoothPoints(primaryPoints, smoothingMinutes, collapse),
            )
        )
        peers.forEach { peer ->
            val peerIsRaw = peer.viewMode == 1 || peer.viewMode == 3
            inputs.add(
                HistoryChartModelBuilder.SeriesInput(
                    sensorId = peer.sensorId,
                    isPrimary = false,
                    viewMode = peer.viewMode,
                    colorArgb = peer.color,
                    points = smoothPoints(peer.points, smoothingMinutes, collapse),
                    hasCalibration = CalibrationAccess.hasActiveCalibration(peerIsRaw, peer.sensorId),
                )
            )
        }

        val calibration = HistoryChartModelBuilder.Calibration { base, timestamp, isRaw, sensorId ->
            if (!allowCalibration) return@Calibration null
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
