package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalPresetMergeTests {
    private fun preset(
        id: Long,
        name: String,
        sortOrder: Int
    ) = JournalInsulinPresetEntity(
        id = id,
        displayName = name,
        onsetMinutes = 1,
        durationMinutes = 60,
        accentColor = 0,
        curveJson = "0:0;30:1;60:0",
        isBuiltIn = true,
        isArchived = false,
        countsTowardIob = true,
        sortOrder = sortOrder
    )

    @Test
    fun currentElevenPresetLayoutMatchesTheSameSortOrderAfterRenaming() {
        val existing = (0..10).map { preset(it.toLong() + 1, "Old localized $it", it) }
        val renamedRapid = preset(0, "Rapid acting (generic)", 0)
        val renamedLong = preset(0, "Long acting (generic)", 1)

        assertEquals(1L, matchExistingBuiltInPreset(renamedRapid, existing)?.id)
        assertEquals(2L, matchExistingBuiltInPreset(renamedLong, existing)?.id)
    }

    @Test
    fun olderSixPresetLayoutStillUsesItsHistoricalMapping() {
        val existing = (0..5).map { preset(it.toLong() + 10, "Legacy $it", it) }

        assertEquals(11L, matchExistingBuiltInPreset(preset(0, "Rapid", 0), existing)?.id)
        assertEquals(13L, matchExistingBuiltInPreset(preset(0, "NPH", 9), existing)?.id)
        assertNull(matchExistingBuiltInPreset(preset(0, "Fiasp", 6), existing))
    }

    @Test
    fun exactNameWinsEvenWhenSortOrdersDiffer() {
        val expected = preset(42, "Fiasp", 99)
        val existing = listOf(expected)

        assertEquals(
            expected,
            matchExistingBuiltInPreset(preset(0, "Fiasp", 6), existing)
        )
    }

    @Test
    fun stableProfileWinsWhenCommercialLabelChanges() {
        val expected = preset(42, "Fiasp (aspart)", 99).copy(
            curveProfileId = JournalBuiltInCurveProfile.FIASP.storageValue,
            curveModelVersion = 1
        )
        val renamed = preset(0, "Fiasp", 6).copy(
            curveProfileId = JournalBuiltInCurveProfile.FIASP.storageValue,
            curveModelVersion = JournalInsulinCurveCatalogue.MODEL_VERSION
        )

        assertEquals(expected, matchExistingBuiltInPreset(renamed, listOf(expected)))
    }

    private fun sourcePreset(profile: JournalBuiltInCurveProfile, sortOrder: Int): JournalInsulinPresetEntity {
        val definition = JournalInsulinCurveCatalogue.definition(profile)
        val curve = JournalInsulinCurveCatalogue.referenceCurve(profile)
        return preset(0, profile.name, sortOrder).copy(
            onsetMinutes = JournalInsulinCurveCatalogue.referenceOnsetMinutes(profile),
            durationMinutes = curve.last().minute,
            curveJson = serializeJournalCurve(curve, definition.evidence.requiresZeroEndpoints),
            curveProfileId = profile.storageValue,
            curveModelVersion = JournalInsulinCurveCatalogue.MODEL_VERSION,
            curveEvidence = definition.evidence.storageValue
        )
    }

    @Test
    fun untouchedLegacyBuiltInCurveIsUpgradedToItsSourceProfile() {
        val legacy = preset(7, "Fiasp", 6).copy(
            curveJson = serializeJournalCurve(legacyGeneratedJournalCurve(JournalBuiltInCurveProfile.FIASP)),
            countsTowardIob = true,
            useForCalculation = true
        )
        val fresh = sourcePreset(JournalBuiltInCurveProfile.FIASP, 6)

        val merged = mergeBuiltInPreset(fresh, legacy)

        assertEquals(7L, merged.id)
        assertEquals(fresh.curveJson, merged.curveJson)
        assertEquals(JournalBuiltInCurveProfile.FIASP.storageValue, merged.curveProfileId)
        assertEquals(JournalInsulinCurveCatalogue.MODEL_VERSION, merged.curveModelVersion)
        assertEquals(JournalCurveEvidence.SOURCE_SINGLE_DOSE.storageValue, merged.curveEvidence)
        assertEquals(fresh.onsetMinutes, merged.onsetMinutes)
        assertTrue(merged.useForCalculation)
    }

    @Test
    fun editedLegacyCurveStaysAsTheUsersUnverifiedCurve() {
        val edited = preset(7, "Fiasp", 6).copy(curveJson = "0:0;40:1;300:0", isArchived = true)
        val fresh = sourcePreset(JournalBuiltInCurveProfile.FIASP, 6)

        val merged = mergeBuiltInPreset(fresh, edited)

        assertEquals("0:0;40:1;300:0", merged.curveJson)
        assertNull(merged.curveProfileId)
        assertEquals(JournalCurveEvidence.UNVERIFIED.storageValue, merged.curveEvidence)
        assertTrue(merged.isArchived)
        assertEquals(fresh.displayName, merged.displayName)
    }

    @Test
    fun upgradingToAReferenceOnlyCurveKeepsIobButDropsCalculation() {
        val legacy = preset(10, "NPH", 9).copy(
            curveJson = serializeJournalCurve(legacyGeneratedJournalCurve(JournalBuiltInCurveProfile.NPH)),
            countsTowardIob = true,
            useForCalculation = true
        )
        val fresh = sourcePreset(JournalBuiltInCurveProfile.NPH, 9)

        val merged = mergeBuiltInPreset(fresh, legacy)

        assertEquals(JournalCurveEvidence.SOURCE_REFERENCE.storageValue, merged.curveEvidence)
        assertTrue(merged.countsTowardIob)
        assertFalse(merged.useForCalculation)
    }

    @Test
    fun olderSourceModelIsRefreshedInPlace() {
        val stale = sourcePreset(JournalBuiltInCurveProfile.FIASP, 6)
            .copy(id = 3, curveModelVersion = 1, curveJson = "0:0;55:1;360:0")
        val fresh = sourcePreset(JournalBuiltInCurveProfile.FIASP, 6)

        val merged = mergeBuiltInPreset(fresh, stale)

        assertEquals(3L, merged.id)
        assertEquals(fresh.curveJson, merged.curveJson)
        assertEquals(JournalInsulinCurveCatalogue.MODEL_VERSION, merged.curveModelVersion)
    }
}
