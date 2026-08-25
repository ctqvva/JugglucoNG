package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalInsulinCurveCatalogueTests {

    @Test
    fun everyBuiltInProfileHasAValidVersionedCurve() {
        JournalBuiltInCurveProfile.entries.forEach { profile ->
            val definition = JournalInsulinCurveCatalogue.definition(profile)
            assertEquals(profile.storageValue, definition.profileId)
            assertTrue("${profile.name} has no variants", definition.variants.isNotEmpty())
            definition.variants.forEach { variant ->
                assertTrue("${profile.name} has too few points", variant.points.size >= 3)
                assertEquals(variant.points.map { it.minute }.sorted(), variant.points.map { it.minute })
                assertEquals(variant.points.map { it.minute }.distinct(), variant.points.map { it.minute })
                assertTrue(variant.points.all { it.minute >= 0 })
                assertTrue(variant.points.all { it.activity.isFinite() && it.activity in 0f..1f })
                val peak = variant.points.maxOf { it.activity }
                if (definition.evidence.isSourceBacked) {
                    assertEquals("${profile.name} source peak", 1f, peak, 0.0001f)
                } else {
                    assertTrue("${profile.name} legacy peak is too low", peak > 0.9f)
                }
                if (definition.evidence.requiresZeroEndpoints) {
                    assertEquals(0f, variant.points.first().activity, 0.0001f)
                    assertEquals(0f, variant.points.last().activity, 0.0001f)
                }
                variant.onsetMinutes?.let { onset ->
                    assertTrue("${profile.name} onset is outside its curve", onset in 0..variant.points.last().minute)
                }
            }
        }
    }

    @Test
    fun evidenceStatesPreventSteadyStateAndIncompleteTailsFromBecomingDoseCurves() {
        val steadyState = setOf(
            JournalBuiltInCurveProfile.GLARGINE_U300,
            JournalBuiltInCurveProfile.DEGLUDEC,
            JournalBuiltInCurveProfile.ICODEC
        )
        val referenceOnly = setOf(
            JournalBuiltInCurveProfile.NPH,
            JournalBuiltInCurveProfile.GLARGINE_U100,
            JournalBuiltInCurveProfile.DETEMIR,
            JournalBuiltInCurveProfile.RYZODEG_70_30,
            JournalBuiltInCurveProfile.LISPRO_MIX_50_50,
            JournalBuiltInCurveProfile.LISPRO_MIX_75_25,
            JournalBuiltInCurveProfile.HUMAN_MIX_70_30
        )

        steadyState.forEach {
            assertEquals(
                JournalCurveEvidence.SOURCE_STEADY_STATE,
                JournalInsulinCurveCatalogue.definition(it).evidence
            )
            assertFalse(JournalInsulinCurveCatalogue.definition(it).evidence.supportsPerDoseCalculation)
        }
        referenceOnly.forEach {
            assertEquals(
                JournalCurveEvidence.SOURCE_REFERENCE,
                JournalInsulinCurveCatalogue.definition(it).evidence
            )
            assertFalse(JournalInsulinCurveCatalogue.definition(it).evidence.supportsPerDoseCalculation)
        }
        assertTrue(JournalInsulinCurveCatalogue.referenceCurve(JournalBuiltInCurveProfile.DEGLUDEC).first().activity > 0f)
        assertTrue(JournalInsulinCurveCatalogue.referenceCurve(JournalBuiltInCurveProfile.NPH).last().activity > 0f)
    }

    @Test
    fun exactFiaspDosePerKgUsesTheMatchingSourceCurve() {
        val resolved = JournalInsulinCurveCatalogue.resolve(
            JournalBuiltInCurveProfile.FIASP,
            amountUnits = 7f,
            bodyWeightKg = 70f
        )
        val expected = JournalInsulinCurveCatalogue.definition(JournalBuiltInCurveProfile.FIASP)
            .variants.first { it.dose == 0.1f }
            .points

        assertEquals(expected, resolved.points)
        assertEquals(70f, resolved.usedBodyWeightKg!!, 0.0001f)
        assertFalse(resolved.approximated)
        assertEquals(JournalInsulinCurveCatalogue.MODEL_VERSION, resolved.modelVersion)
    }

    @Test
    fun interpolatedAndOutOfRangeDosesAreMarkedApproximate() {
        val definition = JournalInsulinCurveCatalogue.definition(JournalBuiltInCurveProfile.FIASP)
        val interpolated = JournalInsulinCurveCatalogue.resolve(
            JournalBuiltInCurveProfile.FIASP,
            amountUnits = 10.5f,
            bodyWeightKg = 70f
        )
        val clamped = JournalInsulinCurveCatalogue.resolve(
            JournalBuiltInCurveProfile.FIASP,
            amountUnits = 2f,
            bodyWeightKg = 100f
        )

        assertTrue(interpolated.approximated)
        assertNotEquals(definition.variants.first().points, interpolated.points)
        assertNotEquals(definition.variants[1].points, interpolated.points)
        assertTrue(clamped.approximated)
        assertEquals(definition.variants.first().points, clamped.points)
    }

    @Test
    fun missingWeightUsesReferenceCurveAndRecordsNoWeight() {
        val resolved = JournalInsulinCurveCatalogue.resolve(
            JournalBuiltInCurveProfile.FIASP,
            amountUnits = 8f,
            bodyWeightKg = null
        )

        assertEquals(
            JournalInsulinCurveCatalogue.referenceCurve(JournalBuiltInCurveProfile.FIASP),
            resolved.points
        )
        assertNull(resolved.usedBodyWeightKg)
        assertTrue(resolved.approximated)
    }

    @Test
    fun changingWeightOnlyChangesSubsequentResolutions() {
        val original = JournalInsulinCurveCatalogue.resolve(
            JournalBuiltInCurveProfile.FIASP,
            amountUnits = 14f,
            bodyWeightKg = 70f
        )
        val afterWeightChange = JournalInsulinCurveCatalogue.resolve(
            JournalBuiltInCurveProfile.FIASP,
            amountUnits = 14f,
            bodyWeightKg = 140f
        )

        assertFalse(original.approximated)
        assertFalse(afterWeightChange.approximated)
        assertNotEquals(original.points, afterWeightChange.points)
        assertEquals(70f, original.usedBodyWeightKg!!, 0.0001f)
        assertEquals(140f, afterWeightChange.usedBodyWeightKg!!, 0.0001f)
        // The first immutable value still describes the original 0.2 U/kg dose.
        assertEquals(122, original.points.maxByOrNull { it.activity }?.minute)
        assertEquals(91, afterWeightChange.points.maxByOrNull { it.activity }?.minute)
    }

    @Test
    fun sourceTimingAnchorsMatchTheOfficialPharmacodynamicLabels() {
        assertEquals(20, JournalInsulinCurveCatalogue.referenceOnsetMinutes(JournalBuiltInCurveProfile.FIASP))
        assertEquals(
            91,
            JournalInsulinCurveCatalogue.referenceCurve(JournalBuiltInCurveProfile.FIASP)
                .maxByOrNull { it.activity }
                ?.minute
        )
        assertEquals(
            162,
            JournalInsulinCurveCatalogue.referenceCurve(JournalBuiltInCurveProfile.ASPART_MIX_70_30)
                .maxByOrNull { it.activity }
                ?.minute
        )
        assertEquals(
            120,
            JournalInsulinCurveCatalogue.referenceCurve(JournalBuiltInCurveProfile.LISPRO_MIX_50_50)
                .maxByOrNull { it.activity }
                ?.minute
        )
        assertEquals(
            120,
            JournalInsulinCurveCatalogue.referenceCurve(JournalBuiltInCurveProfile.LISPRO_MIX_75_25)
                .maxByOrNull { it.activity }
                ?.minute
        )
        assertEquals(50, JournalInsulinCurveCatalogue.referenceOnsetMinutes(JournalBuiltInCurveProfile.HUMAN_MIX_70_30))
        assertEquals(
            210,
            JournalInsulinCurveCatalogue.referenceCurve(JournalBuiltInCurveProfile.HUMAN_MIX_70_30)
                .maxByOrNull { it.activity }
                ?.minute
        )
    }

    @Test
    fun commercialAndScientificNamesAreSeparateMetadata() {
        assertEquals("aspart", JournalInsulinCurveCatalogue.scientificName(JournalBuiltInCurveProfile.FIASP))
        assertEquals("degludec", JournalInsulinCurveCatalogue.scientificName(JournalBuiltInCurveProfile.DEGLUDEC))
        assertNull(JournalInsulinCurveCatalogue.scientificName(JournalBuiltInCurveProfile.RAPID_GENERIC))
    }

    @Test
    fun aSingleObservedWeightIndexedDoseIsExactOnlyAtItsSourceDose() {
        val exact = JournalInsulinCurveCatalogue.resolve(
            JournalBuiltInCurveProfile.HUMAN_REGULAR,
            amountUnits = 21f,
            bodyWeightKg = 70f
        )
        val differentWeight = JournalInsulinCurveCatalogue.resolve(
            JournalBuiltInCurveProfile.HUMAN_REGULAR,
            amountUnits = 21f,
            bodyWeightKg = 84f
        )

        assertFalse(exact.approximated)
        assertTrue(differentWeight.approximated)
        assertEquals(exact.points, differentWeight.points)
    }
}
