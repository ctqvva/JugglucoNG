package tk.glucodata.ui.meal

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.R

class MealTitleSuggestionTests {
    @Test
    fun breakfastFromFiveUntilEleven() {
        assertTitle(R.string.meal_suggested_breakfast, "05:00", "08:30", "10:59:59.999999999")
    }

    @Test
    fun lunchFromElevenUntilFourteen() {
        assertTitle(R.string.meal_suggested_lunch, "11:00", "12:30", "13:59:59.999999999")
    }

    @Test
    fun dinnerFromSeventeenUntilTwentyOne() {
        assertTitle(R.string.meal_suggested_dinner, "17:00", "19:30", "20:59:59.999999999")
    }

    @Test
    fun snackOutsideMealWindowsIncludingMidnight() {
        assertTitle(
            R.string.meal_suggested_snack,
            "00:00", "04:59:59.999999999", "14:00", "16:59:59.999999999", "21:00", "23:59:59.999999999"
        )
    }

    private fun assertTitle(expected: Int, vararg times: String) {
        times.forEach { time -> assertEquals(time, expected, suggestedMealTitleRes(LocalTime.parse(time))) }
    }
}
