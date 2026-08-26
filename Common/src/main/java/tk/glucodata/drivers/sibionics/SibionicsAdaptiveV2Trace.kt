package tk.glucodata.drivers.sibionics

import tk.glucodata.Log
import tk.glucodata.drivers.sibionics.adaptive2.AdaptiveV2Diagnostics

/**
 * Per-sample Adaptive V2 trace, emitted into the ordinary app log so it lands in
 * a captured trace file alongside everything else.
 *
 * V2 already built these diagnostics; they simply never reached anywhere
 * observable, which meant every question about on-device behaviour had to be
 * answered from synthetic proxies. That is how the lag prior came to be set
 * from slow ramps, where the vendor's deconvolution barely engages, and ended
 * up a factor of two low.
 *
 * Off by default — one CSV row per minute is far too much for normal
 * operation. Enabled from Debug settings while capturing a trace.
 */
object SibionicsAdaptiveV2Trace {

    private const val TAG = "SibionicsV2"

    /** Written from the UI, read from the BLE thread. */
    @Volatile
    @JvmStatic
    var enabled: Boolean = false

    /** Emitted once per session so a capture is self-describing. */
    @Volatile
    private var headerLogged = false

    fun reset() {
        headerLogged = false
    }

    /**
     * @param stockMmol the vendor value for the same sample.
     * @param adaptiveV1Mmol Adaptive V1 for the same sample, from a shadow
     *   context that never touches the emitted value. Comparison only — neither
     *   is an input to V2.
     */
    internal fun log(
        sensorSerial: String?,
        observation: SibionicsSensorObservation,
        diagnostics: AdaptiveV2Diagnostics?,
        stockMmol: Float,
        adaptiveV1Mmol: Float,
    ) {
        if (!enabled || diagnostics == null) return
        if (!headerLogged) {
            headerLogged = true
            Log.i(TAG, "csv $CSV_HEADER")
        }
        Log.i(
            TAG,
            "csv " + listOf(
                sensorSerial.orEmpty(),
                diagnostics.index,
                diagnostics.timestampMs,
                observation.family,
                fmt(observation.chemicalMmol),
                fmt(observation.calibratedMmol),
                fmt(observation.sensorStateCompensationMmol),
                fmt(observation.activeSensitivity),
                fmt(observation.factorySensitivity),
                observation.qualityFlags,
                fmt(diagnostics.glucoseMmol),
                fmt(diagnostics.lower90Mmol),
                fmt(diagnostics.upper90Mmol),
                fmt(diagnostics.interstitialMmol),
                fmt(diagnostics.rateMmolPerMin),
                fmt(diagnostics.rateUncertainty),
                fmt(diagnostics.steadyProbability),
                fmt(diagnostics.dynamicProbability),
                fmt(diagnostics.artifactProbability),
                fmt(diagnostics.driftProbability),
                fmt(diagnostics.sensitivity),
                fmt(diagnostics.biasMmol),
                fmt(diagnostics.artifactMmol),
                fmt(diagnostics.lagMinutes),
                fmt(diagnostics.innovation),
                fmt(diagnostics.measurementNoise),
                fmt(diagnostics.temperatureQuality),
                fmt(diagnostics.impedanceQuality),
                fmt(stockMmol),
                fmt(adaptiveV1Mmol),
            ).joinToString(",")
        )
    }

    /**
     * Locale-independent formatting.
     *
     * The app logs floats through `String.format` elsewhere and a comma decimal
     * separator on a non-English device turns a CSV row into nonsense — the
     * existing `mgdl=75,6` lines in captured traces are exactly that.
     */
    private fun fmt(value: Float): String =
        if (value.isFinite()) String.format(java.util.Locale.US, "%.4f", value) else ""

    const val CSV_HEADER =
        "sensor,index,timestampMs,family," +
            "chemical,calibrated,sensorStateCompensation,activeSensitivity,factorySensitivity,qualityFlags," +
            "v2Glucose,v2Lower90,v2Upper90,v2Interstitial,v2Rate,v2RateSd," +
            "pSteady,pDynamic,pArtifact,pDrift," +
            "v2ResidualSensitivity,v2ResidualBias,v2Artifact,v2Lag,v2Innovation,v2MeasurementNoise," +
            "tempQuality,impedanceQuality,stock,adaptiveV1"
}
