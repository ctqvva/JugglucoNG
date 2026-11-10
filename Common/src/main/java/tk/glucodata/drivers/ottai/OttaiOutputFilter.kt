package tk.glucodata.drivers.ottai

import kotlin.math.abs

/**
 * Final output gate before Ottai readings are allowed into current publishing or storage.
 *
 * The parser/formula should stay literal. This gate handles vendor output validity and
 * single-sample electrode-current excursions observed on live hardware.
 */
object OttaiOutputFilter {
    const val MIN_RAW_CURRENT = 1_000
    const val MAX_TEMPERATURE_C = 45.0

    // A body-worn sensor never reports this cold. The gate had no lower bound at all, so a
    // corrupt frame only got caught when its temperature happened to decode HIGH — the
    // 2026-07-14 corruption produced 185.07, 360.93, 421.01, 325.39 and 388.72 C, all refused,
    // while nothing would have stopped the same garbage decoding to a negative value.
    const val MIN_TEMPERATURE_C = 15.0
    const val MAX_GLUCOSE_MMOL = 40.0f

    // A one-minute CGM point moving this far while the electrode current jumps this much
    // is treated as sensor noise. The next normal sample is accepted against the last
    // accepted baseline; no replacement value is fabricated.
    const val SINGLE_SAMPLE_DELTA_MMOL = 1.5f
    const val RAW_EXCURSION_RATIO = 0.18f

    /** Smallest history frame worth judging as a whole; a couple of records prove nothing. */
    const val MISFRAMED_MIN_RECORDS = 8

    /**
     * Temperatures no body-worn sensor reports, however cold or hot its surroundings. Well
     * outside the hard gate's 15–45 C on purpose: a sensor outdoors in winter really does sit
     * below 15 C, and its frame must not be mistaken for a misframed one.
     */
    const val IMPOSSIBLE_TEMPERATURE_LOW_C = 0.0
    const val IMPOSSIBLE_TEMPERATURE_HIGH_C = 60.0

    /**
     * True when a history frame is almost certainly decoded at the wrong record width.
     *
     * A frame read at the wrong width is not a few bad records among good ones: nearly all of
     * it fails the hard gate, and the few survivors are coincidences where the two grids line
     * up (2026-09-26: 2 of 17 passed, as a constant ~4.5 mmol/L). Those survivors are still
     * plausible-looking glucose, so they are worse than the rejects — the frame is judged
     * whole and none of it is stored.
     *
     * Failing the hard gate is not proof on its own: a cold sensor fails it on temperature
     * with every record correctly framed, and its few passing readings are real. So the frame
     * must also be mostly physically impossible temperatures (the trace: 76–535 C), which a
     * correctly framed record never decodes to. Withholding real readings on a guess is not
     * allowed; this only drops what cannot be a reading.
     */
    fun isMisframedFrame(readings: List<OttaiReading>): Boolean {
        if (readings.size < MISFRAMED_MIN_RECORDS) return false
        val accepted = readings.count { hardRejectReason(it.record, it.adjustGlucose.toFloat()) == null }
        if (accepted * 4 >= readings.size) return false
        val impossible = readings.count { isImpossibleTemperature(it.record.temperatureC) }
        return impossible * 2 > readings.size
    }

    private fun isImpossibleTemperature(celsius: Double): Boolean =
        !celsius.isFinite() || celsius < IMPOSSIBLE_TEMPERATURE_LOW_C || celsius > IMPOSSIBLE_TEMPERATURE_HIGH_C

    fun hardRejectReason(record: OttaiRecord, mmol: Float): String? {
        if (!mmol.isFinite() || mmol <= 0f) return "glucose=$mmol"
        if (mmol > MAX_GLUCOSE_MMOL) return "glucose=$mmol"
        if (record.rawCurrent < MIN_RAW_CURRENT) return "raw=${record.rawCurrent}"
        if (!record.temperatureC.isFinite() ||
            record.temperatureC > MAX_TEMPERATURE_C ||
            record.temperatureC < MIN_TEMPERATURE_C
        ) {
            return "temp=${record.temperatureC}"
        }
        return null
    }

    fun isOneMinuteRawExcursion(
        candidateMmol: Float,
        candidateRaw: Int,
        baselineMmol: Float,
        baselineRaw: Int,
    ): Boolean {
        if (!candidateMmol.isFinite() || !baselineMmol.isFinite()) return false
        if (candidateMmol <= 0f || baselineMmol <= 0f) return false
        if (candidateRaw <= 0 || baselineRaw <= 0) return false

        val glucoseDelta = abs(candidateMmol - baselineMmol)
        val rawDeltaRatio = abs(candidateRaw - baselineRaw).toFloat() / baselineRaw
        return glucoseDelta >= SINGLE_SAMPLE_DELTA_MMOL &&
            rawDeltaRatio >= RAW_EXCURSION_RATIO
    }
}
