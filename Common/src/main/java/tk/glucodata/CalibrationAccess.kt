package tk.glucodata

object CalibrationAccess {
    private const val CLASS_NAME = "tk.glucodata.data.calibration.CalibrationManager"

    @Volatile
    private var provider: CalibrationProvider? = null

    @JvmStatic
    fun register(provider: CalibrationProvider) {
        this.provider = provider
    }

    private val holder by lazy { runCatching { Class.forName(CLASS_NAME) }.getOrNull() }
    private val instance by lazy { runCatching { holder?.getField("INSTANCE")?.get(null) }.getOrNull() }
    private val hasActiveCalibrationMethod by lazy {
        runCatching {
            holder?.getMethod("hasActiveCalibration", Boolean::class.javaPrimitiveType, String::class.java)
        }.getOrNull()
    }
    private val getCalibratedValueMethod by lazy {
        runCatching {
            holder?.getMethod(
                "getCalibratedValue",
                Float::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                String::class.java
            )
        }.getOrNull()
    }
    private val shouldHideInitialMethod by lazy {
        runCatching { holder?.getMethod("shouldHideInitialWhenCalibrated") }.getOrNull()
    }
    private val notifyExternalPipelineMethod by lazy {
        runCatching { holder?.getMethod("notifyExternalCalibrationPipelineChanged") }.getOrNull()
    }
    private val getActiveCalibrationAnchorsMethod by lazy {
        runCatching {
            holder?.getMethod(
                "getActiveCalibrationAnchors",
                String::class.java,
                Boolean::class.javaPrimitiveType,
            )
        }.getOrNull()
    }
    private val getIntegratedCalibrationAnchorsMethod by lazy {
        runCatching {
            holder?.getMethod(
                "getIntegratedCalibrationAnchors",
                String::class.java,
                Boolean::class.javaPrimitiveType,
            )
        }.getOrNull()
    }
    private val isCalibrationStateLoadedMethod by lazy {
        runCatching { holder?.getMethod("isCalibrationStateLoaded") }.getOrNull()
    }
    private val tuningForModeMethod by lazy {
        runCatching {
            holder?.getMethod("tuningForMode", Boolean::class.javaPrimitiveType)
        }.getOrNull()
    }
    private val shouldOverwriteSensorValuesMethod by lazy {
        runCatching { holder?.getMethod("shouldOverwriteSensorValues") }.getOrNull()
    }
    private val getRevisionMethod by lazy {
        runCatching {
            holder?.methods?.firstOrNull { method ->
                method.name == "getRevision" &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == Long::class.javaPrimitiveType
            }
        }.getOrNull()
    }
    private val getIntegratedCalibratedSeriesMethod by lazy {
        runCatching {
            holder?.getMethod(
                "getIntegratedCalibratedSeries",
                FloatArray::class.java,
                LongArray::class.java,
                Boolean::class.javaPrimitiveType,
                String::class.java,
            )
        }.getOrNull()
    }
    private val getIntegratedCalibrationFingerprintMethod by lazy {
        runCatching {
            holder?.getMethod(
                "getIntegratedCalibrationFingerprint",
                String::class.java,
                Boolean::class.javaPrimitiveType,
            )
        }.getOrNull()
    }
    private val seedIntegratedCalibrationBaselineMethod by lazy {
        runCatching {
            holder?.getMethod(
                "seedIntegratedCalibrationBaseline",
                FloatArray::class.java,
                LongArray::class.java,
                Boolean::class.javaPrimitiveType,
                String::class.java,
            )
        }.getOrNull()
    }

    private val setEnabledForModeMethod by lazy {
        runCatching {
            holder?.getMethod(
                "setEnabledForMode",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                String::class.java,
            )
        }.getOrNull()
    }
    private val clearAllMethod by lazy {
        runCatching { holder?.getMethod("clearAllBlocking") }.getOrNull()
    }

    /** Enable or disable calibration for both lanes of the current sensor. */
    @JvmStatic
    fun setEnabled(enabled: Boolean): Boolean = runCatching {
        val method = setEnabledForModeMethod ?: return false
        method.invoke(instance, false, enabled, null)
        method.invoke(instance, true, enabled, null)
        true
    }.getOrDefault(false)

    private val deleteCalibrationAtMethod by lazy {
        runCatching {
            holder?.getMethod("deleteCalibrationAtBlocking", Long::class.javaPrimitiveType)
        }.getOrNull()
    }
    private val updateCalibrationValueMethod by lazy {
        runCatching {
            holder?.getMethod(
                "updateCalibrationUserValueBlocking",
                Long::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            )
        }.getOrNull()
    }

    private val addCalibrationMethod by lazy {
        runCatching {
            holder?.getMethod(
                "addCalibrationFromWearAtBlocking",
                Long::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            )
        }.getOrNull()
    }

    /**
     * Record a fingerstick calibration. [timestampMs] picks the reading it is
     * measured against; 0 means the current one. [sensorStockMgdl] is the
     * sensor's own uncorrected value there, which only the device that produced
     * the reading knows; NaN means unknown and leaves the phone to recover it
     * from its own history as before.
     */
    @JvmStatic
    @JvmOverloads
    fun addCalibration(
        userValueMgdl: Float,
        timestampMs: Long = 0L,
        sensorStockMgdl: Float = Float.NaN,
    ): Boolean = runCatching {
        addCalibrationMethod?.invoke(instance, timestampMs, userValueMgdl, sensorStockMgdl) as? Boolean
            ?: false
    }.getOrDefault(false)

    /** Delete the calibration recorded at [timestamp]. */
    @JvmStatic
    fun deleteCalibrationAt(timestamp: Long): Boolean = runCatching {
        deleteCalibrationAtMethod?.invoke(instance, timestamp) as? Boolean ?: false
    }.getOrDefault(false)

    /** Change the fingerstick value of the calibration recorded at [timestamp]. */
    @JvmStatic
    fun updateCalibrationUserValue(timestamp: Long, userValueMgdl: Float): Boolean = runCatching {
        updateCalibrationValueMethod?.invoke(instance, timestamp, userValueMgdl) as? Boolean ?: false
    }.getOrDefault(false)

    /** Delete every stored calibration. */
    @JvmStatic
    fun clearAll(): Boolean = runCatching {
        clearAllMethod?.invoke(instance) ?: return false
        true
    }.getOrDefault(false)

    @JvmStatic
    fun hasActiveCalibration(isRawMode: Boolean, sensorId: String? = null): Boolean {
        provider?.let { return it.hasActiveCalibration(isRawMode, sensorId) }
        return runCatching {
            hasActiveCalibrationMethod?.invoke(instance, isRawMode, sensorId) as? Boolean
        }.getOrNull() ?: false
    }

    @JvmStatic
    @JvmOverloads
    fun getCalibratedValue(
        value: Float,
        timestamp: Long,
        isRawMode: Boolean,
        emitDiagnostics: Boolean = false,
        sensorIdOverride: String? = null
    ): Float {
        provider?.let {
            return it.getCalibratedValue(
                value,
                timestamp,
                isRawMode,
                emitDiagnostics,
                sensorIdOverride,
            )
        }
        return runCatching {
            getCalibratedValueMethod?.invoke(
                instance,
                value,
                timestamp,
                isRawMode,
                emitDiagnostics,
                sensorIdOverride
            ) as? Float
        }.getOrNull() ?: value
    }

    @JvmStatic
    fun shouldHideInitialWhenCalibrated(): Boolean {
        provider?.let { return it.shouldHideInitialWhenCalibrated() }
        return runCatching {
            shouldHideInitialMethod?.invoke(instance) as? Boolean
        }.getOrNull() ?: false
    }

    @JvmStatic
    fun notifyExternalCalibrationPipelineChanged() {
        runCatching { notifyExternalPipelineMethod?.invoke(instance) }
    }

    @JvmStatic
    fun getActiveCalibrationAnchors(sensorId: String?, isRawMode: Boolean = false): DoubleArray {
        provider?.let { return it.getActiveCalibrationAnchors(sensorId, isRawMode) }
        return runCatching {
            getActiveCalibrationAnchorsMethod?.invoke(instance, sensorId, isRawMode) as? DoubleArray
        }.getOrNull() ?: DoubleArray(0)
    }

    /**
     * The anchors a driver-integrated evaluation fits against, rebased onto
     * stock values. Empty where there is no CalibrationManager, which is also
     * where nothing integrates locally.
     */
    @JvmStatic
    fun getIntegratedCalibrationAnchors(sensorId: String?, isRawMode: Boolean): DoubleArray =
        runCatching {
            getIntegratedCalibrationAnchorsMethod?.invoke(instance, sensorId, isRawMode) as? DoubleArray
        }.getOrNull() ?: DoubleArray(0)

    /**
     * False when the phone's calibrations are not in memory, so callers do not
     * mistake a failed load for "this sensor has no calibration". True where
     * there is no CalibrationManager at all (the watch), which has nothing to
     * load.
     */
    @JvmStatic
    fun isCalibrationStateLoaded(): Boolean {
        if (holder == null) return true
        return runCatching { isCalibrationStateLoadedMethod?.invoke(instance) as? Boolean }
            .getOrNull() ?: false
    }

    /**
     * The settings behind the phone's fit, so the watch can reproduce the same
     * numbers with the shared computation.
     */
    @JvmStatic
    fun tuningForMode(isRawMode: Boolean): tk.glucodata.data.calibration.CalibrationTuning =
        runCatching {
            tuningForModeMethod?.invoke(instance, isRawMode)
                as? tk.glucodata.data.calibration.CalibrationTuning
        }.getOrNull() ?: tk.glucodata.data.calibration.CalibrationTuning.DEFAULT

    @JvmStatic
    fun shouldOverwriteSensorValues(): Boolean {
        provider?.let { return it.shouldOverwriteSensorValues() }
        return runCatching {
            shouldOverwriteSensorValuesMethod?.invoke(instance) as? Boolean
        }.getOrNull() ?: false
    }

    @JvmStatic
    fun getRevision(): Long {
        provider?.let { return it.getRevision() }
        return runCatching {
            when (val value = getRevisionMethod?.invoke(instance)) {
                is Long -> value
                is Number -> value.toLong()
                else -> null
            }
        }.getOrNull() ?: 0L
    }

    @JvmStatic
    fun getIntegratedCalibratedSeries(
        values: FloatArray,
        timestamps: LongArray,
        isRawMode: Boolean,
        sensorIdOverride: String?,
    ): FloatArray {
        if (values.size != timestamps.size) return values.copyOf()
        // The watch has no CalibrationManager, so this used to hand the driver
        // its own values straight back: a sensor whose driver folds calibration
        // into what it stores wrote stock numbers for as long as the watch owned
        // it, and both devices then displayed them as if they were corrected.
        provider?.let {
            return it.getIntegratedCalibratedSeries(values, timestamps, isRawMode, sensorIdOverride)
        }
        return runCatching {
            getIntegratedCalibratedSeriesMethod?.invoke(
                instance,
                values,
                timestamps,
                isRawMode,
                sensorIdOverride,
            ) as? FloatArray
        }.getOrNull()?.takeIf { it.size == values.size } ?: values.copyOf()
    }

    @JvmStatic
    fun getIntegratedCalibrationFingerprint(sensorIdOverride: String?, isRawMode: Boolean): Long {
        provider?.let { return it.getIntegratedCalibrationFingerprint(sensorIdOverride, isRawMode) }
        return runCatching {
            when (val value = getIntegratedCalibrationFingerprintMethod?.invoke(
                instance,
                sensorIdOverride,
                isRawMode,
            )) {
                is Long -> value
                is Number -> value.toLong()
                else -> 0L
            }
        }.getOrDefault(0L)
    }

    @JvmStatic
    fun seedIntegratedCalibrationBaseline(
        values: FloatArray,
        timestamps: LongArray,
        isRawMode: Boolean,
        sensorIdOverride: String?,
    ) {
        if (values.size != timestamps.size || values.isEmpty()) return
        runCatching {
            seedIntegratedCalibrationBaselineMethod?.invoke(
                instance,
                values,
                timestamps,
                isRawMode,
                sensorIdOverride,
            )
        }
    }
}
