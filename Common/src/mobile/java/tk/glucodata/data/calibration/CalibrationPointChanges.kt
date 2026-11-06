package tk.glucodata.data.calibration

import java.util.concurrent.atomic.AtomicLong

/**
 * Which calibration points appeared and which went, between two loads of the table.
 *
 * Compared by what a point does to the line, never by row id: a mirror sync or
 * a profile import rewrites unchanged points under new ids, and that must not
 * look like the user removing them. An edit is the old point going and a new
 * one arriving, which is what it means for anything recorded under the old one.
 *
 * A point switched off is neither. The switch is a way of looking at the line
 * without the point, and the user can flip it back, so what the point wrote
 * stays where it is, as it does for the sensor's own calibration switch. Removing
 * the point afterwards still takes it back.
 */
internal object CalibrationPointChanges {

    data class Changes(
        val gone: List<CalibrationEntity>,
        val arrived: List<CalibrationEntity>,
    ) {
        val isEmpty: Boolean get() = gone.isEmpty() && arrived.isEmpty()
    }

    private data class Effect(
        val timestamp: Long,
        val isRawMode: Boolean,
        /** A journal point counts in both lanes; the entry it came from changes nothing. */
        val isJournalDerived: Boolean,
        val sensorValue: Float,
        val sensorValueRaw: Float,
        /** Where an integrating driver's fit places the point. */
        val sensorValueStock: Float,
        val userValue: Float,
    )

    private fun CalibrationEntity.effect() = Effect(
        timestamp, isRawMode, journalEntryId != null,
        sensorValue, sensorValueRaw, sensorValueStock, userValue,
    )

    fun between(before: List<CalibrationEntity>, after: List<CalibrationEntity>): Changes {
        // Two units in play is the display unit changing under the table, not a
        // calibration change, and the converted floats would not compare equal.
        if ((before + after).distinctBy { it.valueUnit }.size > 1) return Changes(emptyList(), emptyList())
        return Changes(gone = missingFrom(after, before), arrived = missingFrom(before, after))
    }

    /**
     * The rows of [rows] with no counterpart in [others].
     *
     * Two sensors can hold the same point, a profile copied across for one, so
     * the sensor has to count: removing it from one is a removal even while the
     * other keeps it. But a sensor replacement re-files old points under an
     * archive id, which changes the sensor and not how many there are, and a
     * point that merely moved is still in force for the minutes it was in force
     * for. So a row only counts as missing when its effect has fewer rows.
     */
    private fun missingFrom(others: List<CalibrationEntity>, rows: List<CalibrationEntity>): List<CalibrationEntity> {
        val counterparts = others.groupBy { it.effect() }
        return rows.groupBy { it.effect() }.flatMap { (effect, sameEffect) ->
            val matching = counterparts[effect].orEmpty()
            val surplus = sameEffect.size - matching.size
            if (surplus <= 0) return@flatMap emptyList()
            sameEffect.filter { row -> matching.none { it.sensorId == row.sensorId } }.take(surplus)
        }
    }
}

/**
 * Keeps an older read of the table from replacing a newer one.
 *
 * Two loads can run at once, and the one that read first can finish last. Applying
 * it would drop a point that is still stored, and now that a departed point
 * releases its records that is no longer only a stale list. A dropped load loses
 * nothing: whoever wrote the table loads again afterwards.
 *
 * Numbered rather than locked because a load runs driver callbacks part way
 * through and is reached from runBlocking, where holding a lock could deadlock.
 */
internal class CalibrationLoadOrder {
    private val issued = AtomicLong()
    private var applied = 0L

    /** Call before reading the table. */
    fun begin(): Long = issued.incrementAndGet()

    /** Whether a read may be applied. Call under the lock that guards the state. */
    fun mayApply(load: Long): Boolean {
        if (load < applied) return false
        applied = load
        return true
    }
}
