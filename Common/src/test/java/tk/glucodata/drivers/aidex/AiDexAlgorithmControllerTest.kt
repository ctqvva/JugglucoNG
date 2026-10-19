package tk.glucodata.drivers.aidex

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tk.glucodata.drivers.aidex.adaptive2.AiDexAdaptiveV2
import tk.glucodata.drivers.aidex.adaptive2.AiDexReference

class AiDexAlgorithmControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val start = 1_790_000_000_000L
    private fun sample(index: Int, factory: Float = 100f, raw: Float = 100f, session: Long = start) =
        AiDexSourceSample(session + index * 60_000L, session, factory, raw, 34f, AiDexSourceSample.LIVE)
    private fun controller(initial: AiDexAlgorithmSelection = AiDexAlgorithmSelection.STOCK) =
        AiDexAlgorithmController(AiDexSampleJournal(File(temporary.newFolder(), "source.bin")), initial)
    private fun seed(model: AiDexAlgorithmController) { model.record((0..180).map { sample(it) }) }
    private fun replay(model: AiDexAlgorithmController, refs: List<AiDexReference> = emptyList()): List<AiDexAlgorithmReading> {
        val ticket = model.ticket(refs)!!
        val result = model.compute(ticket)
        assertTrue(model.commit(ticket, result) { true })
        return result.readings
    }

    @Test fun `live rate is unknown across a dropout or a model-to-factory switch`() {
        fun reading(index: Int, glucose: Float, estimated: Boolean) = AiDexAlgorithmReading(sample(index), glucose,
            if (estimated) tk.glucodata.drivers.aidex.adaptive2.ProbabilisticGlucoseEstimate(
                glucose / 18f, glucose / 18f - 1f, glucose / 18f + 1f, 0f, 0.1f, 0f, 1f, 0f, 0f, 0f, 0.5f) else null)
        assertEquals(4f, AiDexLiveRate.between(reading(1, 100f, false), reading(2, 104f, false)), 0.0001f)
        assertEquals(2f, AiDexLiveRate.between(reading(1, 100f, false), reading(6, 110f, false)), 0.0001f)
        // 38 minutes describe the dropout, not the current trend.
        assertTrue(AiDexLiveRate.between(reading(2, 104f, false), reading(40, 160f, false)).isNaN())
        // Model and factory differ by calibration; a step between them is not a rate.
        assertTrue(AiDexLiveRate.between(reading(1, 100f, true), reading(2, 130f, false)).isNaN())
        assertTrue(AiDexLiveRate.between(null, reading(2, 130f, false)).isNaN())
        // A Stock trend byte still wins; with nothing usable the rate stays unknown.
        assertEquals(1.5f, AiDexLiveRate.resolve(1.5f, reading(2, 104f, false), reading(40, 160f, false)), 0f)
        assertTrue(AiDexLiveRate.resolve(Float.NaN, reading(2, 104f, false), reading(40, 160f, false)).isNaN())
    }

    @Test fun `stock settles without a rewrite only when stock is requested and applied`() {
        val model = controller()
        seed(model)
        model.invalidateReferences()
        assertTrue(model.settleStock())
        assertFalse(model.needsReplay())
        model.request(AiDexAlgorithmSelection.ADAPTIVE_V2)
        assertFalse(model.settleStock())
        assertTrue(model.needsReplay())
    }

    @Test fun `stock is default and unknown persisted choices fail to stock`() {
        for (value in listOf(-1, 1, 2, 7, 10, 15, 999)) assertEquals(AiDexAlgorithmSelection.STOCK, AiDexAlgorithmSelection.fromStorage(value))
        assertEquals(8, AiDexAlgorithmSelection.ADAPTIVE_V2.storageId)
        assertEquals(9, AiDexAlgorithmSelection.ADAPTIVE_V2_CALIBRATED.storageId)
    }

    @Test fun `switch to adaptive and back restores exact factory and raw history`() {
        val model = controller()
        seed(model)
        val spike = sample(181, factory = 180f, raw = 110f)
        model.record(listOf(spike))
        model.request(AiDexAlgorithmSelection.ADAPTIVE_V2)
        val adaptive = replay(model).last()
        assertTrue(adaptive.glucoseMgDl < 125f)
        assertNotNull(adaptive.uncertainty)
        model.request(AiDexAlgorithmSelection.STOCK)
        val restored = replay(model).last()
        assertEquals(180f, restored.glucoseMgDl, 0f)
        assertEquals(110f, restored.source.rawMgDl, 0f)
        assertNull(restored.uncertainty)
        assertEquals(spike, model.journal.snapshot().last())
    }

    @Test fun `calibration toggle consumes references only in mode nine`() {
        val model = controller()
        seed(model)
        val refs = listOf(AiDexReference(start + 120 * 60_000L, 130f))
        model.request(AiDexAlgorithmSelection.ADAPTIVE_V2)
        val without = replay(model, refs).last().glucoseMgDl
        model.request(AiDexAlgorithmSelection.ADAPTIVE_V2_CALIBRATED)
        val with = replay(model, refs).last().glucoseMgDl
        assertTrue(with > without + 20f)
        model.request(AiDexAlgorithmSelection.ADAPTIVE_V2)
        assertEquals(without, replay(model, refs).last().glucoseMgDl, 0f)
    }

    @Test fun `future references cannot influence earlier history`() {
        val model = controller(AiDexAlgorithmSelection.ADAPTIVE_V2_CALIBRATED)
        seed(model)
        val baseline = model.compute(model.ticket(emptyList())!!)
        val withFuture = model.compute(model.ticket(listOf(AiDexReference(start + 90 * 60_000L, 130f)))!!)
        assertEquals(baseline.readings.take(90), withFuture.readings.take(90))
        assertTrue(withFuture.readings[91].glucoseMgDl > baseline.readings[91].glucoseMgDl + 10f)
    }

    @Test fun `live and replay use the same causal reference release`() {
        val model = controller(AiDexAlgorithmSelection.ADAPTIVE_V2_CALIBRATED)
        seed(model)
        replay(model)
        val reference = AiDexReference(start + 181 * 60_000L + 30_000L, 130f)
        val live = model.live(sample(181), listOf(reference))!!
        val replayed = replay(model, listOf(reference)).last()
        assertEquals(live, replayed)
    }

    @Test fun `rawless or unusable reference minute keeps its reference for the next usable sample`() {
        for (missingRaw in listOf(Float.NaN, 0f, 6500f)) {
            val model = controller(AiDexAlgorithmSelection.ADAPTIVE_V2_CALIBRATED)
            seed(model)
            replay(model)
            val refs = listOf(AiDexReference(start + 181 * 60_000L, 130f))
            assertNull(model.live(sample(181, raw = missingRaw), refs)!!.uncertainty)
            val live = model.live(sample(182), refs)!!
            assertTrue(live.glucoseMgDl > 115f)
            assertEquals("raw=$missingRaw, replay must retain the unaccepted anchor", live, replay(model, refs).last())
            val restarted = AiDexAlgorithmController(model.journal, model.appliedSelection)
            assertEquals(live, replay(restarted, refs).last())
            assertEquals(model.live(sample(183), refs), restarted.live(sample(183), refs))
        }
    }

    @Test fun `unaccepted rawless reference expires after its one minute window`() {
        val model = controller(AiDexAlgorithmSelection.ADAPTIVE_V2_CALIBRATED)
        seed(model)
        replay(model)
        val refs = listOf(AiDexReference(start + 181 * 60_000L, 130f))
        model.live(sample(181, raw = Float.NaN), refs)
        val live = model.live(sample(183), refs)!!
        assertEquals(live, replay(model, refs).last())
        assertEquals(live, model.compute(model.ticket(emptyList())!!).readings.last())
    }

    @Test fun `a revised stock minute publishes the revised factory value`() {
        val model = controller()
        assertEquals(100f, model.live(sample(0), emptyList())!!.glucoseMgDl, 0f)
        assertEquals(120f, model.live(sample(0, factory = 120f), emptyList())!!.glucoseMgDl, 0f)
    }

    @Test fun `a revised adaptive minute waits for replay instead of publishing a stale posterior`() {
        val model = controller(AiDexAlgorithmSelection.ADAPTIVE_V2)
        seed(model)
        replay(model)
        assertNull(model.live(sample(180, raw = 120f), emptyList()))
        assertTrue(model.needsReplay())
        assertTrue(replay(model).last().glucoseMgDl > 115f)
    }

    @Test fun `journal replay after process restart reproduces live continuation`() {
        val model = controller(AiDexAlgorithmSelection.ADAPTIVE_V2_CALIBRATED)
        seed(model)
        val refs = listOf(AiDexReference(start + 120 * 60_000L, 130f))
        replay(model, refs)
        val replacement = AiDexAlgorithmController(model.journal, model.appliedSelection)
        replay(replacement, refs)
        for (index in 181..200) assertEquals(model.live(sample(index, raw = 100f + index % 4), refs),
            replacement.live(sample(index, raw = 100f + index % 4), refs))
    }

    @Test fun `late history causes replay and cannot become current live`() {
        val model = controller(AiDexAlgorithmSelection.ADAPTIVE_V2)
        model.record((0..180).filter { it != 90 }.map { sample(it) })
        replay(model)
        model.record(listOf(sample(90, raw = 110f)))
        assertTrue(model.needsReplay())
        assertNull(model.live(sample(90), emptyList()))
        assertEquals(start + 180 * 60_000L, model.latest!!.source.timestampMs)
    }

    @Test fun `rawless samples use factory without fabricating an interval`() {
        val model = controller(AiDexAlgorithmSelection.ADAPTIVE_V2)
        model.record(listOf(sample(0, raw = Float.NaN)))
        val row = replay(model).single()
        assertEquals(100f, row.glucoseMgDl, 0f)
        assertNull(row.uncertainty)
    }

    @Test fun `new session clears old reference correction`() {
        val model = controller(AiDexAlgorithmSelection.ADAPTIVE_V2_CALIBRATED)
        seed(model)
        model.record(listOf(sample(0, session = start + 300 * 60_000L)))
        val result = replay(model, listOf(AiDexReference(start + 100 * 60_000L, 130f)))
        assertTrue(result[result.lastIndex - 1].glucoseMgDl > 115f)
        assertEquals(100f, result.last().glucoseMgDl, 0.1f)
    }

    @Test fun `selection change invalidates a worker before either store is touched`() {
        val model = controller()
        seed(model)
        model.request(AiDexAlgorithmSelection.ADAPTIVE_V2)
        val old = model.ticket(emptyList())!!
        val result = model.compute(old)
        model.request(AiDexAlgorithmSelection.STOCK)
        var writes = 0
        assertFalse(model.commit(old, result) { writes++; true })
        assertEquals(0, writes)
    }

    @Test fun `new live source invalidates an older worker before storage`() {
        val model = controller()
        seed(model)
        val old = model.ticket(emptyList())!!
        val result = model.compute(old)
        model.live(sample(181), emptyList())
        assertFalse(model.commit(old, result) { fail("stale native or Room write"); true })
    }

    @Test fun `reference edits invalidate a worker before storage`() {
        val model = controller()
        seed(model)
        val old = model.ticket(emptyList())!!
        val result = model.compute(old)
        model.invalidateReferences()
        assertFalse(model.commit(old, result) { fail("stale calibrated history"); true })
    }

    @Test fun `external journal restoration is checked again at commit`() {
        val file = File(temporary.newFolder(), "source.bin")
        val model = AiDexAlgorithmController(AiDexSampleJournal(file), AiDexAlgorithmSelection.STOCK)
        seed(model)
        val old = model.ticket(emptyList())!!
        val result = model.compute(old)
        AiDexSampleJournal(file).appendAll(listOf(sample(181)))
        assertFalse(model.commit(old, result) { fail("journal changed outside controller"); true })
    }

    @Test fun `failed history write does not install a half rebuilt context`() {
        val model = controller()
        seed(model)
        model.request(AiDexAlgorithmSelection.ADAPTIVE_V2)
        val ticket = model.ticket(emptyList())!!
        assertFalse(model.commit(ticket, model.compute(ticket)) { false })
        assertEquals(AiDexAlgorithmSelection.STOCK, model.appliedSelection)
        assertTrue(model.needsReplay())
        assertEquals(100f, model.live(sample(181), emptyList())!!.glucoseMgDl, 0f)
    }

    @Test fun `removal rejects late workers and every subsequent writer`() {
        val model = controller()
        seed(model)
        val ticket = model.ticket(emptyList())!!
        val result = model.compute(ticket)
        model.close()
        assertFalse(model.commit(ticket, result) { fail("write after native shell removal"); true })
        assertFalse(model.record(listOf(sample(181))))
        assertNull(model.live(sample(181), emptyList()))
        assertFalse(model.request(AiDexAlgorithmSelection.ADAPTIVE_V2))
    }

    @Test fun `removal drains both stores and publication before closing`() {
        val model = controller()
        seed(model)
        val ticket = model.ticket(emptyList())!!
        val result = model.compute(ticket)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val commit = executor.submit<Boolean> { model.commit(ticket, result) {
                entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); true
            } }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val close = executor.submit { model.close() }
            assertFalse(close.isDone)
            release.countDown()
            assertTrue(commit.get(3, TimeUnit.SECONDS))
            close.get(3, TimeUnit.SECONDS)
            assertTrue(model.isClosed())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun `calibration anchors preserve both lanes units and explicit duplicate preference`() {
        val raw = doubleArrayOf(2.0, 5.0, start.toDouble())
        val auto = doubleArrayOf(7.0, 6.0, start.toDouble())
        val refs = AiDexAlgorithmReferences.decode(raw, auto, true)
        assertEquals(1, refs.size)
        assertEquals(6f * AiDexAdaptiveV2.MGDL_PER_MMOL, refs.single().glucoseMgDl, 0.001f)
        assertEquals(120f, AiDexAlgorithmReferences.decode(doubleArrayOf(), doubleArrayOf(90.0, 120.0, start.toDouble()), false).single().glucoseMgDl, 0f)
        assertEquals(2, AiDexAlgorithmReferences.decode(raw, doubleArrayOf(7.0, 6.0, (start + 60_000).toDouble()), true).size)
        assertTrue(AiDexAlgorithmReferences.decode(doubleArrayOf(1.0), doubleArrayOf(1.0, Double.NaN, 0.0), true).isEmpty())
    }
}
