package tk.glucodata.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tk.glucodata.R
import tk.glucodata.SpeakSchedule
import tk.glucodata.VoiceReadings

/**
 * Voice readings as the dashboard shows them, or null when the user doesn't use them
 * (the item is then left out of the list, so it costs no gap).
 */
@Composable
fun rememberVoiceReadingsStatus(): VoiceReadings.Status? {
    val context = LocalContext.current.applicationContext
    val changes by VoiceReadings.changes.collectAsStateWithLifecycle()
    var minuteTick by remember { mutableLongStateOf(0L) }
    val status = remember(changes, minuteTick) {
        if (VoiceReadings.isInUse(context)) VoiceReadings.status(context) else null
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

/**
 * Features that act on their own while the app sits in the background, with a one-tap
 * switch each, under the hero (#840). Configuration stays in Settings. Today it holds
 * voice readings; keep it to features that are safe to stop with one tap: alarms never
 * belong here.
 */
@Composable
fun DashboardActiveFeatures(
    voice: VoiceReadings.Status,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VoiceReadingsToggle(voice)
    }
}

@Composable
private fun VoiceReadingsToggle(status: VoiceReadings.Status) {
    val context = LocalContext.current
    val title = stringResource(R.string.voice_readings)
    // The checked colours and the toggle semantics carry on/off; the label adds what
    // "on" means right now: the interval, or when the schedule lets it speak again.
    val label = if (status.on) "$title · ${VoiceReadings.summary(context, status)}" else title
    ToggleButton(
        checked = status.on,
        onCheckedChange = { VoiceReadings.setOn(context.applicationContext, it) },
        shapes = ToggleButtonDefaults.shapesFor(ButtonDefaults.MinHeight),
    ) {
        Icon(
            imageVector = when {
                !status.on -> Icons.AutoMirrored.Filled.VolumeOff
                // On, but the schedule keeps it quiet until the start time beside it.
                status.resumesAtMinutes != null -> Icons.Filled.Schedule
                else -> Icons.AutoMirrored.Filled.VolumeUp
            },
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
