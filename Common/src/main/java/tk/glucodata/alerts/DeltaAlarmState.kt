package tk.glucodata.alerts

import tk.glucodata.GlucoseDelta

/**
 * GDH-style delta run-length counter behind the FALLING_FAST / RISING_FAST alarms.
 *
 * Robust, model-free "you're dropping/rising fast" detection: it counts consecutive
 * non-overlapping intervals whose change (via [GlucoseDelta]) exceeds [deltaThreshold] in the
 * alarming direction, and fires once a run of [deltaCount] such intervals has accumulated while
 * the value is past [deltaBorder]. No extrapolation, no insulin, no smoothing.
 *
 * The delta itself stays rolling: it is recomputed for every reading over the configured
 * 1-or-5-minute window, exactly like NG's Δ readout, so the threshold is cadence-independent:
 * "10 mg/dl per 5 min" means the same on a 1-minute sensor and a 5-minute one. The *counter*,
 * however, advances only at interval checkpoints: the first steep delta starts the run (that
 * delta already covers one interval of movement), and it is re-judged at the first reading a
 * full interval later, whose rolling delta covers the adjacent, non-overlapping interval. On a
 * fast stream, consecutive rolling windows overlap almost entirely, so counting every reading
 * would satisfy [deltaCount] within a few readings instead of `deltaCount × interval` minutes.
 * On a sensor that reports exactly once per interval, every reading is a checkpoint and the
 * behaviour is the same as counting readings.
 *
 * Between checkpoints, a delta reversed against the alarming direction breaks the run
 * immediately; a delta that is merely no longer steep enough leaves the run standing until the
 * next checkpoint decides. A NaN delta (no usable history, or the pair straddles a data gap)
 * breaks the run at once.
 *
 * The configuration implies a total distance (`deltaCount × deltaThreshold`). With the optional
 * early trigger, the alarm also fires as soon as the value has moved that far from the run's
 * anchor (the older sample of the run-starting delta, so distance is measured consistently with
 * the delta metric) — checked on every reading, not just at checkpoints, and sharing the
 * once-per-run arming with the checkpoint path.
 *
 * One instance per direction, fed the current display value on every evaluation. It advances only
 * on a genuinely newer reading timestamp, so scheduler ticks (which re-pass the same reading) and
 * calibration-only display changes cannot inflate the counter. It fires once per steep run and
 * re-arms only when the run breaks; repeat nagging is left to the shared snooze / retry machinery.
 */
internal class DeltaAlarmState(private val falling: Boolean) {
    private companion object {
        // Sentinel for "no reading ingested yet"; real reading timestamps are positive epoch millis.
        const val NO_READING = -1L
    }

    private class Sample(val timeMs: Long, val value: Float)

    private val history = ArrayDeque<Sample>()
    private var lastReadingTimeMs = NO_READING
    private var lastCheckpointMs = NO_READING
    private var lastIntervalMinutes = 0   // 0 = no reading judged yet
    private var count = 0
    private var runReferenceValue = Float.NaN   // anchor of the current run, for the early trigger
    private var armed = true

    fun reset() {
        history.clear()
        lastReadingTimeMs = NO_READING
        lastCheckpointMs = NO_READING
        lastIntervalMinutes = 0
        count = 0
        runReferenceValue = Float.NaN
        armed = true
    }

    /**
     * Drop the reading history so the next reading starts a fresh run without counting the jump to
     * it. Used when a recalibration shifts the displayed value without a real new sample.
     */
    fun resetBaseline() {
        history.clear()
        lastCheckpointMs = NO_READING
        count = 0
        runReferenceValue = Float.NaN
        armed = true
    }

    /**
     * The alert offered by the last true [shouldTrigger] never reached the user
     * (delivery suppressed or failed). Re-arm so the next evaluation offers it
     * again while the steep run still stands; if the run has broken meanwhile,
     * the stale alarm dies with it instead of firing late.
     */
    fun rearmAfterFailedDelivery() {
        armed = true
    }

    fun shouldTrigger(
        enabled: Boolean,
        activeNow: Boolean,
        snoozed: Boolean,
        value: Float,
        readingTimeMs: Long,
        deltaThreshold: Float,
        deltaCount: Int,
        deltaBorder: Float,
        intervalMinutes: Int,
        earlyTriggerEnabled: Boolean = false
    ): Boolean {
        if (!enabled || deltaThreshold <= 0f || deltaCount <= 0) {
            reset()
            return false
        }

        // The window scales both the walk-back and the checkpoint spacing, so a run measured
        // over mixed windows is meaningless: when the effective interval changes, start over.
        val interval = GlucoseDelta.sanitizeIntervalMinutes(intervalMinutes)
        if (lastIntervalMinutes != 0 && interval != lastIntervalMinutes) {
            resetBaseline()
        }
        lastIntervalMinutes = interval

        // Advance the run counter only on a genuinely newer, finite reading.
        if (value.isFinite() && readingTimeMs > lastReadingTimeMs) {
            advanceRun(value, readingTimeMs, deltaThreshold, interval)
            history.addLast(Sample(readingTimeMs, value))
            lastReadingTimeMs = readingTimeMs
            pruneOld(readingTimeMs, interval)
        }

        if (!activeNow || snoozed) {
            return false
        }

        val pastBorder = if (falling) value <= deltaBorder else value >= deltaBorder
        val runComplete = count >= deltaCount
        // Optional escalation: once the value has covered the configured total distance
        // (count x threshold) from the run's anchor, the remaining checkpoints add nothing.
        // Checked on every reading; NaN anchor or value makes the comparison false.
        val distanceCovered = if (falling) runReferenceValue - value else value - runReferenceValue
        val earlyTrigger = earlyTriggerEnabled && count > 0 &&
            distanceCovered >= deltaCount * deltaThreshold
        val triggering = armed && value.isFinite() && pastBorder && (runComplete || earlyTrigger)
        if (triggering) {
            armed = false
            return true
        }
        return false
    }

    private fun advanceRun(value: Float, readingTimeMs: Long, deltaThreshold: Float, intervalMinutes: Int) {
        val minGap = GlucoseDelta.minGapMillis(intervalMinutes)
        // Walk back to the newest sample old enough to span (about) one interval, like the readout.
        var prev: Sample? = null
        val iterator = history.listIterator(history.size)
        while (iterator.hasPrevious()) {
            val sample = iterator.previous()
            if (readingTimeMs - sample.timeMs >= minGap) {
                prev = sample
                break
            }
        }

        val delta = if (prev != null) {
            GlucoseDelta.delta(readingTimeMs, value, prev.timeMs, prev.value, intervalMinutes)
        } else {
            Float.NaN
        }

        if (delta.isNaN()) {
            // Not enough history yet, or the pair straddles a data gap: break the run.
            breakRun()
            return
        }

        val steep = if (falling) delta <= -deltaThreshold else delta >= deltaThreshold
        if (count == 0) {
            if (steep) {
                // Run start: this rolling delta already spans one interval of movement. Its
                // older sample anchors the early trigger's distance measurement.
                count = 1
                lastCheckpointMs = readingTimeMs
                runReferenceValue = prev?.value ?: Float.NaN
            }
            return
        }

        // A reversal against the alarming direction breaks the run immediately, even between
        // checkpoints; a merely-not-steep-enough delta is left for the next checkpoint to judge.
        val reversed = if (falling) delta > 0f else delta < 0f
        if (reversed) {
            breakRun()
            return
        }

        // Checkpoint: the first reading a full interval past the last one. Its rolling delta
        // covers the adjacent, non-overlapping interval; between checkpoints the counter holds.
        if (readingTimeMs - lastCheckpointMs >= GlucoseDelta.windowMillis(intervalMinutes)) {
            if (steep) {
                count += 1
                lastCheckpointMs = readingTimeMs
            } else {
                breakRun()
            }
        }
    }

    private fun breakRun() {
        count = 0
        lastCheckpointMs = NO_READING
        runReferenceValue = Float.NaN
        armed = true  // run broken -> eligible to fire again on the next run
    }

    private fun pruneOld(readingTimeMs: Long, intervalMinutes: Int) {
        // Keep a little more than the max usable gap so the walk-back always has candidates.
        val keepMillis = GlucoseDelta.minGapMillis(intervalMinutes) + 21L * 60_000L
        while (history.isNotEmpty() && readingTimeMs - history.first().timeMs > keepMillis) {
            history.removeFirst()
        }
    }
}
