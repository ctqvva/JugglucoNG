package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adaptive V1 plausibility gating.
 *
 * V1 stays the conservative, stock-aware model: it may leave the vendor
 * trajectory only as far as the chemical signal supports. These tests pin the
 * distinction that motivated the gating — an unsupported excursion must not
 * become a confident low, while a coherent sustained fall must still reach
 * genuinely low values.
 */
class SibionicsAdaptivePlausibilityTest {
    private fun settled(): SibionicsAdaptiveAlgorithmContext =
        SibionicsAdaptiveAlgorithmContext().apply {
            configure(1.4f)
            repeat(40) { offset ->
                val index = WARMUP_INDEX + offset
                process(
                    6f, 6f, 34f, 2_900f, index, index * 60_000L, emptyList(),
                    chemicalMmol = 6f,
                    vendorStockMmol = 6f,
                )
            }
        }

    @Test
    fun unsupportedChemicalDiveDoesNotBecomeAConfidentLow() {
        val context = settled()
        val outputs = listOf(5.0f, 3.4f, 3.0f).mapIndexed { offset, chemical ->
            val index = DIVE_INDEX + offset
            context.process(
                6f, 6f, 34f, 2_900f, index, index * 60_000L, emptyList(),
                chemicalMmol = chemical,
                vendorStockMmol = 6f,
            )
        }

        // The vendor reference never moved; three samples of a physiologically
        // impossible drop are not enough evidence to abandon it.
        assertTrue("outputs=$outputs", outputs.all { it > 5f })
    }

    @Test
    fun unsupportedDiveRecoversWithoutAReboundOvershoot() {
        val context = settled()
        listOf(5.0f, 3.4f, 3.0f).forEachIndexed { offset, chemical ->
            val index = DIVE_INDEX + offset
            context.process(
                6f, 6f, 34f, 2_900f, index, index * 60_000L, emptyList(),
                chemicalMmol = chemical,
                vendorStockMmol = 6f,
            )
        }
        val recovery = (0 until 6).map { offset ->
            val index = DIVE_INDEX + 3 + offset
            context.process(
                6f, 6f, 34f, 2_900f, index, index * 60_000L, emptyList(),
                chemicalMmol = 6f,
                vendorStockMmol = 6f,
            )
        }

        assertTrue("recovery=$recovery", recovery.all { it in 5f..6.3f })
        assertEquals("recovery=$recovery", 6f, recovery.last(), 0.35f)
    }

    @Test
    fun coherentSustainedFallStillReachesBelowThreeMmol() {
        val context = settled()
        var chemical = 6f
        var stock = 6f
        var output = Float.NaN
        repeat(30) { offset ->
            chemical -= 0.13f
            stock -= 0.11f
            val index = DIVE_INDEX + offset
            output = context.process(
                stock, stock, 34f, 2_900f, index, index * 60_000L, emptyList(),
                chemicalMmol = chemical,
                vendorStockMmol = stock,
            )
        }

        // No absolute floor: the allowance is relative to the reference, and the
        // reference itself is low here.
        assertTrue("output=$output", output < 3f)
    }

    @Test
    fun coherentFallIsNotHeldBackBehindTheVendorTrajectory() {
        val context = settled()
        var chemical = 6f
        var stock = 6f
        var leadCount = 0
        repeat(20) { offset ->
            chemical -= 0.13f
            stock -= 0.11f
            val index = DIVE_INDEX + offset
            val output = context.process(
                stock, stock, 34f, 2_900f, index, index * 60_000L, emptyList(),
                chemicalMmol = chemical,
                vendorStockMmol = stock,
            )
            if (output < stock - 0.05f) leadCount++
        }

        assertTrue("leadCount=$leadCount", leadCount >= 15)
    }

    @Test
    fun leadCompensationFadesOutInTheLowRange() {
        fun finalLead(startMmol: Float): Float {
            val context = SibionicsAdaptiveAlgorithmContext().apply { configure(1.4f) }
            var chemical = startMmol
            var stock = startMmol
            repeat(40) { offset ->
                val index = WARMUP_INDEX + offset
                context.process(
                    stock, stock, 34f, 2_900f, index, index * 60_000L, emptyList(),
                    chemicalMmol = chemical,
                    vendorStockMmol = stock,
                )
            }
            var output = Float.NaN
            repeat(8) { offset ->
                chemical -= 0.14f
                val index = DIVE_INDEX + offset
                output = context.process(
                    stock, stock, 34f, 2_900f, index, index * 60_000L, emptyList(),
                    chemicalMmol = chemical,
                    vendorStockMmol = stock,
                )
            }
            return chemical - output
        }

        // Falling identically fast, but only the high-range run is allowed to
        // extrapolate ahead of what has actually been observed.
        val highLead = finalLead(10f)
        val lowLead = finalLead(4.2f)
        assertTrue("highLead=$highLead lowLead=$lowLead", highLead > lowLead)
    }

    @Test
    fun poorSignalQualityFadesTheEstimateBackTowardStock() {
        val context = settled()
        var output = Float.NaN
        repeat(15) { offset ->
            val index = DIVE_INDEX + offset
            // Alternating temperature steps: never severe enough for the hard
            // fallback, but enough that the front end is no longer trustworthy.
            val temperature = if (offset % 2 == 0) 34f else 26f
            output = context.process(
                6f, 6f, temperature, 2_900f, index, index * 60_000L, emptyList(),
                chemicalMmol = 4.6f,
                vendorStockMmol = 6f,
            )
        }

        assertTrue("output=$output", output > 5.2f)
    }

    @Test
    fun evidenceStateSurvivesSnapshotRestore() {
        val original = settled()
        listOf(5.4f, 5.0f, 4.7f).forEachIndexed { offset, chemical ->
            val index = DIVE_INDEX + offset
            original.process(
                6f, 6f, 34f, 2_900f, index, index * 60_000L, emptyList(),
                chemicalMmol = chemical,
                vendorStockMmol = 6f,
            )
        }
        val restored = SibionicsAdaptiveAlgorithmContext().apply { configure(1.4f) }
        assertTrue(restored.restore(original.snapshot()))

        val index = DIVE_INDEX + 3
        val expected = original.process(
            6f, 6f, 34f, 2_900f, index, index * 60_000L, emptyList(),
            chemicalMmol = 4.4f,
            vendorStockMmol = 6f,
        )
        val actual = restored.process(
            6f, 6f, 34f, 2_900f, index, index * 60_000L, emptyList(),
            chemicalMmol = 4.4f,
            vendorStockMmol = 6f,
        )

        assertEquals(expected, actual, 0.0001f)
    }

    private companion object {
        private const val WARMUP_INDEX = 120
        private const val DIVE_INDEX = 160
    }
}
