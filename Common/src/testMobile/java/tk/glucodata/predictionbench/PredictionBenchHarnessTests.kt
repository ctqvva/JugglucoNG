package tk.glucodata.predictionbench

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType

/**
 * The benchmark is only worth its numbers if it measures the shipped forecast and cannot see
 * the future. These hold both, on synthetic data, so they run everywhere.
 */
class PredictionBenchHarnessTests {

    private val start = 1_790_000_000_000L

    private fun syntheticLane(days: Double, isMmol: Boolean, seed: Int = 3, cadenceMinutes: Int = 1): Lane {
        val rng = Random(seed)
        val n = (days * 24 * 60 / cadenceMinutes).toInt()
        val times = LongArray(n) { start + it * cadenceMinutes * 60_000L + 15_000L }
        val mgdl = FloatArray(n) {
            val m = it * cadenceMinutes.toDouble()
            (115 + 45 * sin(2 * PI * m / 190) + 18 * sin(2 * PI * m / 47) + rng.nextDouble(-4.0, 4.0)).toFloat()
        }
        val display = FloatArray(n) { if (isMmol) mgdl[it] / MGDL_PER_MMOL else mgdl[it] }
        return Lane("SYN", "auto", times, display, isMmol)
    }

    private fun view(lane: Lane, end: Int, journal: List<JournalEntry> = emptyList()) =
        OriginView(lane, end, journal, emptyMap(), if (lane.isMmol) "mmol/L" else "mg/dL")

    private val presets = mapOf(1L to BenchLoaders.genericRapidPreset())

    /** A view with a journal, filtered the way the runner filters it. */
    private fun journalView(lane: Lane, end: Int, entries: List<JournalEntry>, index: LaneJournal, mode: JournalMode) =
        OriginView(lane, end, OriginView.journalFor(entries, mode, lane.times[end], 120 * 60_000L), presets,
            if (lane.isMmol) "mmol/L" else "mg/dL", profile = null, history = index, mode = mode)

    private fun treatment(id: Long, type: JournalEntryType, timestamp: Long, createdAt: Long, amount: Float) = JournalEntry(
        id = id, timestamp = timestamp, sensorSerial = null, type = type, title = "", note = null,
        amount = amount, glucoseValueMgDl = null, durationMinutes = null, intensity = null,
        insulinPresetId = if (type == JournalEntryType.INSULIN) 1L else null, foodId = null, proteinGrams = null,
        fatGrams = null, source = JournalEntrySource.MANUAL, sourceRecordId = null, createdAt = createdAt, updatedAt = createdAt,
    )

    private val horizons = intArrayOf(15, 30, 45, 60, 90, 120)

    @Test
    fun kernelVariantAtDefaultsIsTheProductionForecast() {
        for (isMmol in listOf(true, false)) {
            for (lane in listOf(syntheticLane(1.0, isMmol), syntheticLane(1.0, isMmol).decimated(5))) {
                for (smoothing in listOf(0, 5)) {
                    val production = ProductionKernel("p", smoothingMinutes = smoothing)
                    val variant = KernelVariant("v", smoothingMinutes = smoothing)
                    var checked = 0
                    for (end in 70 until lane.size step 7) {
                        val v = view(lane, end)
                        val a = production.forecast(v, horizons)
                        val b = variant.forecast(view(lane, end), horizons)
                        for (k in horizons.indices) {
                            assertTrue("NaN at ${lane.id} end=$end", a[k].isFinite())
                            assertEquals("${lane.id} mmol=$isMmol smoothing=$smoothing end=$end h=${horizons[k]}", a[k], b[k], 1e-3f)
                        }
                        checked++
                    }
                    assertTrue(checked > 20)
                }
            }
        }
    }

    @Test
    fun noForecasterCanSeeTheFuture() {
        val lane = syntheticLane(3.6, isMmol = true)
        val cut = (3.3 * 24 * 60).toInt()
        val rng = Random(11)
        val rewritten = Lane(lane.sensor, lane.laneName, lane.times,
            FloatArray(lane.size) { if (it <= cut) lane.display[it] else lane.display[it] * 0.6f + 3f + rng.nextFloat() }, lane.isMmol)

        // A meal and a bolus every six hours, logged on time; the altered world adds what nobody
        // could know at the cut: entries logged after it, for moments before and after it.
        val cutTime = lane.times[cut]
        val onTime = (0 until 14).flatMap { k ->
            val t = lane.times[0] + k * 6 * 60 * 60_000L
            listOf(treatment(2L * k, JournalEntryType.CARBS, t, t, 45f), treatment(2L * k + 1, JournalEntryType.INSULIN, t, t, 4f))
        }
        val late = listOf(
            treatment(100, JournalEntryType.CARBS, cutTime - 20 * 60_000L, cutTime + 60_000L, 60f),
            treatment(101, JournalEntryType.INSULIN, cutTime - 90 * 60_000L, cutTime + 5 * 60_000L, 6f),
            treatment(102, JournalEntryType.CARBS, cutTime + 30 * 60_000L, cutTime + 30 * 60_000L, 30f),
        )
        val knownEntries = onTime.sortedBy { it.timestamp }
        val alteredEntries = (onTime + late).sortedBy { it.timestamp }
        val knownIndex = LaneJournal(knownEntries, presets)
        val alteredIndex = LaneJournal(alteredEntries, presets)

        val original = Rosters.standard()
        val altered = Rosters.standard()
        var compared = 0
        var journalMattered = false
        for (end in 60..cut step 5) {
            for (f in original.indices) {
                val a = original[f].forecast(journalView(lane, end, knownEntries, knownIndex, JournalMode.OPERATIONAL), horizons)
                val b = altered[f].forecast(journalView(rewritten, end, alteredEntries, alteredIndex, JournalMode.OPERATIONAL), horizons)
                assertArrayEquals("${original[f].name} at end=$end read the future", a, b, 0f)
            }
            if (!journalMattered) {
                val withJournal = ProductionKernel("p").forecast(journalView(lane, end, knownEntries, knownIndex, JournalMode.OPERATIONAL), horizons)
                journalMattered = !withJournal.contentEquals(ProductionKernel("p").forecast(view(lane, end), horizons))
            }
            compared++
        }
        assertTrue(compared > 700)
        assertTrue("the journal never changed a forecast, so this proves nothing about it", journalMattered)
        // The learners must have been trained by the end, or this proves nothing about them.
        val learner = original.first { it is RidgeDirect }
        val last = journalView(lane, cut, knownEntries, knownIndex, JournalMode.OPERATIONAL)
        assertFalse(learner.forecast(last, horizons).all { it == last.mgdl(last.end) })
    }

    @Test
    fun aViewRefusesIndicesAndTimesPastItsEnd() {
        val lane = syntheticLane(0.2, isMmol = false)
        val v = view(lane, 100)
        assertTrue(v.mgdlAt(v.originTime + 60_000L, 10 * 60_000L).isNaN())
        assertTrue(runCatching { v.display(101) }.isFailure)
        assertEquals(lane.mgdl(100), v.mgdlAt(v.originTime, 60_000L))
    }

    @Test
    fun operationalJournalHoldsOnlyWhatExistedThen() {
        val t0 = start + 10 * 60 * 60_000L
        fun entry(id: Long, timestamp: Long, createdAt: Long, updatedAt: Long = createdAt) = JournalEntry(
            id = id, timestamp = timestamp, sensorSerial = null, type = JournalEntryType.CARBS, title = "", note = null,
            amount = 30f, glucoseValueMgDl = null, durationMinutes = null, intensity = null, insulinPresetId = null,
            foodId = null, proteinGrams = null, fatGrams = null, source = JournalEntrySource.MANUAL, sourceRecordId = null,
            createdAt = createdAt, updatedAt = updatedAt,
        )
        val loggedOnTime = entry(1, t0 - 30 * 60_000L, t0 - 29 * 60_000L)
        val loggedLate = entry(2, t0 - 10 * 60_000L, t0 + 20 * 60_000L)
        val plannedAhead = entry(3, t0 + 30 * 60_000L, t0 - 5 * 60_000L)
        val editedLater = entry(4, t0 - 60 * 60_000L, t0 - 60 * 60_000L, updatedAt = t0 + 60_000L)
        val all = listOf(loggedOnTime, loggedLate, plannedAhead, editedLater)
        val horizon = 120 * 60_000L

        assertEquals(listOf(1L, 3L), OriginView.journalFor(all, JournalMode.OPERATIONAL, t0, horizon).map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L), OriginView.journalFor(all, JournalMode.ORACLE, t0, horizon).map { it.id })
        assertTrue(OriginView.journalFor(all, JournalMode.NONE, t0, horizon).isEmpty())
    }

    @Test
    fun truthInterpolatesOnlyAcrossShortGaps() {
        val lane = Lane("S", "auto", longArrayOf(0L, 60_000L, 600_000L), floatArrayOf(100f, 110f, 200f), isMmol = false)
        assertEquals(105f, Benchmark.truthAt(lane, 30_000L, 6 * 60_000L), 1e-4f)
        assertTrue(Benchmark.truthAt(lane, 300_000L, 6 * 60_000L).isNaN())
        assertEquals(200f, Benchmark.truthAt(lane, 600_000L, 6 * 60_000L), 0f)
    }

    @Test
    fun aPooledLaneScoresOnlyAgainstTheOriginsOwnSensor() {
        // Sensor 0 ends at 2 min, sensor 1 starts at 4 min: an outcome at 3 min belongs to neither.
        val lane = Lane("P", "person", longArrayOf(0L, 60_000L, 120_000L, 240_000L, 300_000L),
            floatArrayOf(100f, 100f, 100f, 200f, 200f), isMmol = false, segments = intArrayOf(0, 0, 0, 1, 1))
        assertTrue(Benchmark.truthAt(lane, 180_000L, 6 * 60_000L, segment = 0).isNaN())
        assertTrue(Benchmark.truthAt(lane, 240_000L, 6 * 60_000L, segment = 0).isNaN())
        assertEquals(200f, Benchmark.truthAt(lane, 240_000L, 6 * 60_000L, segment = 1), 0f)
    }

    @Test
    fun decimationKeepsTheCadenceAsked() {
        val lane = syntheticLane(0.5, isMmol = false).decimated(5)
        val gaps = (1 until lane.size).map { (lane.times[it] - lane.times[it - 1]) / 60_000.0 }
        assertTrue(gaps.all { abs(it - 5.0) < 1e-9 })
    }
}
