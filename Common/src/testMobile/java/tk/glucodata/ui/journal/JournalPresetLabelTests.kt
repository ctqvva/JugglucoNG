package tk.glucodata.ui.journal

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.data.journal.JournalBuiltInCurveProfile
import tk.glucodata.data.journal.JournalInsulinPreset

class JournalPresetLabelTests {

    private fun preset(
        name: String,
        builtIn: Boolean = true,
        profile: JournalBuiltInCurveProfile? = JournalBuiltInCurveProfile.RAPID_GENERIC
    ) = JournalInsulinPreset(
        id = 1L,
        displayName = name,
        onsetMinutes = 15,
        durationMinutes = 240,
        accentColor = 0,
        curveJson = "",
        isBuiltIn = builtIn,
        isArchived = false,
        countsTowardIob = true,
        sortOrder = 0,
        curveProfileId = profile?.storageValue
    )

    private fun label(preset: JournalInsulinPreset) =
        journalPresetButtonLabel(preset, rapidGenericName = "Rapida", longGenericName = "Lenta")

    // The name was written to the database when the presets were seeded; an install seeded
    // before the wording changed still holds the long one and must show the short one.
    @Test
    fun aSeededGenericNameFollowsTheCurrentWording() {
        assertEquals("Rapida", label(preset("Ad azione rapida (generica)")))
        assertEquals(
            "Lenta",
            label(preset("Ad azione prolungata (generica)", profile = JournalBuiltInCurveProfile.LONG_BASAL_GENERIC))
        )
    }

    @Test
    fun aNameSeededInAnotherLanguageFollowsTheCurrentLanguage() {
        assertEquals("Rapida", label(preset("Rapid acting (generic)")))
        assertEquals("Rapida", label(preset("速效（通用）")))
    }

    @Test
    fun aNameTheUserTypedIsKept() {
        assertEquals("NovoRapid", label(preset("NovoRapid")))
        assertEquals("Rapida", label(preset("Rapida")))
    }

    @Test
    fun otherPresetsKeepTheirStoredName() {
        assertEquals("Fiasp", label(preset("Fiasp", profile = JournalBuiltInCurveProfile.FIASP)))
        assertEquals(
            "Basale ultra-lenta (generica)",
            label(preset("Basale ultra-lenta (generica)", profile = JournalBuiltInCurveProfile.ULTRA_LONG_BASAL))
        )
        assertEquals("Mia (test)", label(preset("Mia (test)", builtIn = false, profile = null)))
        assertEquals("Vecchia (generica)", label(preset("Vecchia (generica)", profile = null)))
    }

    @Test
    fun aMissingResourceNeverBlanksTheButton() {
        assertEquals(
            "Rapid acting (generic)",
            journalPresetButtonLabel(preset("Rapid acting (generic)"), rapidGenericName = "", longGenericName = "")
        )
    }
}
