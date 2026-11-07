package tk.glucodata.data.calibration

import java.util.concurrent.atomic.AtomicLong
import tk.glucodata.ui.util.GlucoseFormatter

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
 * the point afterwards still takes that back, and [Changes.switched] is what
 * lets it tell that from what was recorded while the point was off.
 */
internal object CalibrationPointChanges {

    data class Changes(
        val gone: List<CalibrationEntity>,
        val arrived: List<CalibrationEntity>,
        /** Rows of the new table whose point was in the other position before. */
        val switched: List<CalibrationEntity>,
    ) {
        val isEmpty: Boolean get() = gone.isEmpty() && arrived.isEmpty() && switched.isEmpty()
    }

    /** Values in tenths of a mg/dL. */
    private data class Effect(
        val timestamp: Long,
        val isRawMode: Boolean,
        /** A journal point counts in both lanes; the entry it came from changes nothing. */
        val isJournalDerived: Boolean,
        val sensorValue: Int,
        val sensorValueRaw: Int,
        /** Where an integrating driver's fit places the point. */
        val sensorValueStock: Int,
        val userValue: Int,
    )

    private fun CalibrationEntity.effect() = Effect(
        timestamp, isRawMode, journalEntryId != null,
        tenths(sensorValue), tenths(sensorValueRaw), tenths(sensorValueStock), tenths(userValue),
    )

    /**
     * A value of [point] that does not depend on the display unit.
     *
     * The table is held in the display unit, and a change of unit converts what
     * is in memory while the next load converts the stored rows: 5.5 mmol/L
     * taken to mg/dL and back is not the float 5.5. Compared as floats, every
     * such point looked removed and added again. Tenths of a mg/dL are far finer
     * than any two calibrations differ by, and far coarser than that error.
     */
    private fun CalibrationEntity.tenths(value: Float): Int =
        Math.round((if (valueUnit == 1) value * GlucoseFormatter.MGDL_PER_MMOL else value) * 10f)

    /**
     * What tells two points at the same moment apart: a calibration takes the
     * timestamp of the reading it was made against, so a second finger stick on
     * that reading differs only in its value.
     */
    fun identity(point: CalibrationEntity): String =
        "${point.timestamp}_${if (point.isRawMode) "raw" else "auto"}_${point.tenths(point.userValue)}"

    fun between(before: List<CalibrationEntity>, after: List<CalibrationEntity>): Changes {
        return Changes(
            gone = missingFrom(after, before),
            arrived = missingFrom(before, after),
            switched = switchedBetween(before, after),
        )
    }

    private fun switchedBetween(before: List<CalibrationEntity>, after: List<CalibrationEntity>): List<CalibrationEntity> {
        val previously = before.groupBy { it.sensorId to it.effect() }
        return after.filter { row ->
            val same = previously[row.sensorId to row.effect()].orEmpty()
            same.isNotEmpty() && same.none { it.isEnabled == row.isEnabled }
        }
    }

    /**
     * The rows of [rows] with no counterpart in [others].
     *
     * Two sensors can hold the same point, a profile copied across for one, so
     * the sensor has to count: removing it from one is a removal even while the
     * other keeps it. But a sensor replacement re-files old points under an
     * archive id, which changes the sensor and not how many there are. That is
     * the driver retiring an electrode's points, not the user taking them back,
     * and what they sealed was sealed against the electrode they were measured
     * on. So a row only counts as missing when its effect has fewer rows, and
     * rows pair off one to one: a sensor can hold the same point twice, and the
     * twin left behind does not stand in for the one removed.
     */
    private fun missingFrom(others: List<CalibrationEntity>, rows: List<CalibrationEntity>): List<CalibrationEntity> {
        val counterparts = others.groupBy { it.effect() }
        return rows.groupBy { it.effect() }.flatMap { (effect, sameEffect) ->
            val matching = counterparts[effect].orEmpty()
            val surplus = sameEffect.size - matching.size
            if (surplus <= 0) return@flatMap emptyList()
            val unpaired = matching.mapTo(ArrayList()) { it.sensorId }
            sameEffect.filterNot { unpaired.remove(it.sensorId) }.take(surplus)
        }
    }
}

/**
 * The moments a point was switched, oldest first: off, on, off and so on.
 *
 * A point that is off is in no fit, so a minute recorded then owes it nothing,
 * and taking that minute back when the point goes would move a sealed value
 * whenever anything else about the calibration has changed since.
 */
internal object CalibrationPointSwitches {
    fun parse(stored: String?): List<Long> = stored.orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    fun encode(switches: List<Long>): String = switches.joinToString(",")

    fun isOffAt(switches: List<Long>, moment: Long): Boolean = switches.count { it <= moment } % 2 == 1

    /**
     * [switches] with the point now [off].
     *
     * Nothing to add when they already say so. That also covers a point that was
     * off before anything was noted: turning it on must not read as turning it off.
     */
    fun thenSwitched(switches: List<Long>, off: Boolean, at: Long): List<Long> =
        if ((switches.size % 2 == 1) == off) switches else switches + at
}

/**
 * Keeps an older read of the table from replacing a newer one.
 *
 * Two loads can run at once, and the one that read first can finish last. Applying
 * it would drop a point that is still stored, and now that a departed point
 * releases its records that is no longer only a stale list. A dropped load loses
 * nothing: whoever wrote the table loads again afterwards.
 *
 * The number has to be taken where the read is ordered against writes, inside
 * the read's write transaction, or a load numbered first can still read last.
 * Numbered rather than locked across the whole load because a load runs driver
 * callbacks part way through and is reached from runBlocking.
 */
internal class CalibrationLoadOrder {
    private val issued = AtomicLong()
    private var applied = 0L

    /** Call inside the transaction that reads the table. */
    fun begin(): Long = issued.incrementAndGet()

    /** Whether a read may be applied. Call under the lock that guards the state. */
    fun mayApply(load: Long): Boolean {
        if (load < applied) return false
        applied = load
        return true
    }
}
