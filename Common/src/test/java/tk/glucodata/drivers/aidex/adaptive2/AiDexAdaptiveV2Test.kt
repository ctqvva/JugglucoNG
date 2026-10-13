package tk.glucodata.drivers.aidex.adaptive2

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class AiDexAdaptiveV2Test {
    private val start = 1_790_000_000_000L
    private fun sample(index: Int, raw: Float = 100f, factory: Float = 100f,
        session: Long = start, temperature: Float = 34f) =
        AiDexAdaptiveSample(session + index * 60_000L, session, raw, factory, temperature)

    private fun settled(): AiDexAdaptiveV2 = AiDexAdaptiveV2().apply {
        repeat(180) { check(process(sample(it)) != null) }
    }

    @Test fun `normalization keeps explicit transport units`() {
        val result = AiDexAdaptiveV2().process(sample(0, 50f, 150f))!!
        assertEquals(3f, result.rawToGlucoseScale, 0.00001f)
        assertEquals(150f / AiDexAdaptiveV2.MGDL_PER_MMOL, result.estimate.glucoseMmol, 0.001f)
    }

    @Test fun `factory overshoot does not become a glucose excursion`() {
        val a = settled()
        val b = settled()
        for (i in 180..195) {
            val baseline = a.process(sample(i))!!
            val overshoot = b.process(sample(i, factory = if (i < 185) 200f else 100f))!!
            assertEquals(baseline.estimate.glucoseMmol, overshoot.estimate.glucoseMmol, 0.0001f)
        }
    }

    @Test fun `actual raw rise follows in the same minute`() {
        val result = settled().process(sample(180, raw = 127f, factory = 190f))!!
        assertTrue(result.estimate.glucoseMmol > 6.8f)
        assertTrue(result.estimate.upper90Mmol - result.estimate.lower90Mmol > 1f)
    }

    @Test fun `real low is reachable without a clinical low floor`() {
        val model = settled()
        var result = model.process(sample(180, raw = 50f))!!
        repeat(8) { result = model.process(sample(181 + it, raw = 50f))!! }
        assertTrue(result.estimate.glucoseMmol < 3f)
        assertTrue(result.estimate.isUsable)
    }

    @Test fun `reference only waits instead of treating raw as factory glucose`() {
        val model = AiDexAdaptiveV2(AiDexNormalization.REFERENCE_ONLY)
        assertNull(model.process(sample(0, raw = 40f, factory = 400f)))
        val result = model.process(sample(1, raw = 40f, factory = 400f), listOf(AiDexReference(start + 60_000, 120f)))!!
        assertEquals(3f, result.rawToGlucoseScale, 0.001f)
        assertEquals(120f / AiDexAdaptiveV2.MGDL_PER_MMOL, result.estimate.glucoseMmol, 0.02f)
    }

    @Test fun `reference only outputs are independent of factory readings`() {
        val a = AiDexAdaptiveV2(AiDexNormalization.REFERENCE_ONLY)
        val b = AiDexAdaptiveV2(AiDexNormalization.REFERENCE_ONLY)
        repeat(120) { i ->
            val refs = if (i == 0) listOf(AiDexReference(start, 120f)) else emptyList()
            val x = a.process(sample(i, raw = 60f + i % 10, factory = 80f), refs)!!
            val y = b.process(sample(i, raw = 60f + i % 10, factory = if (i % 2 == 0) 400f else Float.NaN), refs)!!
            assertEquals(x.estimate, y.estimate)
        }
    }

    @Test fun `future or stale references cannot bootstrap the scale`() {
        for (time in listOf(start - 120_000L, start + 120_000L)) {
            assertNull(AiDexAdaptiveV2(AiDexNormalization.REFERENCE_ONLY)
                .process(sample(0), listOf(AiDexReference(time, 150f))))
        }
    }

    @Test fun `first minute reference is assimilated`() {
        val noReference = AiDexAdaptiveV2().process(sample(0))!!
        val anchored = AiDexAdaptiveV2().process(sample(0), listOf(AiDexReference(start, 130f)))!!
        assertTrue(anchored.estimate.glucoseMmol > noReference.estimate.glucoseMmol)
    }

    @Test fun `stale duplicate and old session do not mutate the checkpoint`() {
        val model = settled()
        val before = model.snapshot()
        assertNull(model.process(sample(179, raw = 200f)))
        assertNull(model.process(sample(1, session = start - 1_000_000L)))
        assertArrayEquals(before, model.snapshot())
    }

    @Test fun `explicit reset starts fresh and rejects previous session packets`() {
        val model = settled()
        val reset = start + 200 * 60_000L
        val fresh = model.process(sample(0, raw = 50f, factory = 100f, session = reset))!!
        assertEquals(2f, fresh.rawToGlucoseScale, 0.0001f)
        val before = model.snapshot()
        assertNull(model.process(sample(201)))
        assertArrayEquals(before, model.snapshot())
    }

    @Test fun `reference only reset requires a new session reference`() {
        val model = AiDexAdaptiveV2(AiDexNormalization.REFERENCE_ONLY)
        assertNotNull(model.process(sample(0), listOf(AiDexReference(start, 100f))))
        assertNull(model.process(sample(0, session = start + 200 * 60_000L)))
    }

    @Test fun `missing raw is unavailable rather than fabricated from factory`() {
        val model = settled()
        val checkpoint = model.snapshot()
        for (raw in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f, 10_000f))
            assertNull(model.process(sample(180, raw = raw)))
        assertArrayEquals(checkpoint, model.snapshot())
    }

    @Test fun `invalid factory cannot bootstrap factory assisted mode`() {
        assertNull(AiDexAdaptiveV2().process(sample(0, factory = Float.NaN)))
        assertNull(AiDexAdaptiveV2().process(sample(0, factory = 1023f)))
    }

    @Test fun `missing factory after bootstrap still observes raw`() {
        assertNotNull(settled().process(sample(180, raw = 95f, factory = Float.NaN)))
    }

    @Test fun `invalid factory telemetry cannot poison a valid checkpoint`() {
        val model = settled()
        assertNotNull(model.process(sample(180, factory = Float.POSITIVE_INFINITY)))
        val restored = AiDexAdaptiveV2()
        assertTrue(restored.restore(model.snapshot()))
        assertEquals(model.process(sample(181)), restored.process(sample(181)))
    }

    @Test fun `temperature changes uncertainty without manufacturing a correction`() {
        val a = settled().process(sample(180))!!
        val b = settled().process(sample(180, temperature = 20f))!!
        assertTrue(abs(a.estimate.glucoseMmol - b.estimate.glucoseMmol) < 0.1f)
        assertTrue(b.estimate.upper90Mmol - b.estimate.lower90Mmol >= a.estimate.upper90Mmol - a.estimate.lower90Mmol)
    }

    @Test fun `a sharp reversal is recovered rather than extrapolated`() {
        val model = settled()
        model.process(sample(180, raw = 140f))!!
        val recovery = model.process(sample(181))!!
        assertTrue(abs(recovery.estimate.glucoseMmol - 100f / AiDexAdaptiveV2.MGDL_PER_MMOL) < 0.5f)
        assertTrue(recovery.estimate.artifactProbability > 0.05f)
    }

    @Test fun `no Sibionics lag lead is applied on ramps`() {
        val model = settled()
        var result: AiDexAdaptiveResult? = null
        repeat(25) { result = model.process(sample(180 + it, raw = 100f + it * 2f)) }
        val last = result!!
        assertTrue(abs(last.estimate.glucoseMmol - last.normalizedRawMmol) < 0.35f)
    }

    @Test fun `snapshot restores exact continuation including scale and probabilities`() {
        val a = settled()
        a.process(sample(180, raw = 120f), listOf(AiDexReference(start + 180 * 60_000L, 130f)))
        val b = AiDexAdaptiveV2()
        assertTrue(b.restore(a.snapshot()))
        assertEquals(a.probabilityBelow(7f), b.probabilityBelow(7f), 0f)
        for (i in 181..220) {
            val s = sample(i, raw = 100f + i % 7)
            assertEquals(a.process(s), b.process(s))
        }
    }

    @Test fun `empty waiting reference only state round trips`() {
        val a = AiDexAdaptiveV2(AiDexNormalization.REFERENCE_ONLY)
        assertNull(a.process(sample(0)))
        val b = AiDexAdaptiveV2(AiDexNormalization.REFERENCE_ONLY)
        assertTrue(b.restore(a.snapshot()))
        val refs = listOf(AiDexReference(start + 60_000L, 110f))
        assertEquals(a.process(sample(1), refs), b.process(sample(1), refs))
    }

    @Test fun `corrupt truncated and different mode checkpoints fail cleanly`() {
        val checkpoint = settled().snapshot()
        val corrupt = checkpoint.copyOf().apply { this[size / 2] = (this[size / 2].toInt() xor 1).toByte() }
        for (bytes in listOf(corrupt, checkpoint.copyOf(checkpoint.size - 1), byteArrayOf())) {
            val model = settled()
            assertFalse(model.restore(bytes))
            assertEquals(AiDexAdaptiveV2().process(sample(0)), model.process(sample(0)))
        }
        assertFalse(AiDexAdaptiveV2(AiDexNormalization.REFERENCE_ONLY).restore(checkpoint))
    }

    @Test fun `gap carries additional uncertainty`() {
        val normal = settled().process(sample(180))!!
        val gap = settled().process(sample(210))!!
        assertTrue(gap.estimate.upper90Mmol - gap.estimate.lower90Mmol > normal.estimate.upper90Mmol - normal.estimate.lower90Mmol)
    }

    @Test fun `unusable normalized signal does not change continuation`() {
        val model = settled()
        val checkpoint = model.snapshot()
        assertNull(model.process(sample(180, raw = 1000f)))
        assertArrayEquals(checkpoint, model.snapshot())
    }

    @Test fun `long transport gap retains reference correction in the same session`() {
        val model = settled()
        val referenceTime = start + 180 * 60_000L
        model.process(sample(180), listOf(AiDexReference(referenceTime, 130f)))!!
        var before = model.process(sample(181))!!
        for (i in 182..190) before = model.process(sample(i))!!
        val after = model.process(sample(400))!!
        assertTrue(after.estimate.glucoseMmol > 6.7f)
        assertTrue(abs(after.estimate.glucoseMmol - before.estimate.glucoseMmol) < 0.3f)
    }

    @Test fun `scale adapts to sustained drift without learning a single factory spike`() {
        val model = settled()
        val spike = model.process(sample(180, factory = 200f))!!
        assertEquals(1f, spike.rawToGlucoseScale, 0.00001f)
        var last = spike
        for (i in 181..1600) last = model.process(sample(i, factory = 120f))!!
        assertTrue(last.rawToGlucoseScale > 1.17f)
        assertTrue(last.rawToGlucoseScale < 1.21f)
    }
}
