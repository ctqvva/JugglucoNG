package tk.glucodata

interface CalibrationProvider {
    fun hasActiveCalibration(isRawMode: Boolean, sensorId: String?): Boolean

    fun getCalibratedValue(
        value: Float,
        timestamp: Long,
        isRawMode: Boolean,
        emitDiagnostics: Boolean,
        sensorId: String?,
    ): Float

    fun shouldHideInitialWhenCalibrated(): Boolean = false

    fun getActiveCalibrationAnchors(sensorId: String?, isRawMode: Boolean): DoubleArray =
        DoubleArray(0)

    fun shouldOverwriteSensorValues(): Boolean = false

    /**
     * Evaluate the calibration model over a series the way a driver that folds
     * the correction into the values it stores needs it done. Defaults to no
     * correction; a provider that cannot reproduce the phone's fit must leave
     * the values alone rather than guess at one.
     */
    fun getIntegratedCalibratedSeries(
        values: FloatArray,
        timestamps: LongArray,
        isRawMode: Boolean,
        sensorId: String?,
    ): FloatArray = values.copyOf()

    /**
     * Changes whenever [getIntegratedCalibratedSeries] would produce different
     * numbers. A managed driver stores it alongside its rebuilt algorithm and
     * replays its history when it moves, so old readings pick up a calibration
     * added after they were taken.
     */
    fun getIntegratedCalibrationFingerprint(sensorId: String?, isRawMode: Boolean): Long = 0L

    fun getRevision(): Long = 0L
}
