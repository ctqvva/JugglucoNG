package tk.glucodata

/** The phone's forecast recorder, as the shared reading pipeline sees it. */
interface PredictionAnalysisBridge {
    /**
     * A live reading of the main sensor has been accepted. Must return at once: the
     * recorder decides off this thread whether it is time to record a forecast.
     */
    fun onRealtimeReading(sensorSerial: String?, readingTimeMs: Long)
}

/**
 * Registration seam for [PredictionAnalysisBridge]. The watch registers nothing, so there the
 * call is a no-op; on the phone it hands the reading to the recorder, which records only while
 * prediction analysis is switched on.
 *
 * Called after the alert decisions for the reading, and it cannot fail them: anything thrown
 * here is logged and dropped.
 */
object PredictionAnalysisAccess {
    private const val TAG = "PredictionAnalysisAccess"

    @Volatile
    private var bridge: PredictionAnalysisBridge? = null

    @JvmStatic
    fun register(bridge: PredictionAnalysisBridge) {
        this.bridge = bridge
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = bridge != null

    @JvmStatic
    fun onRealtimeReading(sensorSerial: String?, readingTimeMs: Long) {
        runCatching { bridge?.onRealtimeReading(sensorSerial, readingTimeMs) }
            .onFailure { Log.stack(TAG, "onRealtimeReading", it) }
    }
}
