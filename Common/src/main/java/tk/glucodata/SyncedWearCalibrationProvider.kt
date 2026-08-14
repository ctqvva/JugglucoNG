package tk.glucodata

object SyncedWearCalibrationProvider : CalibrationProvider {
    private const val MGDL_PER_MMOL = 18.0182f
    private const val PREFS = "tk.glucodata_preferences"
    private const val KEY = "wear_synced_calibration_v1"

    // Keyed by canonical sensor id. A single slot meant a second sensor's serve
    // overwrote the first one's anchors, so whichever sensor reported last was
    // the only one that stayed calibrated.
    private val payloads = java.util.concurrent.ConcurrentHashMap<String, WearCalibrationPayload>()

    @Volatile
    private var restored = false

    private fun keyOf(sensorId: String): String =
        (runCatching { SensorIdentity.canonicalSensorId(sensorId) }.getOrNull() ?: sensorId).lowercase()

    /**
     * The payload used to live only in memory, so every app restart left the
     * watch believing the sensor was uncalibrated until the next serve landed —
     * the displayed value flipped between the calibrated and uncalibrated lane
     * on each reopen. Persist it so the choice is stable across restarts.
     */
    @Synchronized
    private fun restoreLocked() {
        if (restored) return
        restored = true
        runCatching {
            val prefs = Applic.app?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) ?: return@runCatching
            prefs.all.keys
                .filter { it == KEY || it.startsWith("$KEY.") }
                .forEach { prefKey ->
                    val encoded = prefs.getString(prefKey, null) ?: return@forEach
                    val decoded = WearCalibrationPayload.decode(
                        android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP),
                    ) ?: return@forEach
                    payloads[keyOf(decoded.sensorId)] = decoded
                }
        }.onFailure { Log.stack("SyncedWearCalibration", "restore", it) }
    }

    @Synchronized
    fun update(next: WearCalibrationPayload) {
        if (next.sensorId.isBlank()) return
        restoreLocked()
        val key = keyOf(next.sensorId)
        val current = payloads[key]
        if (current != null && next.revision < current.revision) return
        payloads[key] = next
        runCatching {
            val prefs = Applic.app?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            prefs?.edit()?.putString(
                "$KEY.$key",
                android.util.Base64.encodeToString(WearCalibrationPayload.encode(next), android.util.Base64.NO_WRAP),
            )?.apply()
        }.onFailure { Log.stack("SyncedWearCalibration", "persist", it) }
        // A managed driver that integrates the calibration has to replay its
        // history when the anchors move, exactly as CalibrationManager makes it
        // do on the phone. Without this the watch kept serving readings fitted
        // against the anchor set it started with.
        runCatching {
            tk.glucodata.drivers.ManagedSensorRuntime.notifyUserCalibrationRevisionChanged(next.revision)
        }.onFailure { Log.stack("SyncedWearCalibration", "notify drivers", it) }
        UiRefreshBus.requestDataRefresh()
    }

    override fun hasActiveCalibration(isRawMode: Boolean, sensorId: String?): Boolean {
        val current = matchingPayload(sensorId) ?: return false
        return mode(current, isRawMode).anchorsMgdl.isNotEmpty()
    }

    /**
     * Corrects a reading with the phone's anchors and settings, using the same
     * computation the phone runs.
     *
     * This used to return the value untouched, because sync2 corrected values
     * before sending them. That left the watch unable to correct anything it read
     * itself, so taking the sensor over dropped the display straight back to raw
     * numbers. Both devices now store raw and correct here.
     */
    override fun getCalibratedValue(
        value: Float,
        timestamp: Long,
        isRawMode: Boolean,
        emitDiagnostics: Boolean,
        sensorId: String?,
    ): Float {
        val current = matchingPayload(sensorId) ?: return value
        // Older phones corrected on the way out; correcting again would double it.
        if (current.valuesPrecalibrated || current.overwriteSensorValues) return value
        if (!value.isFinite() || value <= 0f) return value
        val watchUnitMgdlPerUnit = if (runCatching { Applic.unit == 1 }.getOrDefault(false)) MGDL_PER_MMOL else 1f
        return calibrateWithPayload(value, timestamp, isRawMode, watchUnitMgdlPerUnit, current)
    }

    /** Pure seam used to pin phone/watch unit parity without Android state. */
    internal fun calibrateWithPayload(
        value: Float,
        timestamp: Long,
        isRawMode: Boolean,
        watchUnitMgdlPerUnit: Float,
        payload: WearCalibrationPayload,
    ): Float {
        val anchors = mode(payload, isRawMode).anchorsMgdl
        if (anchors.isEmpty() || !value.isFinite() || value <= 0f) return value
        // The phone's managed driver folded the fit into the value it stored for
        // this lane, and CalibrationManager leaves such a value alone. Applying
        // the anchors here as well showed the watch a second correction of an
        // already corrected reading — 7,8 on the phone read 7,4 here.
        if (integratedByDriver(payload, isRawMode)) return value
        val points = pointsOf(mode(payload, isRawMode), payload.sourceUnitMgdlPerUnit.toFloat())
        if (points.isEmpty()) return value
        val tuning = if (isRawMode) payload.rawTuning else payload.tuning
        val resolved = tk.glucodata.data.calibration.CalibrationMath.resolvePointsForTimestamp(
            allPoints = points,
            targetTimestamp = timestamp,
            earliestPoint = points.firstOrNull(),
            tuning = tuning,
        )
        if (resolved.isEmpty()) return value
        return evaluate(value, timestamp, watchUnitMgdlPerUnit, payload, tuning, resolved)
    }

    /**
     * The watch's side of a driver that folds calibration into what it stores.
     *
     * Called by the managed driver before it writes a reading, exactly as the
     * phone's CalibrationManager is. Without it the watch wrote stock values for
     * as long as it owned the sensor, and both devices then showed them as
     * corrected — the reason a calibration entered at 5,1 → 3,4 left 5,1 on
     * screen.
     *
     * Uses [WearCalibrationPayload.autoIntegration]: the phone's own anchors,
     * rebased onto the stock values behind them. An empty set means the phone
     * does not integrate this lane either, and the driver's values are left
     * alone — display-time correction handles those.
     */
    override fun getIntegratedCalibratedSeries(
        values: FloatArray,
        timestamps: LongArray,
        isRawMode: Boolean,
        sensorId: String?,
    ): FloatArray {
        if (values.size != timestamps.size || values.isEmpty()) return values.copyOf()
        val payload = matchingPayload(sensorId) ?: return values.copyOf()
        val watchUnit = if (runCatching { Applic.unit == 1 }.getOrDefault(false)) MGDL_PER_MMOL else 1f
        return integrateWithPayload(values, timestamps, isRawMode, watchUnit, payload)
    }

    /** Pure seam for [getIntegratedCalibratedSeries]; see [calibrateWithPayload]. */
    internal fun integrateWithPayload(
        values: FloatArray,
        timestamps: LongArray,
        isRawMode: Boolean,
        watchUnitMgdlPerUnit: Float,
        payload: WearCalibrationPayload,
    ): FloatArray {
        val points = pointsOf(
            if (isRawMode) payload.rawIntegration else payload.autoIntegration,
            payload.sourceUnitMgdlPerUnit.toFloat(),
        )
        if (points.isEmpty()) return values.copyOf()
        val tuning = if (isRawMode) payload.rawTuning else payload.tuning
        return FloatArray(values.size) { index ->
            val value = values[index]
            if (!value.isFinite() || value <= 0f) {
                value
            } else {
                // CalibrationManager.evaluateCalibratedSeries narrows the anchor
                // set per reading only when past history is locked; otherwise
                // every reading is fitted against all of them. Reproduce that
                // choice, or the watch and the phone disagree on old readings.
                val resolved = if (tuning.lockPastHistory) {
                    tk.glucodata.data.calibration.CalibrationMath.resolvePointsForTimestamp(
                        allPoints = points,
                        targetTimestamp = timestamps[index],
                        earliestPoint = points.firstOrNull(),
                        tuning = tuning,
                    )
                } else {
                    points
                }
                if (resolved.isEmpty()) {
                    value
                } else {
                    evaluate(value, timestamps[index], watchUnitMgdlPerUnit, payload, tuning, resolved)
                }
            }
        }
    }

    private fun pointsOf(
        mode: WearCalibrationMode,
        sourceScale: Float,
    ): List<tk.glucodata.data.calibration.CalPoint> {
        val anchors = mode.anchorsMgdl
        val points = ArrayList<tk.glucodata.data.calibration.CalPoint>(anchors.size / 3)
        var offset = 0
        while (offset + 2 < anchors.size) {
            points.add(
                tk.glucodata.data.calibration.CalPoint(
                    x = anchors[offset] / sourceScale,
                    y = anchors[offset + 1] / sourceScale,
                    timestamp = anchors[offset + 2].toLong(),
                ),
            )
            offset += 3
        }
        return points.sortedBy { it.timestamp }
    }

    /** The shared computation, in the unit the phone fitted the anchors in. */
    private fun evaluate(
        value: Float,
        timestamp: Long,
        watchUnitMgdlPerUnit: Float,
        payload: WearCalibrationPayload,
        tuning: tk.glucodata.data.calibration.CalibrationTuning,
        resolved: List<tk.glucodata.data.calibration.CalPoint>,
    ): Float {
        val sourceScale = payload.sourceUnitMgdlPerUnit.toFloat()
        val sourceValue = value * watchUnitMgdlPerUnit / sourceScale
        val computation = tk.glucodata.data.calibration.CalibrationMath.computeAlgorithm(
            algorithm = tuning.algorithm,
            targetValue = sourceValue.toDouble(),
            targetTimestamp = timestamp,
            points = resolved,
            tuning = tuning,
        )
        val correctedSource = tk.glucodata.data.calibration.CalibrationMath.sanitizeCalibratedValue(
            computation.prediction,
            sourceValue,
        )
        val finalSource = if (tuning.applyToPast) {
            correctedSource
        } else {
            tk.glucodata.data.calibration.CalibrationMath.applyPastPolicy(
                originalValue = sourceValue,
                calibratedValue = correctedSource,
                targetTimestamp = timestamp,
                points = resolved,
            )
        }
        if (!finalSource.isFinite() || finalSource <= 0f) return value
        return (finalSource * sourceScale / watchUnitMgdlPerUnit).toFloat()
    }

    override fun getIntegratedCalibrationFingerprint(sensorId: String?, isRawMode: Boolean): Long {
        val payload = matchingPayload(sensorId) ?: return 0L
        val anchors = if (isRawMode) payload.rawIntegration else payload.autoIntegration
        var hash = payload.revision
        anchors.anchorsMgdl.forEach { hash = hash * 31L + java.lang.Double.doubleToRawLongBits(it) }
        return hash
    }

    override fun shouldHideInitialWhenCalibrated(): Boolean {
        restoreLocked()
        return payloads.values.any { it.hideInitialWhenCalibrated }
    }

    override fun getActiveCalibrationAnchors(sensorId: String?, isRawMode: Boolean): DoubleArray {
        val current = matchingPayload(sensorId) ?: return DoubleArray(0)
        // Canonical mg/dL, exactly like CalibrationManager on the phone: the
        // callers convert to the display unit themselves. Converting here too
        // divided every anchor twice, so a 3.0 mmol calibration was listed as
        // "0,2".
        return mode(current, isRawMode).anchorsMgdl.copyOf()
    }

    override fun getRevision(): Long {
        restoreLocked()
        return payloads.values.maxOfOrNull { it.revision } ?: 0L
    }

    private fun mode(payload: WearCalibrationPayload, isRawMode: Boolean): WearCalibrationMode =
        if (isRawMode) payload.raw else payload.auto

    private fun integratedByDriver(payload: WearCalibrationPayload, isRawMode: Boolean): Boolean =
        if (isRawMode) payload.rawIntegratedByDriver else payload.autoIntegratedByDriver

    private fun matchingPayload(sensorId: String?): WearCalibrationPayload? {
        restoreLocked()
        if (payloads.isEmpty()) return null
        val requested = sensorId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return payloads.values.maxByOrNull { it.revision }
        payloads[keyOf(requested)]?.let { return it }
        // Fall back to an identity match: the phone may name the sensor
        // differently from the id the caller happens to hold.
        return payloads.values.firstOrNull { SensorIdentity.matches(it.sensorId, requested) }
    }
}
