package tk.glucodata.drivers.anytime

/**
 * Per-sensor conversion from working-electrode current to glucose, learned from the
 * transmitter's own output while it is still producing one.
 *
 * A CT5 computes glucose in firmware and simply stops at `INFO_COMPLETE_END`, leaving a
 * sensor that still reports live current and temperature but no reading. Measured across
 * 1451 vendor readings of a full 17-day life, its output is a pure proportional scaling of
 * `Iw` — Ib is 0, there is no filtering (step-to-step roughness of the vendor's glucose
 * equals that of the current, ratio 1.00) and no lag. So the whole of what the firmware
 * adds is one scalar, and that scalar is learnable.
 *
 * What it is *not* is constant: it climbs through early life (11.4 at day 0, peaking 15.2
 * around day 4) and then decays about 0.9%/day. Fitting it over a whole life is therefore
 * wrong; fitting it over a trailing window is not. Out of sample, a window fitted one to
 * two days before the tested period predicted the vendor's own glucose to 1.5-4% MARD.
 *
 * The app's existing `computeLinear` cannot be used for this. With the K/R this sensor
 * reports over its own SSN it predicts 158 mg/dL where the transmitter said 107 — that
 * formula is shaped for CT3 and is 71% high here.
 */
internal class AnytimeCt5RawScale(
    private val windowSize: Int = DEFAULT_WINDOW,
) {
    private val ratios = ArrayDeque<Float>(windowSize)

    /** mg/dL per nA, or [Float.NaN] until enough of a window has been seen to trust it. */
    @Volatile
    var scale: Float = Float.NaN
        private set

    val samples: Int get() = ratios.size

    /**
     * Feed a reading the transmitter computed itself. Only clean ones: an errored or
     * warm-up record carries no usable relation between current and glucose.
     */
    fun observe(iwNa: Float, vendorMgdl: Float) {
        if (!iwNa.isFinite() || iwNa < MIN_USABLE_IW) return
        if (!vendorMgdl.isFinite() || vendorMgdl < MIN_USABLE_MGDL) return
        val ratio = vendorMgdl / iwNa
        if (ratio !in MIN_PLAUSIBLE_RATIO..MAX_PLAUSIBLE_RATIO) return
        if (ratios.size >= windowSize) ratios.removeFirst()
        ratios.addLast(ratio)
        scale = if (ratios.size >= MIN_SAMPLES) ratios.sum() / ratios.size else Float.NaN
    }

    /** Restore a scale learned before a restart, so a frozen sensor is usable immediately. */
    fun restore(scale: Float, samples: Int) {
        if (!scale.isFinite() || scale !in MIN_PLAUSIBLE_RATIO..MAX_PLAUSIBLE_RATIO) return
        if (samples < MIN_SAMPLES) return
        ratios.clear()
        // One representative sample: the persisted mean is what matters, and letting live
        // readings outweigh it as they arrive is the intent.
        ratios.addLast(scale)
        this.scale = scale
    }

    /**
     * Estimated mg/dL for a current the transmitter did not compute for, or [Float.NaN]
     * when no scale has been established or the current is not usable.
     */
    fun estimateMgdl(iwNa: Float): Float {
        val s = scale
        if (!s.isFinite() || !iwNa.isFinite() || iwNa < MIN_USABLE_IW) return Float.NaN
        val mgdl = s * iwNa
        return if (mgdl in MIN_ESTIMATE_MGDL..MAX_ESTIMATE_MGDL) mgdl else Float.NaN
    }

    companion object {
        /** ~4 days at the 3-minute cadence, the window the out-of-sample tests used. */
        const val DEFAULT_WINDOW = 2000

        /** Half a day. Fewer than this and a single excursion dominates the mean. */
        const val MIN_SAMPLES = 240

        private const val MIN_USABLE_IW = 0.3f
        private const val MIN_USABLE_MGDL = 20f

        // Observed 11.2-19.1 over a whole life; the guard only rejects the absurd.
        private const val MIN_PLAUSIBLE_RATIO = 4f
        private const val MAX_PLAUSIBLE_RATIO = 40f

        private const val MIN_ESTIMATE_MGDL = 20f
        private const val MAX_ESTIMATE_MGDL = 600f
    }
}
