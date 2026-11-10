package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchMatcherTests {
    private fun candidate(title: String, vararg context: String) =
        SettingsSearchMatcher.Candidate(title, title, context.toList())

    private val settings = listOf(
        candidate("Glucose notification", "Display", "Text, trend arrow, chart and status line"),
        candidate("Show chart (expanded)", "Display › Glucose notification"),
        candidate("Dashboard and charts", "Display"),
        candidate("High connection priority", "Sensors › Connection and handover", "Turbo"),
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
    fun oldNamesStillFindTheSetting() {
        assertEquals(listOf("High connection priority"), search("turbo"))
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
}
