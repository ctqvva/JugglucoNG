package tk.glucodata.alerts

/** Shared production decision for forecast and delta candidates, before delivery/latching. */
internal object CompressionTrendPolicy {
    // Numerical residue only, not a clinically chosen "small dose" cutoff.
    const val ZERO_IOB_EPSILON_UNITS = 0.0001f

    /** A held offer stays pending; a dropped offer is consumed without delivery. */
    fun consume(
        decision: CompressionTrendHoldState.Decision,
        onHold: () -> Unit,
        onDrop: () -> Unit
    ): Boolean = when (decision) {
        CompressionTrendHoldState.Decision.HOLD -> { onHold(); true }
        CompressionTrendHoldState.Decision.DROP -> { onDrop(); true }
        CompressionTrendHoldState.Decision.ALLOW -> false
    }

    fun decide(
        holds: CompressionTrendHoldState,
        type: AlertType,
        nowMs: Long,
        readingTimeMs: Long,
        valueMgdl: Float,
        enabled: Boolean,
        covered: Boolean,
        lowThresholdMgdl: Float?,
        hardFloorReached: Boolean,
        suppressZeroIob: Boolean,
        iobUnits: () -> Float?,
        hasEvidence: () -> Boolean
    ): CompressionTrendHoldState.Decision {
        if (!enabled) {
            holds.clear()
            return CompressionTrendHoldState.Decision.ALLOW
        }
        val falling = type == AlertType.PRE_LOW || type == AlertType.FALLING_FAST
        if (!covered || !holds.supports(type) || !valueMgdl.isFinite() ||
            (falling && (lowThresholdMgdl == null || !lowThresholdMgdl.isFinite() ||
                lowThresholdMgdl <= 0f || valueMgdl <= lowThresholdMgdl || hardFloorReached))
        ) {
            holds.onCandidateCleared(type)
            return CompressionTrendHoldState.Decision.ALLOW
        }
        if (falling && suppressZeroIob) {
            val iob = runCatching(iobUnits).getOrNull()
            if (iob != null && iob.isFinite() && iob >= 0f && iob <= ZERO_IOB_EPSILON_UNITS) {
                holds.onCandidateCleared(type)
                return CompressionTrendHoldState.Decision.DROP
            }
        }
        if (!runCatching(hasEvidence).getOrDefault(false)) {
            holds.onCandidateCleared(type)
            return CompressionTrendHoldState.Decision.ALLOW
        }
        return holds.onCandidate(type, nowMs, readingTimeMs, valueMgdl, actualLow = false)
    }
}
