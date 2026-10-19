package tk.glucodata.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import tk.glucodata.GlucoseDelta
import java.util.Locale

/**
 * The per-row "Δ" column of the dashboard readings list is the hero readout
 * anchored in the past: [readingDeltaTexts] is the one function both call, so a
 * row and the hero can never show different deltas for the same reading.
 *
 * These tests pin that identity three ways: the newest row against the hero's
 * own call shape, every row against a literal per-row walk-back (the pre-change
 * hero algorithm), and the arithmetic against [GlucoseDelta] directly.
 */
class DashboardReadingDeltaTests {

    private val nowMillis = 1_700_000_000_000L
    private val minute = 60_000L

    // The "Δ" text follows the device locale's decimal separator; these tests
    // assert a literal ".", so pin the locale rather than the host's.
    private val originalLocale: Locale = Locale.getDefault()

    @Before
    fun useDotDecimalLocale() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    /** Oldest-first, like the hero's history input. */
    private fun oneMinuteCadence(count: Int, valueAt: (minutesAgo: Int) -> Float): List<GlucosePoint> =
        (count - 1 downTo 0).map { minutesAgo ->
            GlucosePoint(
                value = valueAt(minutesAgo),
                time = "",
                timestamp = nowMillis - minutesAgo * minute
            )
        }

    /** Newest-first, like buildDisplayReadings hands the rows to the card. */
    private fun rowsOf(history: List<GlucosePoint>, limit: Int = 10): List<GlucosePoint> =
        buildDisplayReadings(history, limit = limit)

    /**
     * The hero's original inline walk-back, kept verbatim as the reference: the
     * first point old enough for the window, then GlucoseDelta over the pair.
     */
    private fun heroReference(
        history: List<GlucosePoint>,
        anchor: GlucosePoint,
        isMmol: Boolean,
        intervalMinutes: Int
    ): String? {
        val previous = history.asReversed().firstOrNull { p ->
            p.value > 0.1f && anchor.timestamp - p.timestamp >= GlucoseDelta.minGapMillis(intervalMinutes)
        } ?: return null
        val delta = GlucoseDelta.delta(
            anchor.timestamp, anchor.value,
            previous.timestamp, previous.value,
            intervalMinutes
        )
        return GlucoseDelta.format(delta, isMmol).takeIf { it.isNotEmpty() }
    }

    @Test
    fun newestRowIsBitIdenticalToTheHeroReadout() {
        val history = oneMinuteCadence(count = 30) { minutesAgo -> 100f + minutesAgo * 2f }
        val rows = rowsOf(history)

        // The hero's exact call shape (DashboardComponents.kt) for the newest point.
        val hero = readingDeltaTexts(
            listOf(history.last().timestamp), history, false, 5
        ).first()
        val newestRow = readingDeltaTexts(
            rows.map { it.timestamp }, history, false, 5
        ).first()

        assertEquals(hero, newestRow)
        assertEquals(heroReference(history, history.last(), false, 5), newestRow)
    }

    @Test
    fun rawPrimaryDoesNotShowAutoMovementOnAFlatRawSeries() {
        for (isMmol in listOf(false, true)) for (interval in listOf(1, 5)) {
            val divisor = if (isMmol) 18.0182f else 1f
            val history = oneMinuteCadence(10) { minutesAgo -> (110f - minutesAgo * 2f) / divisor }
                .map { it.copy(rawValue = 74.2f / divisor) }
            val anchors = listOf(history.last().timestamp)
            val raw = readingDeltaTexts(anchors, history, isMmol, interval, useRaw = true).single()
            val auto = readingDeltaTexts(anchors, history, isMmol, interval).single()
            assertEquals(if (isMmol) "0.0" else "0", raw)
            org.junit.Assert.assertNotEquals(raw, auto)
            // A fresh history list on foreground refresh keeps the same lane.
            assertEquals(raw, readingDeltaTexts(anchors, history.map { it.copy() }, isMmol, interval, useRaw = true).single())
        }
    }

    @Test
    fun rawDeltaUsesTheSameWalkBackAndFallbackAsTheNotification() {
        val history = oneMinuteCadence(10) { 100f }.mapIndexed { index, point ->
            point.copy(rawValue = if (index == 4) 0f else 80f + index * 2f)
        }
        for (interval in listOf(1, 5)) {
            val newest = history.last()
            val previous = history.asReversed().first { newest.timestamp - it.timestamp >= GlucoseDelta.minGapMillis(interval) }
            val expected = GlucoseDelta.format(GlucoseDelta.delta(newest.timestamp, newest.rawValue,
                previous.timestamp, if (previous.rawValue > 0f) previous.rawValue else previous.value, interval), false)
            assertEquals(expected, readingDeltaTexts(listOf(newest.timestamp), history, false, interval, useRaw = true).single())
        }
    }

    @Test
    fun everyRowMatchesAPerRowWalkBack() {
        // Mixed shape: a climb, a plateau of repeats, a sensor dropout value.
        val values = listOf(90f, 92f, 92f, 0f, 95f, 99f, 104f, 104f, 110f, 118f, 121f, 119f, 122f, 130f, 133f)
        val history = (0 until values.size).map { i ->
            GlucosePoint(value = values[i], time = "", timestamp = nowMillis - (values.size - 1 - i) * minute)
        }
        val rows = rowsOf(history)

        for (intervalMinutes in listOf(1, 5)) {
            val onePass = readingDeltaTexts(rows.map { it.timestamp }, history, false, intervalMinutes)
            rows.forEachIndexed { index, row ->
                assertEquals(
                    "row $index (interval $intervalMinutes) must match the hero's walk-back",
                    heroReference(history, row, false, intervalMinutes),
                    onePass[index]
                )
            }
        }
    }

    @Test
    fun fiveMinuteWindowOnOneMinuteCadenceIsThePlainDifference() {
        // 2 mg/dL per minute: value(t) - value(t - 5 min) = 10, gap exactly the
        // window, so no rescaling — the column shows the raw difference.
        val history = oneMinuteCadence(count = 30) { minutesAgo -> 100f + (30 - minutesAgo) * 2f }
        val rows = rowsOf(history)
        val texts = readingDeltaTexts(rows.map { it.timestamp }, history, false, 5)

        assertEquals("+10", texts.first())
        val expected = GlucoseDelta.format(
            GlucoseDelta.delta(
                history.last().timestamp, history.last().value,
                history[history.size - 6].timestamp, history[history.size - 6].value,
                5
            ),
            false
        )
        assertEquals(expected, texts.first())
    }

    @Test
    fun columnFollowsTheIntervalSetting() {
        val history = oneMinuteCadence(count = 30) { minutesAgo -> 100f + (30 - minutesAgo) * 2f }
        val rows = rowsOf(history)

        val oneMinute = readingDeltaTexts(rows.map { it.timestamp }, history, false, 1)
        val fiveMinute = readingDeltaTexts(rows.map { it.timestamp }, history, false, 5)

        assertEquals("+2", oneMinute.first())
        assertEquals("+10", fiveMinute.first())
        // And each stays the hero's own value for that interval.
        assertEquals(
            readingDeltaTexts(listOf(history.last().timestamp), history, false, 1).first(),
            oneMinute.first()
        )
        assertEquals(
            readingDeltaTexts(listOf(history.last().timestamp), history, false, 5).first(),
            fiveMinute.first()
        )
    }

    @Test
    fun rowsWithoutAnOldEnoughPartnerStayEmpty() {
        // Only 4 minutes of data: no pair satisfies the 5-minute walk-back, for
        // any row — empty column, no NaN text.
        val history = oneMinuteCadence(count = 4) { minutesAgo -> 100f + minutesAgo * 2f }
        val rows = rowsOf(history)
        val texts = readingDeltaTexts(rows.map { it.timestamp }, history, false, 5)

        assertEquals(rows.size, texts.size)
        texts.forEach { assertNull(it) }
    }

    @Test
    fun aDataGapWiderThanThePairingRuleYieldsEmptyNotNan() {
        // 25-minute hole: the walk-back lands on a point too old to pair with
        // (GlucoseDelta caps at 20 minutes), so the rows right after the gap
        // stay empty instead of showing a stretched number.
        val afterGap = (4 downTo 0).map { minutesAgo ->
            GlucosePoint(value = 120f + minutesAgo, time = "", timestamp = nowMillis - minutesAgo * minute)
        }
        val beforeGap = (3 downTo 0).map { i ->
            GlucosePoint(value = 100f + i, time = "", timestamp = nowMillis - 25 * minute - i * minute)
        }
        val history = beforeGap + afterGap
        val rows = rowsOf(history)
        val texts = readingDeltaTexts(rows.map { it.timestamp }, history, false, 5)

        rows.forEachIndexed { index, row ->
            assertEquals(heroReference(history, row, false, 5), texts[index])
        }
        assertNull("newest row sits 25+ min after its walk-back target", texts.first())
    }

    @Test
    fun mmolKeepsOneDecimalAndItsSign() {
        val history = oneMinuteCadence(count = 30) { minutesAgo -> 5.0f + (30 - minutesAgo) * 0.1f }
        val rows = rowsOf(history)
        val texts = readingDeltaTexts(rows.map { it.timestamp }, history, true, 5)

        assertEquals("+0.5", texts.first())
        assertEquals(heroReference(history, history.last(), true, 5), texts.first())
    }

    @Test
    fun emptyInputsProduceEmptyColumns() {
        assertEquals(emptyList<String?>(), readingDeltaTexts(emptyList(), emptyList(), false, 5))
        val texts = readingDeltaTexts(listOf(nowMillis), emptyList(), false, 5)
        assertEquals(listOf(null as String?), texts)
    }
}
