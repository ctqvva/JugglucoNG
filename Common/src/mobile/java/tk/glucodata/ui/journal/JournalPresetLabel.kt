package tk.glucodata.ui.journal

import tk.glucodata.data.journal.JournalBuiltInCurveProfile
import tk.glucodata.data.journal.JournalInsulinPreset

/**
 * The name the dose sheet's insulin buttons show for [preset].
 *
 * A built-in preset's name is written into the database once, when the presets are seeded,
 * in the language and wording of that moment. The two generic ones are the buttons most
 * people see, and their seeded names ("Rapid acting (generic)") do not fit a half-width
 * button in several languages. So while the stored name is still one the app itself seeded,
 * the button shows the current wording from the resources instead: a shorter translation, or
 * a change of language, reaches installs that were seeded earlier.
 *
 * "One the app seeded" means an exact match in [seededNames]: the default in every language
 * the app ships, plus wordings it used to ship. Anything else is a name the user typed and
 * is shown as it is, whatever it looks like.
 */
internal fun journalPresetButtonLabel(
    preset: JournalInsulinPreset,
    rapidGenericName: String,
    longGenericName: String,
    seededNames: Set<String>
): String {
    if (!preset.isBuiltIn) return preset.displayName
    val current = when (JournalBuiltInCurveProfile.fromStorage(preset.curveProfileId)) {
        JournalBuiltInCurveProfile.RAPID_GENERIC -> rapidGenericName
        JournalBuiltInCurveProfile.LONG_BASAL_GENERIC -> longGenericName
        else -> return preset.displayName
    }
    return if (current.isNotBlank() && preset.displayName.trim() in seededNames) current else preset.displayName
}

/**
 * Seeded names of the two generic presets that are no longer in the resources. An install
 * seeded with one of these still holds it, so it has to stay recognisable as a default.
 */
internal val LEGACY_SEEDED_GENERIC_PRESET_NAMES: Set<String> = setOf(
    "Ad azione rapida (generica)",
    "Ad azione prolungata (generica)"
)
