package tk.glucodata.ui.journal

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LunchDining
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Vaccines
import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.data.journal.JournalEntryType

class JournalMealIconTests {
    @Test
    fun eatingFromAMealUsesTheMealIcon() {
        assertEquals(Icons.Default.Restaurant, JournalEntryType.CARBS.journalActionIcon(mealId = 42L))
    }

    @Test
    fun standaloneFoodKeepsTheFoodIcon() {
        assertEquals(Icons.Default.LunchDining, JournalEntryType.CARBS.journalActionIcon())
    }

    @Test
    fun insulinLinkedToAMealStillUsesTheInsulinIcon() {
        assertEquals(Icons.Default.Vaccines, JournalEntryType.INSULIN.journalActionIcon(mealId = 42L))
    }
}
