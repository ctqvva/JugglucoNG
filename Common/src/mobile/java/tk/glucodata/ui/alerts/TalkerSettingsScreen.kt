@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package tk.glucodata.ui.alerts

import android.content.Context
import android.media.AudioAttributes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round
import tk.glucodata.MainActivity
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.SpeakSchedule
import tk.glucodata.Talker
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.components.cardShape
import tk.glucodata.ui.util.findActivity

private const val TALKER_SLIDER_MAX = 50000f
private const val TALKER_SLIDER_CENTER = 25000.0
private val TALKER_SLIDER_MULTIPLIER = 10000.0 / ln(2.0)

private data class TalkerUiState(
    val speakGlucose: Boolean,
    val talkTouch: Boolean,
    val speakMessages: Boolean,
    val speakAlarms: Boolean,
    val mediaSound: Boolean,
    val overrideSilent: Boolean,
    val separationSeconds: Int,
    val speed: Float,
    val pitch: Float,
    val selectedVoiceIndex: Int,
    val profile: Int
)

@Composable
fun TalkerSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context.findActivity() as? MainActivity ?: return
    val hostView = LocalView.current
    val focusManager = LocalFocusManager.current
    val profiles = remember(activity) { talkerProfileLabels(activity) }

    var uiState by remember(activity) { mutableStateOf(loadTalkerUiState(activity)) }
    var separationText by remember { mutableStateOf(uiState.separationSeconds.toString()) }
    var voiceNames by remember(activity) { mutableStateOf(Talker.getVoiceNames().toList()) }
    var previewingVoiceIndex by remember { mutableStateOf(-1) }
    var profileMenuExpanded by remember { mutableStateOf(false) }
    var voiceMenuExpanded by remember { mutableStateOf(false) }
    var scheduleEnabled by remember { mutableStateOf(SpeakSchedule.isEnabled(activity)) }
    var scheduleStart by remember { mutableStateOf(SpeakSchedule.getStartMinutes(activity)) }
    var scheduleEnd by remember { mutableStateOf(SpeakSchedule.getEndMinutes(activity)) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.separationSeconds) {
        separationText = uiState.separationSeconds.toString()
    }

    DisposableEffect(activity) {
        Talker.ensureComposeTalker(activity)
        val listener = Runnable {
            val loadedVoices = Talker.getVoiceNames().toList()
            voiceNames = loadedVoices
            val currentVoice = Talker.getSelectedVoiceIndex()
            if (loadedVoices.isNotEmpty()) {
                val normalized = currentVoice.coerceIn(0, loadedVoices.lastIndex)
                if (normalized != uiState.selectedVoiceIndex) {
                    uiState = uiState.copy(selectedVoiceIndex = normalized)
                }
            }
        }
        Talker.addVoiceOptionsListener(listener)
        listener.run()
        Talker.setPreviewDoneListener { previewingVoiceIndex = -1 }
        onDispose {
            Talker.removeVoiceOptionsListener(listener)
            Talker.setPreviewDoneListener(null)
            Talker.stopPreview()
            Talker.finishComposeSession()
        }
    }

    fun persist(updated: TalkerUiState) {
        uiState = updated
        Talker.applyComposeSettings(
            activity,
            updated.speakGlucose,
            updated.talkTouch,
            updated.speakMessages,
            updated.speakAlarms,
            updated.mediaSound,
            updated.overrideSilent,
            updated.speed,
            updated.pitch,
            updated.separationSeconds,
            updated.selectedVoiceIndex
        )
    }

    fun reloadFromStorage() {
        val refreshed = loadTalkerUiState(activity)
        uiState = refreshed
        voiceNames = Talker.getVoiceNames().toList()
        separationText = refreshed.separationSeconds.toString()
    }

    val selectedVoiceLabel = voiceNames.getOrNull(uiState.selectedVoiceIndex)
        ?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.defaultname)
    val enabledSpeechLabels = buildList {
        if (uiState.speakGlucose) add(context.getString(R.string.speakglucose))
        if (uiState.talkTouch) add(context.getString(R.string.talk_touch))
        if (uiState.speakMessages) add(context.getString(R.string.speakmessages))
        if (uiState.speakAlarms) add(context.getString(R.string.speakalarms))
    }
    val headlineSummary = enabledSpeechLabels.joinToString(" • ").ifEmpty { selectedVoiceLabel }
    val profileLabel = profiles.getOrElse(uiState.profile) { profiles.first() }

    if (showStartPicker) {
        ScheduleTimePickerDialog(
            initialHour = scheduleStart / 60,
            initialMinute = scheduleStart % 60,
            onDismiss = { showStartPicker = false },
            onConfirm = { h, m ->
                val mins = h * 60 + m
                scheduleStart = mins
                SpeakSchedule.setStartMinutes(activity, mins)
                showStartPicker = false
            }
        )
    }
    if (showEndPicker) {
        ScheduleTimePickerDialog(
            initialHour = scheduleEnd / 60,
            initialMinute = scheduleEnd % 60,
            onDismiss = { showEndPicker = false },
            onConfirm = { h, m ->
                val mins = h * 60 + m
                scheduleEnd = mins
                SpeakSchedule.setEndMinutes(activity, mins)
                showEndPicker = false
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.talker)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
//            TalkerSummaryCard(
//                headline = headlineSummary,
//                voiceLabel = selectedVoiceLabel,
//                profileLabel = profileLabel,
//                mediaEnabled = uiState.mediaSound
//            )

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsSwitchItem(
                    title = stringResource(R.string.speakglucose),
                    checked = uiState.speakGlucose,
                    onCheckedChange = { persist(uiState.copy(speakGlucose = it)) },
                    icon = Icons.Default.VolumeUp,
                    iconTint = MaterialTheme.colorScheme.primary,
                    position = CardPosition.TOP
                )
//                SettingsSwitchItem(
//                    title = stringResource(R.string.talk_touch),
//                    checked = uiState.talkTouch,
//                    onCheckedChange = { persist(uiState.copy(talkTouch = it)) },
//                    icon = Icons.Default.TouchApp,
//                    iconTint = MaterialTheme.colorScheme.secondary,
//                    position = CardPosition.MIDDLE
//                )
//                SettingsSwitchItem(
//                    title = stringResource(R.string.speakmessages),
//                    checked = uiState.speakMessages,
//                    onCheckedChange = { persist(uiState.copy(speakMessages = it)) },
//                    icon = Icons.Default.Message,
//                    iconTint = MaterialTheme.colorScheme.tertiary,
//                    position = CardPosition.MIDDLE
//                )
                SettingsSwitchItem(
                    title = stringResource(R.string.speakalarms),
                    checked = uiState.speakAlarms,
                    onCheckedChange = { persist(uiState.copy(speakAlarms = it)) },
                    icon = Icons.Default.NotificationsActive,
                    iconTint = MaterialTheme.colorScheme.error,
                    position = CardPosition.BOTTOM
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
//                DropdownSettingsCard(
//                    title = stringResource(R.string.profile).trim(),
//                    value = profileLabel,
//                    icon = Icons.Default.Tune,
//                    iconTint = MaterialTheme.colorScheme.primary,
//                    position = CardPosition.TOP,
//                    expanded = profileMenuExpanded,
//                    onExpandedChange = { profileMenuExpanded = it }
//                ) {
//                    profiles.forEachIndexed { index, label ->
//                        DropdownMenuItem(
//                            text = { Text(label) },
//                            onClick = {
//                                profileMenuExpanded = false
//                                focusManager.clearFocus(force = true)
//                                Talker.selectProfile(activity, index)
//                                reloadFromStorage()
//                            }
//                        )
//                    }
//                }

                DropdownSettingsCard(
                    title = stringResource(R.string.talker),
                    value = selectedVoiceLabel,
                    icon = Icons.Default.RecordVoiceOver,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    position = CardPosition.TOP,
                    enabled = voiceNames.isNotEmpty(),
                    expanded = voiceMenuExpanded,
                    onExpandedChange = { voiceMenuExpanded = it }
                ) {
                    voiceNames.forEachIndexed { index, label ->
                        val isPreviewing = previewingVoiceIndex == index
                        DropdownMenuItem(
                            text = { Text(label) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    if (isPreviewing) {
                                        Talker.stopPreview()
                                        previewingVoiceIndex = -1
                                    } else {
                                        previewingVoiceIndex = index
                                        Talker.previewVoice(index)
                                    }
                                }) {
                                    Icon(
                                        if (isPreviewing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = null
                                    )
                                }
                            },
                            onClick = {
                                voiceMenuExpanded = false
                                persist(uiState.copy(selectedVoiceIndex = index))
                            }
                        )
                    }
                }

                SettingsSwitchItem(
                    title = "Media",
                    checked = uiState.mediaSound,
                    onCheckedChange = { persist(uiState.copy(mediaSound = it, overrideSilent = false)) },
                    icon = Icons.Default.MusicNote,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    position = CardPosition.MIDDLE
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.override_silent_mode),
                    subtitle = stringResource(R.string.override_silent_mode_desc),
                    checked = uiState.overrideSilent,
                    onCheckedChange = { persist(uiState.copy(overrideSilent = it, mediaSound = false)) },
                    icon = Icons.Default.NotificationsActive,
                    iconTint = MaterialTheme.colorScheme.error,
                    position = CardPosition.BOTTOM
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                NumericInputCard(
                    title = stringResource(R.string.secondsbetween),
                    value = separationText,
                    icon = Icons.Default.Schedule,
                    iconTint = MaterialTheme.colorScheme.primary,
                    position = CardPosition.TOP,
                    onValueChange = { input ->
                        val digitsOnly = input.filter { it.isDigit() }.take(3)
                        separationText = digitsOnly
                        val parsed = digitsOnly.toIntOrNull()
                        if (parsed != null && parsed > 0) {
                            persist(uiState.copy(separationSeconds = parsed))
                        }
                    },
                    onDone = {
                        focusManager.clearFocus(force = true)
                        val parsed = separationText.toIntOrNull()
                        separationText = (parsed?.takeIf { it > 0 } ?: uiState.separationSeconds).toString()
                    }
                )

                SliderCard(
                    title = stringResource(R.string.speed),
                    valueLabel = formatTalkerRatio(uiState.speed),
                    sliderValue = talkerRatioToSliderProgress(uiState.speed),
                    icon = Icons.Default.GraphicEq,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    position = CardPosition.MIDDLE,
                    onValueChange = { uiState = uiState.copy(speed = talkerSliderProgressToRatio(it)) },
                    onValueChangeFinished = { persist(uiState) }
                )

                SliderCard(
                    title = stringResource(R.string.pitch),
                    valueLabel = formatTalkerRatio(uiState.pitch),
                    sliderValue = talkerRatioToSliderProgress(uiState.pitch),
                    icon = Icons.Default.Tune,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    position = CardPosition.BOTTOM,
                    onValueChange = { uiState = uiState.copy(pitch = talkerSliderProgressToRatio(it)) },
                    onValueChangeFinished = { persist(uiState) }
                )
            }
//
//            FlowRow(
//                modifier = Modifier.fillMaxWidth(),
//                horizontalArrangement = Arrangement.spacedBy(12.dp),
//                verticalArrangement = Arrangement.spacedBy(12.dp)
//            ) {
//                OutlinedButton(
//                    onClick = { tk.glucodata.help.help(R.string.talkhelp, activity) },
//                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
//                ) {
//                    Icon(Icons.Default.HelpOutline, contentDescription = null)
//                    Spacer(Modifier.size(8.dp))
//                    Text(stringResource(R.string.helpname))
//                }
//                OutlinedButton(
//                    onClick = {
//                        focusManager.clearFocus(force = true)
//                        tk.glucodata.settings.Settings.scheduleProfiles(activity, hostView)
//                    },
//                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
//                ) {
//                    Icon(Icons.Default.Schedule, contentDescription = null)
//                    Spacer(Modifier.size(8.dp))
//                    Text(stringResource(R.string.schedules))
//                }
//            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsSwitchItem(
                    title = stringResource(R.string.voice_schedule_title),
                    subtitle = stringResource(R.string.voice_schedule_desc),
                    checked = scheduleEnabled,
                    onCheckedChange = {
                        scheduleEnabled = it
                        SpeakSchedule.setEnabled(activity, it)
                    },
                    icon = Icons.Default.Schedule,
                    iconTint = MaterialTheme.colorScheme.primary,
                    position = if (scheduleEnabled) CardPosition.TOP else CardPosition.SINGLE
                )
                if (scheduleEnabled) {
                    SettingsItem(
                        title = stringResource(R.string.voice_schedule_from),
                        subtitle = SpeakSchedule.formatMinutes(scheduleStart),
                        icon = Icons.Default.Schedule,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        position = CardPosition.MIDDLE,
                        onClick = { showStartPicker = true }
                    )
                    SettingsItem(
                        title = stringResource(R.string.voice_schedule_until),
                        subtitle = SpeakSchedule.formatMinutes(scheduleEnd),
                        icon = Icons.Default.Schedule,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        position = CardPosition.BOTTOM,
                        onClick = { showEndPicker = true }
                    )
                }
            }

            FilledTonalButton(
                onClick = {
                    persist(uiState)
                    Talker.testCurrentValue(activity)
                },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.test))
            }
        }
    }
}

@Composable
private fun TalkerSummaryCard(
    headline: String,
    voiceLabel: String,
    profileLabel: String,
    mediaEnabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        ),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                Spacer(Modifier.size(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.talker),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = voiceLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryTag(profileLabel)
                if (mediaEnabled) {
                    SummaryTag("MEDIA")
                }
            }
        }
    }
}

@Composable
private fun SummaryTag(text: String) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun DropdownSettingsCard(
    title: String,
    value: String,
    icon: ImageVector,
    iconTint: Color,
    position: CardPosition,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Box {
        SettingsItem(
            title = title,
            subtitle = value,
            onClick = if (enabled) ({ onExpandedChange(true) }) else null,
            icon = icon,
            iconTint = iconTint,
            position = position
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            content()
        }
    }
}

@Composable
private fun NumericInputCard(
    title: String,
    value: String,
    icon: ImageVector,
    iconTint: Color,
    position: CardPosition,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape(position),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.medium,
                color = iconTint.copy(alpha = 0.12f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = iconTint)
                }
            }

            Spacer(Modifier.size(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { onDone() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun SliderCard(
    title: String,
    valueLabel: String,
    sliderValue: Float,
    icon: ImageVector,
    iconTint: Color,
    position: CardPosition,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape(position),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = iconTint.copy(alpha = 0.12f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = iconTint)
                    }
                }

                Spacer(Modifier.size(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        valueLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Slider(
                value = sliderValue,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = 0f..TALKER_SLIDER_MAX
            )
        }
    }
}

private fun loadTalkerUiState(context: Context): TalkerUiState {
    Talker.ensureComposeTalker(context)
    val soundType = Natives.getSoundType()
    return TalkerUiState(
        speakGlucose = Natives.getVoiceActive(),
        talkTouch = Natives.gettouchtalk(),
        speakMessages = Natives.speakmessages(),
        speakAlarms = Natives.speakalarms(),
        mediaSound = soundType == AudioAttributes.USAGE_MEDIA,
        overrideSilent = soundType == AudioAttributes.USAGE_ALARM,
        separationSeconds = Talker.getSeparationSeconds().coerceAtLeast(1),
        speed = Talker.getSelectedSpeed().coerceAtLeast(0.18f),
        pitch = Talker.getSelectedPitch().coerceAtLeast(0.18f),
        selectedVoiceIndex = Talker.getSelectedVoiceIndex().coerceAtLeast(0),
        profile = Natives.getProfile()
    )
}

private fun talkerProfileLabels(context: Context): List<String> {
    val profilePrefix = context.getString(R.string.profile).trim()
    return listOf(
        context.getString(R.string.defaultname),
        "$profilePrefix 1",
        "$profilePrefix 2",
        "$profilePrefix 3",
        "$profilePrefix 4",
        "$profilePrefix 5"
    )
}

private fun talkerRatioToSliderProgress(ratio: Float): Float {
    if (ratio < 0.18f) return 0f
    return (round(ln(ratio.toDouble()) * TALKER_SLIDER_MULTIPLIER) + TALKER_SLIDER_CENTER)
        .toFloat()
        .coerceIn(0f, TALKER_SLIDER_MAX)
}

private fun talkerSliderProgressToRatio(progress: Float): Float {
    return exp((progress - TALKER_SLIDER_CENTER) / TALKER_SLIDER_MULTIPLIER).toFloat()
}

private fun formatTalkerRatio(value: Float): String {
    return String.format(Locale.US, "%.2f", value)
}

@Composable
private fun ScheduleTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit
) {
    val state = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        text = { TimePicker(state = state) }
    )
}
