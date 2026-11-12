package tk.glucodata.ui.journal

import tk.glucodata.data.journal.JournalBuiltInCurveProfile
import tk.glucodata.data.journal.JournalInsulinPreset

/**
 * The name the dose sheet's insulin buttons show for [preset].
 *
 * A built-in preset's name is written into the database once, when the presets are seeded,
 * in the language and wording of that moment. The two generic ones are the buttons most
 * people see, and their seeded names ("Rapid acting (generic)") do not fit a half-width
 * button in several languages. So while such a name still looks like a seeded default, the
 * button shows the current wording from the resources instead: a shorter translation, or a
 * change of language, reaches installs that were seeded earlier.
 *
 * A name the user typed is theirs and is shown as it is. Seeded defaults all end in a
 * bracketed qualifier, which is what tells the two apart without keeping a list of every
 * default in every language.
 */
internal fun journalPresetButtonLabel(
    preset: JournalInsulinPreset,
    rapidGenericName: String,
    longGenericName: String
): String {
    if (!preset.isBuiltIn) return preset.displayName
    val current = when (JournalBuiltInCurveProfile.fromStorage(preset.curveProfileId)) {
        JournalBuiltInCurveProfile.RAPID_GENERIC -> rapidGenericName
        JournalBuiltInCurveProfile.LONG_BASAL_GENERIC -> longGenericName
        else -> return preset.displayName
    }
    val stored = preset.displayName.trim()
    val looksSeeded = stored.endsWith(")") || stored.endsWith("\uFF09")
    return if (looksSeeded && current.isNotBlank()) current else preset.displayName
}
