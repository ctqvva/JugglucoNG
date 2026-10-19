package tk.glucodata.data.calibration

import kotlin.math.abs

/**
 * Decides which journal blood-glucose entries become calibration points.
 *
 * A finger stick logged in the journal — typed in by hand, or pushed in by a
 * Bluetooth meter — already carries everything a manual calibration needs: a
 * time and a reference value. All that is missing is the sensor lane reading
 * from the same moment, which is why every entry has to be paired against the
 * stored history before it can be used.
 *
 * The plan is a full reconciliation rather than an append: journal entries get
 * edited, re-timed and deleted, and the calibration derived from one has to
 * follow. Anything the user entered by hand is left strictly alone.
 */
object JournalCalibrationPolicy {
    /**
     * How far a sensor reading may sit from the finger stick and still be
     * treated as the value the meter was measured against. A CGM reports every
     * one to five minutes, so a wider gap means the pairing is interpolated
     * across a stretch nothing was recorded in — exactly where a calibration
     * does the most damage.
     */
    const val MATCH_WINDOW_MS = 10L * 60L * 1000L

    /**
     * A hand-entered calibration this close to an entry is taken to be that same
     * finger stick, entered through the calibration sheet. The manual point wins
     * and the journal entry is skipped, so one measurement never lands twice.
     */
    const val DUPLICATE_WINDOW_MS = 60L * 1000L

    /** Values outside a plausible meter range are treated as mistyped, not as truth. */
    const val MIN_MGDL = 20f
    const val MAX_MGDL = 600f

    /** A clock-skewed entry from the future must not steer the current curve. */
    const val FUTURE_TOLERANCE_MS = 5L * 60L * 1000L

    /** A journal entry that carries a blood-glucose value. */
    data class JournalBloodGlucose(
        val entryId: Long,
        val timestamp: Long,
        val glucoseMgDl: Float,
    )

    /** Both sensor lanes as stored at one moment, in the user's display unit. */
    data class SensorLanes(
        val timestamp: Long,
        val autoValue: Float,
        val rawValue: Float,
    )

    data class Plan(
        val inserts: List<CalibrationEntity> = emptyList(),
        val updates: List<CalibrationEntity> = emptyList(),
        val deleteIds: List<Int> = emptyList(),
    ) {
        val isEmpty: Boolean get() = inserts.isEmpty() && updates.isEmpty() && deleteIds.isEmpty()
    }

    /**
     * Reconciles the journal-derived calibrations of one sensor.
     *
     * [history] must hold the sensor's own readings in the display unit and must
     * not be empty — an empty history is indistinguishable from "no entry pairs
     * any more", and acting on it would wipe every derived point. The caller
     * checks that before planning.
     *
     * [existing] is the whole calibration table; rows belonging to other sensors
     * are ignored rather than deleted, since their history was never loaded.
     */
    fun plan(
        entries: List<JournalBloodGlucose>,
        history: List<SensorLanes>,
        existing: List<CalibrationEntity>,
        sensorId: String,
        isMmol: Boolean,
        nowMillis: Long,
        matchesSensor: (String, String) -> Boolean,
    ): Plan {
        if (sensorId.isBlank()) return Plan()

        val ownRows = existing.filter { matchesSensor(it.sensorId, sensorId) }
        val derivedRows = ownRows.filter { it.journalEntryId != null }
        val manualTimestamps = ownRows.filter { it.journalEntryId == null }.map { it.timestamp }

        val orderedHistory = history.sortedBy { it.timestamp }
        val desired = LinkedHashMap<Long, CalibrationEntity>()

        eligibleEntries(entries, nowMillis).forEach { entry ->
            if (manualTimestamps.any { abs(it - entry.timestamp) <= DUPLICATE_WINDOW_MS }) return@forEach
            val lanes = nearestLanes(orderedHistory, entry.timestamp) ?: return@forEach
            val autoValue = lanes.autoValue.takeIf { it.isFinite() && it > 0f }
                ?: lanes.rawValue.takeIf { it.isFinite() && it > 0f }
                ?: return@forEach
            val rawValue = lanes.rawValue.takeIf { it.isFinite() && it > 0f } ?: autoValue
            desired[entry.entryId] = CalibrationEntity(
                timestamp = entry.timestamp,
                sensorId = sensorId,
                sensorValue = autoValue,
                sensorValueRaw = rawValue,
                userValue = displayValue(entry.glucoseMgDl, isMmol),
                isRawMode = false,
                journalEntryId = entry.entryId,
            )
        }

        val existingByEntryId = derivedRows.associateBy { it.journalEntryId }
        val inserts = mutableListOf<CalibrationEntity>()
        val updates = mutableListOf<CalibrationEntity>()
        desired.forEach { (entryId, row) ->
            val current = existingByEntryId[entryId]
            if (current == null) {
                inserts += row
                return@forEach
            }
            // The user's own on/off choice for a derived point survives a re-pair.
            val merged = row.copy(id = current.id, isEnabled = current.isEnabled)
            if (merged != current) updates += merged
        }

        val deleteIds = derivedRows
            .filter { it.journalEntryId !in desired.keys }
            .map { it.id }

        return Plan(inserts = inserts, updates = updates, deleteIds = deleteIds)
    }

    /**
     * Entries worth pairing, newest first within each duplicate window: two
     * finger sticks a few seconds apart are one measurement entered twice, and
     * counting both would weight that moment double in the fit.
     */
    private fun eligibleEntries(
        entries: List<JournalBloodGlucose>,
        nowMillis: Long,
    ): List<JournalBloodGlucose> {
        val ordered = entries
            .asSequence()
            .filter { it.timestamp > 0L && it.timestamp <= nowMillis + FUTURE_TOLERANCE_MS }
            .filter { it.glucoseMgDl.isFinite() && it.glucoseMgDl in MIN_MGDL..MAX_MGDL }
            .sortedWith(compareByDescending<JournalBloodGlucose> { it.timestamp }.thenByDescending { it.entryId })
            .toList()

        val kept = mutableListOf<JournalBloodGlucose>()
        ordered.forEach { candidate ->
            if (kept.none { abs(it.timestamp - candidate.timestamp) <= DUPLICATE_WINDOW_MS }) {
                kept += candidate
            }
        }
        return kept
    }

    /** The stored reading closest to [timestamp], or null when none is near enough. */
    private fun nearestLanes(orderedHistory: List<SensorLanes>, timestamp: Long): SensorLanes? {
        if (orderedHistory.isEmpty()) return null
        var low = 0
        var high = orderedHistory.size - 1
        var best: SensorLanes? = null
        var bestDistance = Long.MAX_VALUE
        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = orderedHistory[mid]
            val distance = abs(candidate.timestamp - timestamp)
            if (distance < bestDistance) {
                bestDistance = distance
                best = candidate
            }
            when {
                candidate.timestamp < timestamp -> low = mid + 1
                candidate.timestamp > timestamp -> high = mid - 1
                else -> return candidate
            }
        }
        return best?.takeIf { bestDistance <= MATCH_WINDOW_MS }
    }

    /**
     * Calibrations are stored in the display unit, the way the calibration sheet
     * writes them; the journal always stores mg/dL.
     */
    private fun displayValue(mgdl: Float, isMmol: Boolean): Float =
        if (isMmol) mgdl / MGDL_PER_MMOL else mgdl

    private const val MGDL_PER_MMOL = 18.0182f
}
