package tk.glucodata.drivers.sibionics

import org.junit.Assert.*
import org.junit.Test

class SibionicsStartupRecoveryTest {
    private val variant = SibionicsConstants.Variant.EU

    private fun samples(): List<SibionicsSourceSample> = javaClass.classLoader!!
        .getResourceAsStream("sibionics_exact_v116a_startup.csv")!!
        .bufferedReader().useLines { lines ->
            lines.drop(1).map { line ->
                val fields = line.split(',')
                val index = fields[0].toInt()
                SibionicsSourceSample(index, 1_700_000_000_000L + index * 60_000L,
                    fields[1].toFloat(), fields[2].toFloat(), 1000f, variant.ordinal)
            }.toList()
        }

    @Test
    fun missingBeginningGapTruncatedTailAndWrongVariantCannotReplaceCheckpoint() {
        val sources = samples().take(70)
        assertNull(SibionicsStartupRecovery.sourcesForCheckpoint(emptyList(), 71, variant))
        assertNull(SibionicsStartupRecovery.sourcesForCheckpoint(sources.drop(1), 71, variant))
        assertNull(SibionicsStartupRecovery.sourcesForCheckpoint(sources.filter { it.index != 35 }, 71, variant))
        assertNull(SibionicsStartupRecovery.sourcesForCheckpoint(sources.dropLast(1), 71, variant))
        assertNull(SibionicsStartupRecovery.sourcesForCheckpoint(sources, 71, SibionicsConstants.Variant.CHINESE))
        assertNull(SibionicsStartupRecovery.sourcesForCheckpoint(sources, 1, variant))
    }

    @Test
    fun journalAheadOfCheckpointDoesNotSkipUncommittedInputs() {
        val prefix = SibionicsStartupRecovery.sourcesForCheckpoint(samples(), 71, variant)!!
        assertEquals((1..70).toList(), prefix.map { it.index })
    }

    @Test
    fun localRecoveryAndNewCheckpointPreserveExactContinuationForBothFamilies() {
        val sources = samples()
        for (family in listOf(variant, SibionicsConstants.Variant.CHINESE)) {
            val matching = sources.map { it.copy(variantId = family.ordinal) }
            val prefix = SibionicsStartupRecovery.sourcesForCheckpoint(matching, 71, family)!!
            val uninterrupted = SibionicsAlgorithmContext("same-sensor").also {
                it.configure("0316015A", 1.44f, family, SibionicsAlgorithmSelection.STOCK)
            }
            prefix.forEach { uninterrupted.process(it.rawMmol, it.temperatureC, it.index, SibionicsAlgorithmMode.REPLAY) }
            val recovered = SibionicsAlgorithmRebuilder.rebuild(
                "same-sensor", prefix, SibionicsAlgorithmSelection.STOCK, family,
                "0316015A", 1.44f, true,
            ) { values, _ -> values }.context
            val restarted = SibionicsAlgorithmContext("same-sensor").also {
                it.configure("0316015A", 1.44f, family, SibionicsAlgorithmSelection.STOCK)
            }
            assertTrue(restarted.restore(recovered.snapshot()))
            matching.drop(70).forEach {
                val expected = uninterrupted.process(it.rawMmol, it.temperatureC, it.index, SibionicsAlgorithmMode.REPLAY)
                assertEquals("$family local recovery at ${it.index}", expected,
                    recovered.process(it.rawMmol, it.temperatureC, it.index, SibionicsAlgorithmMode.REPLAY), 0f)
                assertEquals("$family next restart at ${it.index}", expected,
                    restarted.process(it.rawMmol, it.temperatureC, it.index, SibionicsAlgorithmMode.REPLAY), 0f)
            }
        }
    }
}
