package tk.glucodata

object WidgetDisplaySource {
    const val CHART_WINDOW_MS = 6L * 60L * 60L * 1000L

    @JvmStatic
    fun resolveActiveSensorSerial(preferredSerial: String? = null): String? {
        return NotificationHistorySource.resolveSensorSerial(preferredSerial ?: SensorIdentity.resolveMainSensor())
    }

    @JvmStatic
    @JvmOverloads
    fun resolveWidgetSnapshot(
        maxAgeMillis: Long = Notify.glucosetimeout,
        preferredSensorId: String? = null
    ): CurrentDisplaySource.Snapshot? {
        val activeSensorSerial = resolveActiveSensorSerial(preferredSensorId)
        return try {
            CurrentDisplaySource.resolveCurrent(maxAgeMillis, activeSensorSerial)
        } catch (_: Throwable) {
            null
        }
    }

    @JvmStatic
    fun resolveViewMode(sensorName: String?): Int {
        if (sensorName.isNullOrEmpty()) {
            return 0
        }
        tk.glucodata.drivers.ManagedSensorRuntime.resolveUiSnapshot(sensorName, sensorName)
            ?.let { return it.viewMode }
        if (!SensorIdentity.hasNativeSensorBacking(sensorName)) {
            return 0
        }
        return try {
            val snapshot = Natives.getSensorUiSnapshot(sensorName)
            if (snapshot != null && snapshot.size >= 2) snapshot[1].toInt() else 0
        } catch (_: Throwable) {
            0
        }
    }

    @JvmStatic
    @JvmOverloads
    fun resolveChartHistory(
        current: CurrentDisplaySource.Snapshot? = null,
        historyWindowMs: Long = CHART_WINDOW_MS
    ): List<GlucosePoint> {
        val activeSensorSerial = current?.sensorId ?: resolveActiveSensorSerial()
        val startTimeMs = (System.currentTimeMillis() - historyWindowMs).coerceAtLeast(0L)
        val baseHistory = try {
            NotificationHistorySource.getDisplayHistory(startTimeMs, Applic.unit == 1, activeSensorSerial)
        } catch (_: Throwable) {
            emptyList()
        }
        return DisplayTrendSource.augmentHistory(baseHistory, current, activeSensorSerial, startTimeMs)
    }

    @JvmStatic
    fun resolveDisplaySnapshot(
        current: CurrentDisplaySource.Snapshot?,
        historyPoints: List<GlucosePoint>,
        sensorSerial: String? = current?.sensorId
    ): CurrentDisplaySource.Snapshot? {
        if (current != null) {
            return current
        }
        val targetTimeMillis = historyPoints.lastOrNull()?.timestamp ?: return null
        val viewMode = resolveViewMode(sensorSerial)
        return CurrentDisplaySource.resolveFromLive(
            liveValueText = null,
            liveNumericValue = Float.NaN,
            rate = Float.NaN,
            targetTimeMillis = targetTimeMillis,
            sensorId = sensorSerial,
            sensorGen = 0,
            index = 0,
            source = "widget",
            recentPoints = historyPoints,
            viewMode = viewMode,
            isMmol = Applic.unit == 1
        )
    }

    @JvmStatic
    fun resolveDataState(
        current: CurrentDisplaySource.Snapshot?,
        historyPoints: List<GlucosePoint>,
        sensorSerial: String? = current?.sensorId
    ): DisplayDataState.Status {
        return DisplayDataState.resolve(
            sensorPresent = !sensorSerial.isNullOrBlank(),
            currentTimestampMillis = current?.timeMillis ?: 0L,
            latestHistoryTimestampMillis = historyPoints.lastOrNull()?.timestamp ?: 0L
        )
    }

    @JvmStatic
    fun hasCalibration(sensorSerial: String?, viewMode: Int): Boolean {
        return NightscoutCalibration.hasCalibrationForViewMode(sensorSerial, viewMode)
    }
}
