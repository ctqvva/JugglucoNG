package tk.glucodata.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tk.glucodata.R
import tk.glucodata.SpeakSchedule
import tk.glucodata.VoiceReadings

/**
 * Voice readings as the dashboard shows them, or null when the switch is hidden: turned
 * off in Voice settings, or the user doesn't use voice readings.
 */
@Composable
fun rememberVoiceReadingsStatus(): VoiceReadings.Status? {
    val context = LocalContext.current.applicationContext
    val changes by VoiceReadings.changes.collectAsStateWithLifecycle()
    var minuteTick by remember { mutableLongStateOf(0L) }
    val status = remember(changes, minuteTick) {
        if (VoiceReadings.showOnDashboard(context) && VoiceReadings.isInUse(context)) {
            VoiceReadings.status(context)
        } else {
            null
        }
    }
    // A schedule opens and closes on the minute; re-read then, and only while one applies.
    val scheduled = status?.on == true && SpeakSchedule.isEnabled(context)
    LaunchedEffect(scheduled) {
        while (scheduled) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            minuteTick++
        }
    }
    return status
}

/** The pill's height: the chart's date chip, so both share one line at the top of the plot. */
internal val VoiceReadingsPillHeight = 32.dp

/**
 * One-tap voice readings switch at the top of the chart (#840). It overlays the plot's
 * header line beside the IOB pill, so it costs the chart no height.
 *
 * On, it says what that means now: the interval, or when the schedule lets it speak
 * again. Off, and wherever room is short ([compact]), it is the glyph alone.
 */
@Composable
fun VoiceReadingsChartPill(
    status: VoiceReadings.Status,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val title = stringResource(R.string.voice_readings)
    val summary = if (status.on) VoiceReadings.summary(context, status) else null
    val showLabel = summary != null && !compact
    val container by animateColorAsState(
        if (status.on) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        label = "voicePillContainer"
    )
    val content by animateColorAsState(
        if (status.on) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "voicePillContent"
    )
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .height(VoiceReadingsPillHeight)
            .widthIn(min = 40.dp)
            .clip(shape)
            .background(container, shape)
            .toggleable(
                value = status.on,
                role = Role.Switch,
                onValueChange = { VoiceReadings.setOn(context.applicationContext, it) }
            )
            .semantics { contentDescription = listOfNotNull(title, summary).joinToString(", ") }
            .animateContentSize()
            .padding(horizontal = if (showLabel) 12.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(
            imageVector = when {
                !status.on -> Icons.AutoMirrored.Filled.VolumeOff
                // On, but the schedule keeps it quiet until the time beside it.
                status.resumesAtMinutes != null -> Icons.Filled.Schedule
                else -> Icons.AutoMirrored.Filled.VolumeUp
            },
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(18.dp),
        )
        if (showLabel) {
            Text(
                text = summary.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
