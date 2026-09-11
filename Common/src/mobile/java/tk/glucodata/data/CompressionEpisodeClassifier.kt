package tk.glucodata.data

import android.content.Context
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalIobCalculator
import tk.glucodata.data.prediction.PredictionModelProfileStore
import tk.glucodata.logic.CompressionLowDetector

/**
 * Runs the retrospective sensor-pressure detector over a recorded trace with the
 * journal answering its questions — the suggestion engine behind the hypo episode log.
 * Its output only ever pre-marks; the user's toggle is the authority, and a manual mark
 * is never overwritten by a re-scan.
 *
 * The ISF handed to the detector is the LARGEST sensitivity across the profile's
 * time-of-day blocks: more explainable drop, fewer automatic "pressure" suggestions —
 * when a single number must stand in for a day curve, it errs toward calling lows real.
 */
object CompressionEpisodeClassifier {

    suspend fun detectPressureEpisodes(
        context: Context,
        samples: List<CompressionLowDetector.Sample>,
        rangeStartMs: Long,
        rangeEndMs: Long,
        tuning: CompressionLowDetector.Tuning
    ): List<CompressionLowDetector.Episode> {
        if (samples.isEmpty()) return emptyList()
        val dao = HistoryDatabase.getInstance(context).journalDao()
        val presetsById = dao.getInsulinPresets()
            .map { tk.glucodata.OutboundApiJournalSnapshot.toPresetModel(it) }
            .associateBy { it.id }
        val maxPresetDurationMs = presetsById.values
            .maxOfOrNull { it.durationMinutes.coerceAtLeast(0) }?.times(60_000L) ?: DAY_MS
        val entries = dao.getEntriesBetween(
            (rangeStartMs - maxOf(DAY_MS, maxPresetDurationMs) - 60_000L).coerceAtLeast(0L),
            rangeEndMs
        )
        val doses = JournalIobCalculator.dosesFromEntities(entries, presetsById)
        val carbs = entries.filter {
            it.entryType == JournalEntryType.CARBS.storageValue && (it.amount ?: 0f) > 0f
        }

        val prefs = context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
        val isf = PredictionModelProfileStore.load(prefs).blocks
            .maxOf { it.parameters.insulinSensitivityMgDlPerUnit }

        return CompressionLowDetector.detect(
            samples = samples,
            isfMgdlPerUnit = isf,
            iobUnitsAt = { at ->
                JournalIobCalculator.compute(doses.filter { it.timestampMillis <= at }, at).iobUnits
            },
            dosePeakPassedAt = { at ->
                val newest = doses.filter { it.timestampMillis <= at }
                    .maxByOrNull { it.timestampMillis }
                // No dose on board is a vacuous pass — the IOB gate judges the amount.
                newest == null || CompressionLowDetector.dosePeakPassed(
                    newest.timestampMillis,
                    newest.preset.curvePoints.map { it.minute to it.activity },
                    at
                )
            },
            carbGramsBetween = { start, end ->
                carbs.filter { it.timestamp in start..end }.mapNotNull { it.amount }.sum()
            },
            tuning = tuning
        )
    }

    private const val DAY_MS = 24L * 60 * 60_000L
}
