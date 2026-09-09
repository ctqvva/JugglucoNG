package tk.glucodata.ui.util

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tk.glucodata.R

/**
 * "How long ago was the newest reading" — the live counter that started on the
 * sensor card and is now also shown on the dashboard sensor panel (primary
 * sensor) and in the hero card's peer pills (every other selected sensor).
 *
 * Seconds until a minute has passed, whole minutes after that; the ticker only
 * wakes when the displayed text can actually change.
 */
enum class SensorReadingAgeUnit {
    SECONDS,
    MINUTES
}

data class SensorReadingAge(
    val amount: Int,
    val unit: SensorReadingAgeUnit
)

fun sensorReadingAge(nowMillis: Long, readingMillis: Long): SensorReadingAge {
    val ageSeconds = ((nowMillis - readingMillis).coerceAtLeast(0L) / 1000L)
    return if (ageSeconds < 60L) {
        SensorReadingAge(ageSeconds.toInt(), SensorReadingAgeUnit.SECONDS)
    } else {
        SensorReadingAge(
            (ageSeconds / 60L).coerceAtLeast(1L).toInt(),
            SensorReadingAgeUnit.MINUTES
        )
    }
}

fun nextSensorReadingAgeDelay(nowMillis: Long, readingMillis: Long): Long {
    val ageSeconds = ((nowMillis - readingMillis).coerceAtLeast(0L) / 1000L)
    return if (ageSeconds < 60L) {
        1_000L
    } else {
        ((60L - (ageSeconds % 60L)) * 1_000L).coerceAtLeast(1_000L)
    }
}

/** Live "5s" / "12m" text for [readingMillis], recomposing only when it changes. */
@Composable
fun rememberSensorReadingAgeText(readingMillis: Long): String {
    var nowMillis by remember(readingMillis) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(readingMillis) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(nextSensorReadingAgeDelay(nowMillis, readingMillis))
        }
    }
    val age = remember(nowMillis, readingMillis) { sensorReadingAge(nowMillis, readingMillis) }
    return when (age.unit) {
        SensorReadingAgeUnit.SECONDS -> stringResource(R.string.sensor_reading_age_seconds, age.amount)
        SensorReadingAgeUnit.MINUTES -> stringResource(R.string.sensor_reading_age_minutes, age.amount)
    }
}

/** Clock glyph + the live age text, sized for whichever surface hosts it. */
@Composable
fun SensorReadingAgeLabel(
    readingMillis: Long,
    iconTint: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.labelSmall,
    iconSize: Dp = 12.dp
) {
    val ageText = rememberSensorReadingAgeText(readingMillis)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.AccessTime,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(iconSize)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = ageText,
            style = textStyle.copy(fontFeatureSettings = "tnum"),
            color = textColor,
            maxLines = 1,
            softWrap = false
        )
    }
}
