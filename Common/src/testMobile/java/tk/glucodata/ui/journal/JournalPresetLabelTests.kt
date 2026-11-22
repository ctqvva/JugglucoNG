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

    // What seededGenericPresetNames() collects on a device: every shipped language's default
    // (a few stand in for all of them here) plus the wordings no longer in the resources.
    private val seeded = LEGACY_SEEDED_GENERIC_PRESET_NAMES + setOf(
        "Rapida", "Lenta", "Rapid acting (generic)", "Long acting (generic)", "速效（通用）", "长效（通用）"
    )

    private fun label(preset: JournalInsulinPreset, rapid: String = "Rapida", long: String = "Lenta") =
        journalPresetButtonLabel(preset, rapidGenericName = rapid, longGenericName = long, seededNames = seeded)

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
    }

    // A renamed built-in keeps its built-in flag and curve. Its name is the user's even when
    // it ends in a bracket, which an earlier version took as the mark of a seeded default.
    @Test
    fun aRenamedBuiltInEndingInABracketIsKept() {
        assertEquals("NovoRapid (breakfast)", label(preset("NovoRapid (breakfast)")))
        assertEquals("Mia rapida (generica)", label(preset("Mia rapida (generica)")))
    }

    // The short Italian defaults have no bracket; they are still seeded names, so an install
    // seeded in Italian follows a later change of language.
    @Test
    fun aShortSeededNameStillFollowsALanguageChange() {
        assertEquals(
            "Rapid acting (generic)",
            label(preset("Rapida"), rapid = "Rapid acting (generic)", long = "Long acting (generic)")
        )
        assertEquals(
            "Long acting (generic)",
            label(
                preset("Lenta", profile = JournalBuiltInCurveProfile.LONG_BASAL_GENERIC),
                rapid = "Rapid acting (generic)",
                long = "Long acting (generic)"
            )
        )
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
            label(preset("Rapid acting (generic)"), rapid = "", long = "")
        )
    }
}
