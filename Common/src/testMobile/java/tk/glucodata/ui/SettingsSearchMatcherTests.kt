package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchMatcherTests {
    private fun candidate(title: String, location: String, vararg terms: String) =
        SettingsSearchMatcher.Candidate(title, title, location, terms.toList())

    private val settings = listOf(
        candidate("Glucose notification", "Display", "Text, trend arrow, chart and status line"),
        candidate("Show chart (expanded)", "Display › Glucose notification"),
        candidate("Dashboard and charts", "Display"),
        candidate("High connection priority", "Advanced › Connection and handover", "Turbo"),
        candidate("Étalonnage", "Glucose"),
        candidate("Glucose unit", "Settings", "mg/dL", "mmol/L"),
        candidate("Bağlantı ve devir", "Sensörler"),
    )

    private fun search(query: String) = SettingsSearchMatcher.search(query, settings)

    @Test
    fun blankQueryFindsNothing() {
        assertTrue(search("").isEmpty())
        assertTrue(search("   ").isEmpty())
    }

    @Test
    fun everyWordMustMatchSomewhere() {
        assertEquals(listOf("Show chart (expanded)"), search("notif expanded"))
        assertTrue(search("chart turbo").isEmpty())
    }

    @Test
    fun titleMatchesComeBeforeLocationMatches() {
        // "Show chart" and "Dashboard and charts" carry it in the title (in screen order);
        // the notification row only mentions it in its description.
        assertEquals(
            listOf("Show chart (expanded)", "Dashboard and charts", "Glucose notification"),
            search("chart")
        )
    }

    @Test
    fun anotherNameOfTheSettingComesBeforeWhereItLives() {
        // "Import" is another name of the CSV row; the AAPS row only lives under "Import".
        val rows = listOf(
            candidate("AAPS treatments", "Journal › Import"),
            candidate("CSV file", "Backup and restore", "Import"),
        )
        assertEquals(listOf("CSV file", "AAPS treatments"), SettingsSearchMatcher.search("import", rows))
        val matches = SettingsSearchMatcher.matches("journal", rows + candidate("Journal", "Settings"))
        assertEquals(listOf("Journal", "AAPS treatments"), matches.map { it.item })
        // The screen is what was asked for; the row inside it is only near it.
        assertEquals(listOf(true, false), matches.map { it.direct })
    }

    @Test
    fun aDescriptionFindsASettingOnlyAsRelated() {
        val rows = listOf(
            SettingsSearchMatcher.Candidate(
                "Health Connect activity", "Health Connect activity", "Journal › Import",
                descriptions = listOf("Add workouts and steps to the journal"),
            ),
            candidate("Insulin on board", "Display", "IOB"),
        )
        val journal = SettingsSearchMatcher.matches("journal", rows).single()
        assertEquals(false, journal.direct)
        val workouts = SettingsSearchMatcher.matches("workouts", rows).single()
        assertEquals(false, workouts.direct)
        assertEquals("Add workouts and steps to the journal", workouts.via)
        // Another name is the setting itself.
        assertEquals(true, SettingsSearchMatcher.matches("iob", rows).single().direct)
    }

    @Test
    fun aPlaceComesBeforeTheSettingsInsideOneThatMatchAsWell() {
        val rows = listOf(
            SettingsSearchMatcher.Candidate("Effective IOB", "Effective IOB", "Journal › Calculations", level = 1),
            SettingsSearchMatcher.Candidate("Insulin on board", "Insulin on board", "Display", listOf("IOB"), level = 1),
            SettingsSearchMatcher.Candidate("IOB screen", "IOB screen", "Settings", level = 0),
        )
        assertEquals(listOf("IOB screen", "Effective IOB", "Insulin on board"), SettingsSearchMatcher.search("iob", rows))
    }

    @Test
    fun oldNamesStillFindTheSetting() {
        assertEquals(listOf("High connection priority"), search("turbo"))
    }

    @Test
    fun aMatchFoundOnlyByATermNamesIt() {
        val turbo = SettingsSearchMatcher.matches("turbo", settings).single()
        assertEquals("Turbo", turbo.via)
        // Title or location alone hold it: nothing to explain.
        assertNull(SettingsSearchMatcher.matches("expanded", settings).single().via)
        assertNull(SettingsSearchMatcher.matches("handover", settings).single().via)
        // The term that holds the missing word, not merely the first one.
        assertEquals("mmol/L", SettingsSearchMatcher.matches("unit mmol", settings).single().via)
    }

    @Test
    fun caseAccentsAndPunctuationAreIgnored() {
        assertEquals(listOf("Étalonnage"), search("etalon"))
        assertEquals(listOf("Glucose unit"), search("MG/DL"))
        assertEquals(listOf("Glucose unit"), search("mmol l"))
    }

    @Test
    fun turkishDotlessIMatchesEitherCase() {
        assertEquals(listOf("Bağlantı ve devir"), search("BAĞLANTI"))
        assertEquals(listOf("Bağlantı ve devir"), search("baglanti"))
        assertEquals(listOf("Bağlantı ve devir"), search("bağlantı"))
    }

    @Test
    fun highlightsMarkTheMatchedWordsInTheOriginalText() {
        val text = "Health Connect activity"
        assertEquals(listOf(0..5), SettingsSearchMatcher.highlights(text, "health"))
        // Two words, out of order, each marked where it sits.
        assertEquals(listOf(0..5, 7..13), SettingsSearchMatcher.highlights(text, "connect HEALTH"))
        assertTrue(SettingsSearchMatcher.highlights(text, "turbo").isEmpty())
    }

    @Test
    fun highlightsPreferTheStartOfAWord() {
        assertEquals(listOf(7..9), SettingsSearchMatcher.highlights("Narrow arrow", "arr"))
        // Only inside a word: still marked.
        assertEquals(listOf(1..3), SettingsSearchMatcher.highlights("Narrow", "arr"))
    }

    @Test
    fun highlightsSurviveAccentsAndSeparators() {
        assertEquals(listOf(0..4), SettingsSearchMatcher.highlights("Étalonnage", "etalo"))
        // A combining accent stays inside the mark of the letter it sits on.
        assertEquals(listOf(0..2), SettingsSearchMatcher.highlights("Éte", "et"))
        assertEquals(listOf(0..1, 3..4), SettingsSearchMatcher.highlights("mg/dL", "mg dl"))
        assertEquals(listOf(10..15), SettingsSearchMatcher.highlights("Journal › Import", "import"))
    }
}
