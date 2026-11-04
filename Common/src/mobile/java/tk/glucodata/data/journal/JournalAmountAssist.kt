package tk.glucodata.data.journal

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * How someone tends to log an amount, read from what they typed themselves.
 *
 * @param step what one press of the stepper adds.
 * @param usual what they usually log around this time of day; null until the log can say.
 */
data class JournalAmountHabit(
    val step: Float,
    val usual: Float?
)

/** Food logged recently that no dose has answered yet. */
data class JournalUncoveredMeal(
    val carbsGrams: Float,
    val proteinGrams: Float?,
    val fatGrams: Float?
)

/**
 * Where the journal sheet's amount steppers start and how far they move.
 *
 * This only repeats the person's own logging back to them. It estimates no coefficient and
 * changes no setting, which is the job of the confirmed ICR and ISF estimates planned for #722.
 * A dose worked out from a meal goes through [JournalDoseCalculator] and whatever ratio the
 * person has saved, so it follows those estimates once they exist.
 */
object JournalAmountAssist {
    const val DEFAULT_INSULIN_STEP = 0.5f
    const val DEFAULT_CARB_STEP = 5f

    /** Coarsest first: the first one that fits the log wins. */
    private val INSULIN_STEPS = listOf(1f, 0.5f, 0.25f, 0.1f)
    private val CARB_STEPS = listOf(15f, 10f, 5f)

    private const val HABIT_WINDOW_MILLIS = 60L * 24 * 60 * 60 * 1000

    /** Below this many entries, whole-unit logging can be plain chance. */
    private const val STEP_MIN_SAMPLES = 8
    private const val STEP_MIN_SHARE = 0.85f
    private const val GRID_TOLERANCE = 1e-3f

    /** Either side of the entry's time of day. */
    private const val TIME_OF_DAY_WINDOW_MINUTES = 120
    private const val MINUTES_PER_DAY = 24 * 60
    private const val USUAL_MIN_AT_THIS_TIME = 3
    private const val USUAL_MIN_OVERALL = 5

    private const val MEAL_WINDOW_MILLIS = 90L * 60 * 1000

    /** A dose this long before food is a pre-bolus for it, not an earlier meal's dose. */
    private const val PRE_BOLUS_MILLIS = 30L * 60 * 1000

    fun insulinHabit(
        entries: List<JournalEntry>,
        presetId: Long?,
        atMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): JournalAmountHabit = habit(
        entries = typedEntries(entries, atMillis) {
            it.type == JournalEntryType.INSULIN && it.insulinPresetId == presetId
        },
        steps = INSULIN_STEPS,
        defaultStep = DEFAULT_INSULIN_STEP,
        atMillis = atMillis,
        timeZone = timeZone
    )

    fun carbHabit(
        entries: List<JournalEntry>,
        atMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): JournalAmountHabit = habit(
        entries = typedEntries(entries, atMillis) { it.type == JournalEntryType.CARBS },
        steps = CARB_STEPS,
        defaultStep = DEFAULT_CARB_STEP,
        atMillis = atMillis,
        timeZone = timeZone
    )

    /**
     * Food from the last 90 minutes with no dose after it. Any dose counts, however small,
     * so a split bolus reads as covered: the worst this can do is say nothing.
     */
    fun uncoveredMeal(
        entries: List<JournalEntry>,
        presetsById: Map<Long, JournalInsulinPreset>,
        atMillis: Long
    ): JournalUncoveredMeal? {
        val lastDose = entries.asSequence()
            .filter { it.type == JournalEntryType.INSULIN && it.timestamp <= atMillis }
            .filter { entry ->
                entry.insulinPresetId?.let(presetsById::get)?.useForCalculation == true &&
                    (entry.amount ?: 0f) > 0f
            }
            .maxOfOrNull { it.timestamp }
        val meals = entries.filter { entry ->
            entry.type == JournalEntryType.CARBS &&
                entry.timestamp in (atMillis - MEAL_WINDOW_MILLIS)..atMillis &&
                (entry.amount ?: 0f) > 0f &&
                (lastDose == null || entry.timestamp > lastDose + PRE_BOLUS_MILLIS)
        }
        if (meals.isEmpty()) return null
        return JournalUncoveredMeal(
            carbsGrams = meals.sumOf { it.amount!!.toDouble() }.toFloat(),
            proteinGrams = meals.sumOfPresent { it.proteinGrams },
            fatGrams = meals.sumOfPresent { it.fatGrams }
        )
    }

    /**
     * One press away from [current], landing on the step's grid: with 1 U steps, 2.5 goes to
     * 3 and 2. Never below zero.
     */
    fun stepped(current: Float, direction: Int, step: Float): Float {
        val position = current / step
        val nearest = position.roundToInt()
        val next = when {
            abs(position - nearest) < GRID_TOLERANCE -> nearest + direction
            direction > 0 -> ceil(position).toInt()
            else -> floor(position).toInt()
        }
        return (next * step).coerceAtLeast(0f)
    }

    /** Only amounts someone chose: a pump's 4.35 U says nothing about how they count. */
    private inline fun typedEntries(
        entries: List<JournalEntry>,
        atMillis: Long,
        matches: (JournalEntry) -> Boolean
    ): List<JournalEntry> = entries.filter { entry ->
        matches(entry) &&
            (entry.originSource ?: entry.source) == JournalEntrySource.MANUAL &&
            entry.timestamp in (atMillis - HABIT_WINDOW_MILLIS)..atMillis &&
            entry.amount?.let { it.isFinite() && it > 0f } == true
    }

    private fun habit(
        entries: List<JournalEntry>,
        steps: List<Float>,
        defaultStep: Float,
        atMillis: Long,
        timeZone: TimeZone
    ): JournalAmountHabit {
        val step = learnedStep(entries.map { it.amount!! }, steps, defaultStep)
        return JournalAmountHabit(step, usualAmount(entries, step, atMillis, timeZone))
    }

    private fun learnedStep(amounts: List<Float>, steps: List<Float>, defaultStep: Float): Float {
        if (amounts.size < STEP_MIN_SAMPLES) return defaultStep
        return steps.firstOrNull { step ->
            amounts.count { onGrid(it, step) } >= amounts.size * STEP_MIN_SHARE
        } ?: defaultStep
    }

    private fun onGrid(amount: Float, step: Float): Boolean {
        val position = amount / step
        return abs(position - position.roundToInt()) < GRID_TOLERANCE
    }

    /**
     * The amount logged most often around this time of day, or across the whole log when
     * this time of day has too few entries. With nothing repeated, the middle of the log.
     */
    private fun usualAmount(
        entries: List<JournalEntry>,
        step: Float,
        atMillis: Long,
        timeZone: TimeZone
    ): Float? {
        val minuteOfDay = minuteOfDay(atMillis, timeZone)
        val atThisTime = entries.filter {
            timeOfDayDistance(minuteOfDay(it.timestamp, timeZone), minuteOfDay) <= TIME_OF_DAY_WINDOW_MINUTES
        }
        val pool = when {
            atThisTime.size >= USUAL_MIN_AT_THIS_TIME -> atThisTime
            entries.size >= USUAL_MIN_OVERALL -> entries
            else -> return null
        }
        val byAmount = pool.groupBy { (it.amount!! / step).roundToInt() }
        val (position, group) = byAmount.entries
            .maxWith(compareBy({ it.value.size }, { group -> group.value.maxOf { it.timestamp } }))
            .let { it.key to it.value }
        val usualPosition = if (group.size >= 2) {
            position
        } else {
            byAmount.keys.sorted().let { it[(it.size - 1) / 2] }
        }
        return (usualPosition * step).coerceAtLeast(step)
    }

    private fun minuteOfDay(millis: Long, timeZone: TimeZone): Int {
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = millis }
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }

    private fun timeOfDayDistance(first: Int, second: Int): Int {
        val distance = abs(first - second)
        return minOf(distance, MINUTES_PER_DAY - distance)
    }

    private inline fun List<JournalEntry>.sumOfPresent(amount: (JournalEntry) -> Float?): Float? {
        val present = mapNotNull(amount)
        return if (present.isEmpty()) null else present.sumOf { it.toDouble() }.toFloat()
    }
}
