package tk.glucodata.ui.meal

import androidx.annotation.StringRes
import java.time.LocalTime
import tk.glucodata.R

/** Initial title only, chosen using local time when the new-meal action runs. */
@StringRes
internal fun suggestedMealTitleRes(time: LocalTime = LocalTime.now()): Int = when (time.hour) {
    in 5..10 -> R.string.meal_suggested_breakfast
    in 11..13 -> R.string.meal_suggested_lunch
    in 17..20 -> R.string.meal_suggested_dinner
    else -> R.string.meal_suggested_snack
}
