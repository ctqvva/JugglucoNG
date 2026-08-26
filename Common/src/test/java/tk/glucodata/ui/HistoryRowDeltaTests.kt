package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The history screen's reading rows carry the dashboard's "Δ": [RowDeltaIndex] is
 * [readingDeltaTexts] keyed by reading, so for one sensor the two screens show the
 * same text for the same reading, and the gap rule that blanks a row after a hole in
 * the data holds there too. The history additionally spans sensors, and a row never
 * pairs with another sensor's point.
 */
class HistoryRowDeltaTests {

    private val nowMillis = 1_700_000_000_000L
    private val minute = 60_000L

    /** Oldest-first, like the history screen's sorted history. */
    private fun oneMinuteCadence(
        count: Int,
        sensor: String? = null,
        startMillis: Long = nowMillis,
        valueAt: (minutesAgo: Int) -> Float
    ): List<GlucosePoint> =
        (count - 1 downTo 0).map { minutesAgo ->
            GlucosePoint(
                value = valueAt(minutesAgo),
                time = "",
                timestamp = startMillis - minutesAgo * minute,
                sensorSerial = sensor
            )
        }

    /** Newest-first, the order the history draws its rows in. */
    private fun rowsOf(history: List<GlucosePoint>): List<GlucosePoint> = history.asReversed()

    /** Serials are names, nothing more, unless a test says otherwise. */
    private fun index(
        history: List<GlucosePoint>,
        sameSensor: (String, String) -> Boolean = { a, b -> a == b },
        isShared: (String) -> Boolean = { false }
    ) = RowDeltaIndex(history, sameSensor, isShared)

    private fun dashboardTexts(rows: List<GlucosePoint>, history: List<GlucosePoint>): List<String?> =
        readingDeltaTexts(rows.map { it.timestamp }, history, false, 5)

    @Test
    fun everyRowShowsWhatTheDashboardShowsForTheSameReading() {
        val history = oneMinuteCadence(count = 30) { minutesAgo -> 100f + minutesAgo * 2f }
        val rows = rowsOf(history)

        val dashboard = dashboardTexts(rows, history)
        val texts = index(history).textsFor(rows, false, 5)

        rows.forEachIndexed { i, row -> assertEquals("row $i", dashboard[i], texts[row]) }
        assertEquals("−10", texts[rows.first()])
        assertEquals(25, texts.size) // the five oldest rows have no partner 4.5 min back
    }

    @Test
    fun aWindowInTheMiddleOfTheHistoryMatchesTheDashboardToo() {
        // The history shows a day weeks back: only the rows of that window are asked
        // for, and they still pair with the points just before the window.
        val history = oneMinuteCadence(count = 200) { minutesAgo -> 100f + (minutesAgo % 17) }
        val window = history.subList(60, 90)
        val rows = rowsOf(window)

        val dashboard = dashboardTexts(rows, history)
        val texts = index(history).textsFor(rows, false, 5)

        rows.forEachIndexed { i, row -> assertEquals("row $i", dashboard[i], texts[row]) }
        assertEquals(30, texts.size)
    }

    @Test
    fun aRowWithoutAnOldEnoughPartnerIsAbsentNotEmpty() {
        val history = oneMinuteCadence(count = 3) { 100f }
        val rows = rowsOf(history)

        val texts = index(history).textsFor(rows, false, 5)

        assertTrue(texts.isEmpty())
    }

    @Test
    fun aGapWiderThanThePairingRuleBlanksTheRowAfterIt() {
        // A 25-minute hole, as the history shows after a dropped reader or a sensor
        // that stopped. The rows after it have no partner within the 20-minute cap
        // and show nothing rather than a number spanning the hole; the rows before
        // it, eight minutes of them, pair among themselves as they always did.
        val afterGap = (4 downTo 0).map { minutesAgo ->
            GlucosePoint(value = 120f + minutesAgo, time = "", timestamp = nowMillis - minutesAgo * minute)
        }
        val beforeGap = (7 downTo 0).map { i ->
            GlucosePoint(value = 100f + i, time = "", timestamp = nowMillis - 25 * minute - i * minute)
        }
        val history = beforeGap + afterGap
        val rows = rowsOf(history)

        val texts = index(history).textsFor(rows, false, 5)

        afterGap.forEach { assertNull("after the gap: $it", texts[it]) }
        assertEquals("−5", texts[beforeGap.last()])
        assertEquals(3, texts.size) // the three newest rows before the gap reach 5 min back
        assertFalse(texts.values.any { it.contains("NaN") })
        val dashboard = dashboardTexts(rows, history)
        rows.forEachIndexed { i, row -> assertEquals(dashboard[i], texts[row]) }
    }

    @Test
    fun aRowNeverPairsWithAnotherSensorsReading() {
        // Two sensors read side by side for ten minutes (the overlap of a swap):
        // interleaved on the timeline, one minute apart. Each row's partner has to be
        // its own sensor's point five minutes earlier, not the other sensor's.
        val old = oneMinuteCadence(count = 10, sensor = "OLD") { 100f }
        val new = oneMinuteCadence(count = 10, sensor = "NEW", startMillis = nowMillis - 30_000L) { 200f }
        val history = (old + new).sortedBy { it.timestamp }
        val rows = history.asReversed()

        val texts = index(history).textsFor(rows, false, 5)

        val oldOnly = dashboardTexts(old.asReversed(), old)
        val newOnly = dashboardTexts(new.asReversed(), new)
        old.asReversed().forEachIndexed { i, row -> assertEquals(oldOnly[i], texts[row]) }
        new.asReversed().forEachIndexed { i, row -> assertEquals(newOnly[i], texts[row]) }
        // Flat values on both sensors: a cross-sensor pair would have shown ±100.
        assertTrue(texts.values.all { it == "0" })
        assertNotNull(texts[old.last()])
        assertNotNull(texts[new.last()])
    }

    @Test
    fun theFirstRowsOfAReplacementSensorHaveNoDelta() {
        // The new sensor starts six minutes after the old one stopped: close enough
        // that, pooled, its first row would pair with the old sensor's last point.
        val old = oneMinuteCadence(count = 10, sensor = "OLD", startMillis = nowMillis - 6 * minute) { 100f }
        val new = oneMinuteCadence(count = 3, sensor = "NEW") { 150f }
        val history = old + new
        val rows = rowsOf(history)

        val texts = index(history).textsFor(rows, false, 5)

        new.forEach { assertNull("replacement sensor: $it", texts[it]) }
        assertEquals("0", texts[old.last()])
        assertNotNull("pooled, this row would read +50", dashboardTexts(rows, history).first())
    }

    @Test
    fun aSensorStoredUnderTwoNamesIsOneSensor() {
        // Older rows under the native name, newer ones under the app id — the identity
        // rule says they match, so the walk-back crosses the rename.
        val history = oneMinuteCadence(count = 12) { 100f }.mapIndexed { i, p ->
            p.copy(sensorSerial = if (i < 6) "native-1" else "app-1")
        }
        val rows = rowsOf(history)
        val sameSensor = { a: String, b: String -> a.removePrefix("native-").removePrefix("app-") == b.removePrefix("native-").removePrefix("app-") }

        val texts = index(history, sameSensor).textsFor(rows, false, 5)

        rows.forEachIndexed { i, row -> assertEquals(dashboardTexts(rows, history)[i], texts[row]) }
        assertEquals(7, texts.size)
        assertTrue("split by name, the rows right after the rename would be blank",
            index(history).textsFor(rows, false, 5).size < 7)
    }

    @Test
    fun importedRowsBelongToEverySensor() {
        // A CSV import filled a hole in the live sensor's data: the live row after the
        // hole pairs with the imported point, as it does on the dashboard.
        val live = oneMinuteCadence(count = 3, sensor = "LIVE") { 120f }
        val imported = listOf(GlucosePoint(value = 100f, time = "", timestamp = nowMillis - 6 * minute, sensorSerial = "imported"))
        val history = imported + live
        val rows = rowsOf(history)

        val texts = index(history, isShared = { it == "imported" }).textsFor(rows, false, 5)

        assertEquals("+16.7", texts[live.last()]) // 20 over six minutes, per five
        assertEquals(dashboardTexts(rows, history)[0], texts[live.last()])
        assertNull("not shared, the import is another sensor", index(history).textsFor(rows, false, 5)[live.last()])
    }

    @Test
    fun blankAndMissingSerialsAreOneSensor() {
        val history = oneMinuteCadence(count = 12) { 100f }.mapIndexed { i, p ->
            if (i % 2 == 0) p.copy(sensorSerial = " ") else p
        }
        val rows = rowsOf(history)

        val texts = index(history).textsFor(rows, false, 5)

        rows.forEachIndexed { i, row -> assertEquals(dashboardTexts(rows, history)[i], texts[row]) }
        assertEquals(7, texts.size)
    }

    @Test
    fun emptyInputsProduceNoTexts() {
        assertTrue(index(oneMinuteCadence(5) { 100f }).textsFor(emptyList(), false, 5).isEmpty())
        assertTrue(index(emptyList()).textsFor(rowsOf(oneMinuteCadence(5) { 100f }), false, 5).isEmpty())
    }
}
