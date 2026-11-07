package tk.glucodata.ui.journal

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class JournalInsulinFormatTests {
    private lateinit var saved: Locale

    @Before
    fun pinLocale() {
        saved = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() = Locale.setDefault(saved)

    @Test
    fun doseTotalKeepsItsDecimalsAtTenUnitsAndAbove() {
        assertEquals("10.5", formatJournalDose(10.5f))
        assertEquals("12.25", formatJournalDose(12.25f))
        assertEquals("10", formatJournalDose(10f))
        assertEquals("0.5", formatJournalDose(0.5f))
    }

    @Test
    fun iobShowsOneDecimalAtEverySize() {
        assertEquals("10.5", formatJournalIob(10.5f))
        assertEquals("6.8", formatJournalIob(6.75f))
        assertEquals("12.0", formatJournalIob(12f))
    }
}
