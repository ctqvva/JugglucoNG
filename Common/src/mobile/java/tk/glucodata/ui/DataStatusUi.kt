package tk.glucodata.ui

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tk.glucodata.DisplayDataState
import tk.glucodata.R
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

internal data class DashboardDataStatusText(
    val title: String,
    val isStale: Boolean = false
)

internal fun shouldShowDashboardSensorStatus(
    isCloneSource: Boolean,
    isFreshData: Boolean,
    sensorStatus: String,
    heroOwnsAwaitingStatus: Boolean,
): Boolean = (!isFreshData || !isCloneSource) &&
    sensorStatus.isNotEmpty() &&
    sensorStatus != "Ready" &&
    !heroOwnsAwaitingStatus

@Composable
internal fun rememberDashboardDataStatusText(
    dataState: DisplayDataState.Status,
    sensorStatus: String
): DashboardDataStatusText? {
    if (dataState.isFresh || dataState.kind == DisplayDataState.Kind.NO_SENSOR) {
        return null
    }

    val context = LocalContext.current
    val meaningfulSensorStatus = sensorStatus.trim().takeIf {
        it.isNotEmpty() && !it.equals("Ready", ignoreCase = true)
    }

    return when (dataState.kind) {
        DisplayDataState.Kind.STALE -> DashboardDataStatusText(
            title = buildStaleDataStatusTitle(
                context = context,
                fallback = stringResource(R.string.no_data_available),
                latestTimestampMillis = dataState.latestTimestampMillis,
                ageMillis = dataState.ageMillis
            ),
            isStale = true
        )

        DisplayDataState.Kind.AWAITING_DATA -> DashboardDataStatusText(
            title = meaningfulSensorStatus ?: stringResource(R.string.status_waiting_for_data),
            isStale = false
        )

        else -> null
    }
}

private fun buildStaleDataStatusTitle(
    context: Context,
    fallback: String,
    latestTimestampMillis: Long,
    ageMillis: Long
): String {
    val clampedAgeMillis = ageMillis.coerceAtLeast(0L)
    val durationLabel = when {
        clampedAgeMillis < TimeUnit.HOURS.toMillis(1) -> {
            val minutes = maxOf(1L, TimeUnit.MILLISECONDS.toMinutes(clampedAgeMillis)).toInt()
            context.getString(R.string.minutes_short_format, minutes)
        }

        clampedAgeMillis < TimeUnit.DAYS.toMillis(1) -> {
            val hours = maxOf(1L, TimeUnit.MILLISECONDS.toHours(clampedAgeMillis)).toInt()
            context.getString(R.string.hours_short, hours)
        }

        else -> null
    }

    if (durationLabel != null) {
        return context.getString(R.string.status_no_new_value_for_duration, durationLabel)
    }

    if (latestTimestampMillis > 0L) {
        val timestampLabel = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(latestTimestampMillis))
        return context.getString(R.string.status_no_new_value_since_time, timestampLabel)
    }

    return fallback
}

@Composable
internal fun DataStatusPulse(
    color: Color,
    modifier: Modifier = Modifier,
    subdued: Boolean = false
) {
    val transition = rememberInfiniteTransition(label = "DataStatusPulse")
    val phase1 = transition.animateFloat(
        initialValue = 0.42f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = if (subdued) 1800 else 1400
                0.42f at 0
                1f at 320 using LinearEasing
                0.55f at 760 using LinearEasing
                0.42f at if (subdued) 1800 else 1400
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "DataStatusPulsePhase1"
    )
    val phase2 = transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = if (subdued) 1800 else 1400
                0.55f at 0
                0.55f at 180
                1f at 520 using LinearEasing
                0.58f at 980 using LinearEasing
                0.55f at if (subdued) 1800 else 1400
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "DataStatusPulsePhase2"
    )
    val phase3 = transition.animateFloat(
        initialValue = 0.68f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = if (subdued) 1800 else 1400
                0.68f at 0
                0.68f at 340
                1f at 720 using LinearEasing
                0.62f at 1180 using LinearEasing
                0.68f at if (subdued) 1800 else 1400
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "DataStatusPulsePhase3"
    )

    Row(
        modifier = modifier.width(if (subdued) 22.dp else 28.dp).height(if (subdued) 16.dp else 22.dp),
        horizontalArrangement = Arrangement.spacedBy(if (subdued) 3.dp else 4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(phase1.value, phase2.value, phase3.value).forEach { amplitude ->
            Canvas(modifier = Modifier.weight(1f).fillMaxSize()) {
                val barWidth = size.width
                val barHeight = size.height * (0.35f + (amplitude * 0.65f))
                drawRoundRect(
                    color = color.copy(alpha = 0.28f + (amplitude * 0.62f)),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - barHeight),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            }
        }
    }
}
