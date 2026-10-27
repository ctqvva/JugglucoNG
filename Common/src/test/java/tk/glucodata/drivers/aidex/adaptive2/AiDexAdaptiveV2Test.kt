package tk.glucodata.drivers.aidex.adaptive2

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class AiDexAdaptiveV2Test {
    private val start = 1_790_000_000_000L
    private fun sample(index: Int, raw: Float = 100f, factory: Float = 100f,
        session: Long = start, temperature: Float = 34f) =
        AiDexAdaptiveSample(session + index * 60_000L, session, raw, factory, temperature)

    /** Bootstraps from a run of stable pairs, then must stay available. */
    private fun settled(): AiDexAdaptiveV2 = AiDexAdaptiveV2().apply {
        var available = false
        repeat(180) {
            val produced = process(sample(it)) != null
            check(produced || !available)
            available = available || produced
        }
        check(available)
    }

    @Test fun `normalization keeps explicit transport units`() {
        val model = AiDexAdaptiveV2()
        var result: AiDexAdaptiveResult? = null
        repeat(40) { result = model.process(sample(it, 50f, 150f)) ?: result }
        assertEquals(3f, result!!.rawToGlucoseScale, 0.00001f)
        assertEquals(150f / AiDexAdaptiveV2.MGDL_PER_MMOL, result!!.estimate.glucoseMmol, 0.01f)
    }

    @Test fun `a single warm-up pair cannot set the scale`() {
        val model = AiDexAdaptiveV2()
        assertNull(model.process(sample(0, raw = 200f, factory = 270f)))
        var result: AiDexAdaptiveResult? = null
        for (i in 1..40) result = model.process(sample(i)) ?: result
        assertEquals(1f, result!!.rawToGlucoseScale, 0.01f)
        assertEquals(100f / AiDexAdaptiveV2.MGDL_PER_MMOL, result!!.estimate.glucoseMmol, 0.1f)
    }

    @Test fun `a reference is not applied again once the factory curve has caught up`() {
        // Factory moves to 1.2x raw and a reference confirms the factory value
        // while the slow scale is still catching up. The scale's lag is not a
        // sensor error: once it converges the output must sit on the reference.
        val model = settled()
        val referenceMinute = 260
        var last: AiDexAdaptiveResult? = null
        for (i in 180..2400) {
            val refs = if (i == referenceMinute) listOf(AiDexReference(start + i * 60_000L, 120f)) else emptyList()
            last = model.process(sample(i, factory = 120f), refs) ?: last
        }
        assertEquals(120f / AiDexAdaptiveV2.MGDL_PER_MMOL, last!!.estimate.glucoseMmol, 0.3f)
    }

    @Test fun `references move the visible curve while its factory mapping is catching up`() {
        val calibrated = AiDexAdaptiveV2()
        val plain = AiDexAdaptiveV2()
        val jump = 24 * 60
        val observed = sortedMapOf<Int, Pair<Float, Float>>()
        for (i in 0..jump + 570) {
            val factory = if (i < jump) 105f else 160f
            val meter = when (i - jump) { 120 -> 167f; 260 -> 174f; else -> null }
            val input = sample(i, raw = 105f, factory = factory)
            val refs = meter?.let { listOf(AiDexReference(input.timestampMs, it)) } ?: emptyList()
            val a = calibrated.process(input, refs)
            val b = plain.process(input)
            if (i - jump in setOf(120, 240, 260, 570)) {
                observed[i - jump] = a!!.estimate.glucoseMmol * AiDexAdaptiveV2.MGDL_PER_MMOL to
                    b!!.estimate.glucoseMmol * AiDexAdaptiveV2.MGDL_PER_MMOL
            }
        }
        assertTrue("First reference must correct the visible lag: $observed", observed.getValue(120).first >= 150f)
        assertTrue("Second reference must still affect the curve: $observed", observed.getValue(260).first >= 160f)
        observed.forEach { (_, values) ->
            assertTrue("Correction stays bounded: $observed", abs(values.first - values.second) <= 2.5f * AiDexAdaptiveV2.MGDL_PER_MMOL + 0.1f)
        }
        assertTrue("Mapping catch-up must not add the initial correction twice: $observed", observed.getValue(570).first < 180f)
        println("Issue 681 factory-lag replay (calibrated, uncalibrated mg/dL): $observed")
    }

    @Test fun `a downward reference also corrects a lagging factory mapping immediately`() {
        val calibrated = AiDexAdaptiveV2()
        val plain = AiDexAdaptiveV2()
        var anchored: AiDexAdaptiveResult? = null
        var baseline: AiDexAdaptiveResult? = null
        val referenceMinute = 24 * 60 + 120
        for (i in 0..referenceMinute) {
            val input = sample(i, raw = 105f, factory = if (i < 24 * 60) 105f else 70f)
            baseline = plain.process(input)
            anchored = calibrated.process(input,
                if (i == referenceMinute) listOf(AiDexReference(input.timestampMs, 75f)) else emptyList())
        }
        val before = baseline!!.estimate.glucoseMmol * AiDexAdaptiveV2.MGDL_PER_MMOL
        val after = anchored!!.estimate.glucoseMmol * AiDexAdaptiveV2.MGDL_PER_MMOL
        assertEquals(before + 0.8f * (75f - before), after, 0.5f)
    }

    @Test fun `a meter agreeing with the filtered curve does not correct toward the raw sample`() {
        val calibrated = settled()
        val plain = settled()
        val input = sample(180, raw = 140f, factory = 190f)
        val baseline = plain.process(input)!!
        val anchored = calibrated.process(input, listOf(AiDexReference(input.timestampMs,
            baseline.estimate.glucoseMmol * AiDexAdaptiveV2.MGDL_PER_MMOL)))!!
        assertEquals(baseline.estimate.glucoseMmol, anchored.estimate.glucoseMmol, 0.03f)
    }

    @Test fun `drifting mapping and multiple references retain exact snapshot continuation`() {
        val a = AiDexAdaptiveV2()
        fun input(i: Int) = sample(i, raw = 105f + 15f * kotlin.math.sin(i / 60.0).toFloat(),
            factory = if (i < 180) 105f else 150f)
        for (i in 0..600) a.process(input(i), when (i) {
            300 -> listOf(AiDexReference(input(i).timestampMs, 155f))
            500 -> listOf(AiDexReference(input(i).timestampMs, 145f))
            else -> emptyList()
        })
        val b = AiDexAdaptiveV2()
        assertTrue(b.restore(a.snapshot()))
        for (i in 601..1600) {
            val refs = if (i == 900) listOf(AiDexReference(input(i).timestampMs, 160f)) else emptyList()
            assertEquals(a.process(input(i), refs), b.process(input(i), refs))
        }
    }

    @Test fun `version three checkpoints are rejected cleanly for journal replay`() {
        val old = settled().snapshot()
        java.nio.ByteBuffer.wrap(old).putInt(4, 3)
        val crc = java.util.zip.CRC32().apply { update(old, 0, old.size - 8) }.value
        java.nio.ByteBuffer.wrap(old).putLong(old.size - 8, crc)
        val model = settled()
        assertFalse(model.restore(old))
        val fresh = AiDexAdaptiveV2()
        for (i in 0..40) assertEquals(fresh.process(sample(i)), model.process(sample(i)))
    }

    @Test fun `an aged sensor offset is learned and keeps lows from being exaggerated`() {
        // factory = 0.8 * raw + 40 mg/dL. A pure ratio would put raw 60 near 60*1.0.
        val model = AiDexAdaptiveV2()
        fun raw(i: Int) = 130f + 70f * kotlin.math.sin(i * 2.0 * Math.PI / 360.0).toFloat()
        for (i in 0 until 4 * 1440) model.process(sample(i, raw = raw(i), factory = 0.8f * raw(i) + 40f))
        var result: AiDexAdaptiveResult? = null
        val low = 4 * 1440
        repeat(10) { result = model.process(sample(low + it, raw = 60f, factory = Float.NaN)) ?: result }
        assertEquals((0.8f * 60f + 40f) / AiDexAdaptiveV2.MGDL_PER_MMOL, result!!.normalizedRawMmol, 0.4f)
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

    private fun width(result: AiDexAdaptiveResult) = result.estimate.upper90Mmol - result.estimate.lower90Mmol

    @Test fun `a settled calibration gives a narrow band`() {
        val model = settled()
        var result: AiDexAdaptiveResult? = null
        for (i in 180..600) result = model.process(sample(i)) ?: result
        // 5.55 mmol/L with a settled factory curve: well under a third of the value.
        assertTrue(width(result!!) < 1.2f)
    }

    @Test fun `a brief factory swing does not balloon the band but a persistent gap widens it`() {
        val steady = settled().also { m -> for (i in 180..600) m.process(sample(i)) }
        val baseline = width(steady.process(sample(601))!!)
        val brief = settled().also { m ->
            for (i in 180..600) m.process(sample(i, factory = if (i in 590..594) 130f else 100f))
        }
        assertTrue(width(brief.process(sample(601))!!) < baseline * 1.3f)
        val persistent = settled().also { m ->
            for (i in 180..600) m.process(sample(i, factory = if (i >= 510) 80f else 100f))
        }
        assertTrue(width(persistent.process(sample(601, factory = 80f))!!) > baseline * 1.3f)
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

    @Test fun `a reference during bootstrap is assimilated`() {
        val noReference = AiDexAdaptiveV2()
        val anchored = AiDexAdaptiveV2()
        assertNull(anchored.process(sample(0), listOf(AiDexReference(start, 130f))))
        noReference.process(sample(0))
        var a: AiDexAdaptiveResult? = null
        var b: AiDexAdaptiveResult? = null
        for (i in 1..40) { a = anchored.process(sample(i)) ?: a; b = noReference.process(sample(i)) ?: b }
        assertTrue(a!!.estimate.glucoseMmol > b!!.estimate.glucoseMmol + 1f)
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
        var fresh: AiDexAdaptiveResult? = null
        for (i in 0..40) fresh = model.process(sample(i, raw = 50f, factory = 100f, session = reset)) ?: fresh
        assertEquals(2f, fresh!!.rawToGlucoseScale, 0.0001f)
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
