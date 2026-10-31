package tk.glucodata.predictionbench

import tk.glucodata.predictionbench.LevelSource.KALMAN as KALMAN_LEVEL
import tk.glucodata.predictionbench.MomentumShape.LINEAR
import tk.glucodata.predictionbench.MomentumShape.PLATEAU
import tk.glucodata.predictionbench.SlopeSource.KALMAN
import tk.glucodata.predictionbench.SlopeSource.TREND_ENGINE

/**
 * The candidates compared head to head. Fresh instances each call: learners carry state.
 *
 * Kept to the questions still open. Variants that lost clearly in earlier rounds (rebound with
 * the arrow's slope, τ30 plateaus, a ½/½ blend) are gone; their results are in the PR that
 * introduced this bench.
 */
object Rosters {
    fun standard(): List<Forecaster> = listOf(
        Persistence(),
        // What ships, and the settings a user can already change.
        ProductionKernel("production kernel (defaults)"),
        ProductionKernel("production kernel, app default profile", useProfile = false),
        ProductionKernel("production, momentum off (setting)", PRODUCTION_DEFAULT_SETTINGS.copy(trendMomentumEnabled = false)),
        ProductionKernel("production, smoothing 10 min (setting)", smoothingMinutes = 10),
        // The kernel's assumptions one at a time.
        KernelVariant("kernel without target pull", settling = false),
        KernelVariant("kernel with plateau τ15", shape = PLATEAU, tauMinutes = 15f),
        KernelVariant("alert-style linear (arrow × h)", slope = TREND_ENGINE, shape = LINEAR, settling = false, clampSlope = false),
        // Sensor-aware trend without personal learning.
        KernelVariant("Kalman level+rate, plateau τ15", slope = KALMAN, shape = PLATEAU, tauMinutes = 15f, settling = false, level = KALMAN_LEVEL),
        KernelVariant("Kalman level+rate, plateau τ8", slope = KALMAN, shape = PLATEAU, tauMinutes = 8f, settling = false, level = KALMAN_LEVEL),
        // Personal, without fitting anything.
        TrailingMean("own trailing 24 h mean"),
        KernelVariant("arrow plateau τ15 + pull to own 24 h mean", slope = TREND_ENGINE, shape = PLATEAU, tauMinutes = 15f, settling = false, reversionTauMinutes = 180f),
        // Personal, fitted: the production adaptive forecast and the bench-only alternatives.
        AdaptiveProduction("adaptive (production)"),
        AdaptiveProduction("adaptive + logged treatments", withTreatments = true),
        RidgeDirect("ridge + insulin/carbs on board (bench)", featureMask = BooleanArray(PastFeatures.COUNT) { true }),
        AnalogKnn("analog k-NN (bench)"),
    )
}
