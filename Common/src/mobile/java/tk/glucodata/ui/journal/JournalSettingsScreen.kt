@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui.journal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import tk.glucodata.R
import tk.glucodata.data.journal.JournalBuiltInCurveProfile
import tk.glucodata.data.journal.JournalCurvePoint
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalInsulinPresetInput
import tk.glucodata.data.journal.builtInJournalCurve
import tk.glucodata.data.journal.normalizeJournalCurvePoints
import tk.glucodata.data.journal.serializeJournalCurve
import tk.glucodata.ui.alerts.AddCustomAlertButton
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.StyledSwitch
import tk.glucodata.ui.components.cardShape
import tk.glucodata.ui.viewmodel.DashboardViewModel

private data class JournalPresetDraft(
    val id: Long? = null,
    val displayName: String = "",
    val accentColor: Int = DEFAULT_PRESET_COLOR,
    val curvePoints: List<JournalCurvePoint> = emptyList(),
    val sortOrder: Int = Int.MAX_VALUE,
    val isBuiltIn: Boolean = false,
    val isArchived: Boolean = false,
    val countsTowardIob: Boolean = true
)

@Composable
fun JournalSettingsScreen(
    navController: NavController,
    viewModel: DashboardViewModel
) {
    val journalEnabled by viewModel.journalEnabled.collectAsState()
    val journalDoseCalculatorEnabled by viewModel.journalDoseCalculatorEnabled.collectAsState()
    val allPresets by viewModel.journalInsulinPresets.collectAsState()
    val activePresets = remember(allPresets) { allPresets.filter { !it.isArchived } }
    val archivedPresets = remember(allPresets) { allPresets.filter { it.isArchived } }
    var editingPreset by remember { mutableStateOf<JournalInsulinPreset?>(null) }
    var creatingPreset by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.journal_manage_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "gate") {
                MasterSwitchCard(
                    title = stringResource(R.string.journal_title),
                    subtitle = stringResource(R.string.journal_gate_desc),
                    checked = journalEnabled,
                    onCheckedChange = { viewModel.setJournalEnabled(it) },
                    icon = Icons.Default.Vaccines,
                    iconTint = MaterialTheme.colorScheme.tertiary
                )
            }

            item(key = "open_journal") {
                FilledTonalButton(
                    onClick = { navController.navigate("history") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.historyname))
                }
            }

            item(key = "dose_calculator") {
                JournalCompactSwitchRow(
                    title = stringResource(R.string.journal_dose_math_title),
                    subtitle = stringResource(R.string.journal_dose_calculator_desc),
                    checked = journalDoseCalculatorEnabled,
                    onCheckedChange = { viewModel.setJournalDoseCalculatorEnabled(it) },
                    icon = Icons.Default.Calculate
                )
            }

            item(key = "import_health_activity") {
                FilledTonalButton(
                    onClick = { viewModel.importHealthConnectActivity(daysBack = 14) },
                    enabled = journalEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsRun,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.journal_import_health_activity))
                }
            }

            item(key = "active_label") {
                SectionLabel(
                    text = stringResource(R.string.journal_insulin_library),
                    topPadding = 2.dp
                )
            }

            if (activePresets.isNotEmpty()) {
                item(key = "active_group") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        activePresets.forEachIndexed { index, preset ->
                            JournalPresetRow(
                                preset = preset,
                                position = cardPosition(index, activePresets.size),
                                onClick = { editingPreset = preset }
                            )
                        }
                    }
                }
            }

            item(key = "add_preset_button") {
                AddCustomAlertButton(
                    text = stringResource(R.string.journal_add_preset),
                    onClick = { creatingPreset = true }
                )
            }

            if (archivedPresets.isNotEmpty()) {
                item(key = "archived_label") {
                    SectionLabel(
                        text = stringResource(R.string.journal_archived_presets),
                        topPadding = 4.dp
                    )
                }
                item(key = "archived_group") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        archivedPresets.forEachIndexed { index, preset ->
                            JournalPresetRow(
                                preset = preset,
                                position = cardPosition(index, archivedPresets.size),
                                onClick = { editingPreset = preset }
                            )
                        }
                    }
                }
            }
        }
    }

    if (creatingPreset) {
        JournalInsulinPresetSheet(
            preset = null,
            onDismiss = { creatingPreset = false },
            onSave = {
                viewModel.saveJournalInsulinPreset(it)
                creatingPreset = false
            },
            onDelete = null
        )
    }

    editingPreset?.let { preset ->
        JournalInsulinPresetSheet(
            preset = preset,
            onDismiss = { editingPreset = null },
            onSave = {
                viewModel.saveJournalInsulinPreset(it)
                editingPreset = null
            },
            onDelete = if (preset.isBuiltIn) {
                null
            } else {
                {
                    viewModel.deleteJournalInsulinPreset(preset.id)
                    editingPreset = null
                }
            }
        )
    }
}

@Composable
private fun JournalPresetRow(
    preset: JournalInsulinPreset,
    position: CardPosition,
    onClick: () -> Unit
) {
    val isDisabled = preset.isArchived
    val previewColor = if (isDisabled) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
    } else {
        Color(preset.accentColor)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        color = if (isDisabled) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = cardShape(position)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (isDisabled) 0.6f else 1f)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = preset.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isDisabled) {
                        Text(
                            text = stringResource(R.string.disabled_status),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = buildString {
                        append(
                            stringResource(
                                R.string.journal_active_window,
                                stringResource(R.string.minutes_short_format, preset.onsetMinutes),
                                stringResource(R.string.minutes_short_format, preset.durationMinutes)
                            )
                        )
                        if (preset.countsTowardIob) {
                            append(" · ")
                            append(stringResource(R.string.journal_active_insulin))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            JournalCurvePreview(
                points = preset.curvePoints,
                color = previewColor,
                modifier = Modifier
                    .width(88.dp)
                    .height(42.dp)
            )
        }
    }
}

@Composable
private fun JournalInsulinPresetSheet(
    preset: JournalInsulinPreset?,
    onDismiss: () -> Unit,
    onSave: (JournalInsulinPresetInput) -> Unit,
    onDelete: (() -> Unit)?
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initialDraft = remember(preset?.id) { buildPresetDraft(preset) }
    var draft by remember(preset?.id) { mutableStateOf(initialDraft) }
    var selectedPointIndex by rememberSaveable(preset?.id) {
        mutableIntStateOf(defaultSelectedPointIndex(draft.curvePoints))
    }
    var showColorDialog by rememberSaveable(preset?.id) { mutableStateOf(false) }

    LaunchedEffect(draft.curvePoints.size) {
        selectedPointIndex = selectedPointIndex.coerceIn(0, draft.curvePoints.lastIndex)
    }

    val selectedPoint = draft.curvePoints.getOrNull(selectedPointIndex)
    val canSave = draft.displayName.trim().isNotBlank() && draft.curvePoints.size >= 3
    val hasChanges = draft != initialDraft
    val usesExplicitActions = draft.id == null || !draft.isBuiltIn
    val latestDraft by rememberUpdatedState(draft)
    val latestInitialDraft by rememberUpdatedState(initialDraft)
    val latestCanSave by rememberUpdatedState(canSave)
    val latestUsesExplicitActions by rememberUpdatedState(usesExplicitActions)
    val latestOnSave by rememberUpdatedState(onSave)
    var dismissalHandled by remember(preset?.id) { mutableStateOf(false) }
    fun saveBuiltInDraftIfNeeded(): Boolean {
        if (latestUsesExplicitActions || !latestCanSave || latestDraft == latestInitialDraft) {
            return false
        }
        val input = buildPresetInput(latestDraft) ?: return false
        latestOnSave(input)
        return true
    }
    fun dismissSheet() {
        dismissalHandled = true
        if (!saveBuiltInDraftIfNeeded()) {
            onDismiss()
        }
    }
    fun saveDraft() {
        val input = buildPresetInput(draft) ?: return
        dismissalHandled = true
        onSave(input)
    }
    fun toggleArchivedAndPersist() {
        val updated = draft.copy(isArchived = !draft.isArchived)
        draft = updated
        buildPresetInput(updated)?.let { input ->
            dismissalHandled = true
            onSave(input)
        }
    }
    DisposableEffect(preset?.id) {
        onDispose {
            if (!dismissalHandled) {
                saveBuiltInDraftIfNeeded()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { dismissSheet() },
        sheetState = sheetState,
        dragHandle = {
            Surface(
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(2.dp)
            ) {
                Box(modifier = Modifier.size(width = 32.dp, height = 4.dp))
            }
        }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(
                            if (preset == null) R.string.journal_add_preset else R.string.journal_edit_preset
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (preset != null) {
                        val archived = draft.isArchived
                        val containerColor = if (archived) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        }
                        val contentColor = if (archived) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                        FilledTonalButton(
                            onClick = { toggleArchivedAndPersist() },
                            modifier = Modifier.height(40.dp),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = containerColor,
                                contentColor = contentColor
                            )
                        ) {
                            Icon(
                                imageVector = if (archived) Icons.Default.CheckCircle else Icons.Default.Block,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = stringResource(
                                    if (archived) R.string.enable else R.string.disable
                                ),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            item(key = "meta_card") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (draft.isArchived) {
                                Modifier.background(
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(22.dp)
                                )
                            } else {
                                Modifier
                            }
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val cardSidePadding = if (draft.isArchived) 12.dp else 0.dp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = cardSidePadding,
                                end = cardSidePadding,
                                top = if (draft.isArchived) 12.dp else 0.dp
                            ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = draft.displayName,
                            onValueChange = { draft = draft.copy(displayName = it) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        FilledTonalIconButton(
                            onClick = { showColorDialog = true },
                            modifier = Modifier.size(56.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        ) {
                            Surface(
                                modifier = Modifier.size(20.dp),
                                shape = CircleShape,
                                color = Color(draft.accentColor)
                            ) {}
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(horizontal = cardSidePadding)
                    )
                    CompactPresetToggleRow(
                        title = stringResource(R.string.journal_active_insulin),
                        subtitle = stringResource(R.string.journal_active_insulin_subtitle),
                        checked = draft.countsTowardIob,
                        enabled = !draft.isArchived,
                        onCheckedChange = { draft = draft.copy(countsTowardIob = it) },
                        contentPadding = PaddingValues(
                            start = cardSidePadding,
                            end = cardSidePadding,
                            bottom = if (draft.isArchived) 8.dp else 0.dp
                        )
                    )
                }
            }

            item(key = "editor") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (draft.isArchived) {
                                Modifier
                                    .background(
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.05f),
                                        shape = RoundedCornerShape(22.dp)
                                    )
                                    .padding(12.dp)
                            } else {
                                Modifier
                            }
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val resetProfile = remember(draft.isBuiltIn, draft.sortOrder) {
                        if (draft.isBuiltIn) defaultBuiltInProfile(draft.sortOrder) else null
                    }
                    val curveDiffersFromDefault = remember(draft.curvePoints, resetProfile) {
                        resetProfile != null &&
                            serializeJournalCurve(draft.curvePoints) !=
                            serializeJournalCurve(builtInJournalCurve(resetProfile))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.journal_curve_preview),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (resetProfile != null && curveDiffersFromDefault) {
                            TextButton(
                                onClick = {
                                    val defaultCurve = builtInJournalCurve(resetProfile)
                                    draft = draft.copy(curvePoints = defaultCurve)
                                    selectedPointIndex = defaultSelectedPointIndex(defaultCurve)
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Restore,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.journal_curve_reset),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = curveWindowSummary(draft.curvePoints),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    InteractiveJournalCurveEditor(
                        points = draft.curvePoints,
                        selectedPointIndex = selectedPointIndex,
                        color = Color(draft.accentColor),
                        onSelectedPointChange = { selectedPointIndex = it },
                        onPointChange = { index, minute, activity ->
                            draft = draft.copy(
                                curvePoints = updateCurvePoint(
                                    points = draft.curvePoints,
                                    index = index,
                                    minute = minute,
                                    activity = activity
                                )
                            )
                        }
                    )
                    selectedPoint?.let { point ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        SelectedCurvePointEditor(
                            index = selectedPointIndex,
                            point = point,
                            canEditMinute = selectedPointIndex != 0,
                            canEditActivity = selectedPointIndex in 1 until draft.curvePoints.lastIndex,
                            canDelete = canDeleteCurvePoint(draft.curvePoints, selectedPointIndex),
                            onMinuteChange = { minute ->
                                draft = draft.copy(
                                    curvePoints = updateCurvePoint(
                                        points = draft.curvePoints,
                                        index = selectedPointIndex,
                                        minute = minute,
                                        activity = point.activity
                                    )
                                )
                            },
                            onActivityChange = { activity ->
                                draft = draft.copy(
                                    curvePoints = updateCurvePoint(
                                        points = draft.curvePoints,
                                        index = selectedPointIndex,
                                        minute = point.minute,
                                        activity = activity
                                    )
                                )
                            },
                            onDelete = {
                                draft = draft.copy(
                                    curvePoints = deleteCurvePoint(draft.curvePoints, selectedPointIndex)
                                )
                                selectedPointIndex = (selectedPointIndex - 1).coerceAtLeast(1)
                            }
                        )
                    }
                }
            }

            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            val updatedPoints = insertCurvePoint(draft.curvePoints, selectedPointIndex)
                            val insertedIndex = findInsertedPointIndex(draft.curvePoints, updatedPoints, selectedPointIndex)
                            draft = draft.copy(curvePoints = updatedPoints)
                            selectedPointIndex = insertedIndex
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.journal_curve_add_point))
                    }
                    Button(
                        onClick = { saveDraft() },
                        enabled = canSave && hasChanges,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.save))
                    }
                    if (usesExplicitActions) {
                        onDelete?.let {
                            OutlinedButton(
                                onClick = it,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(R.string.delete),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showColorDialog) {
        PresetColorDialog(
            initialColor = draft.accentColor,
            onDismiss = { showColorDialog = false },
            onConfirm = {
                draft = draft.copy(accentColor = it)
                showColorDialog = false
            }
        )
    }
}

@Composable
private fun JournalCompactSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (checked) 0.86f else 0.42f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (checked) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StyledSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun CompactPresetToggleRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(contentPadding)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        StyledSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun SelectedCurvePointEditor(
    index: Int,
    point: JournalCurvePoint,
    canEditMinute: Boolean,
    canEditActivity: Boolean,
    canDelete: Boolean,
    onMinuteChange: (Int) -> Unit,
    onActivityChange: (Float) -> Unit,
    onDelete: () -> Unit
) {
    var minuteText by remember(index, point.minute) { mutableStateOf(point.minute.toString()) }
    var activityText by remember(index, point.activity) {
        mutableStateOf((point.activity * 100f).roundToInt().toString())
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.journal_curve_point, index + 1),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (canDelete) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete)
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = minuteText,
                onValueChange = { updated ->
                    minuteText = updated.filter(Char::isDigit)
                    minuteText.toIntOrNull()?.let(onMinuteChange)
                },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.journal_curve_minutes)) },
                singleLine = true,
                enabled = canEditMinute,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                suffix = { Text(stringResource(R.string.minutes)) }
            )
            OutlinedTextField(
                value = activityText,
                onValueChange = { updated ->
                    activityText = updated.filter { it.isDigit() || it == ',' || it == '.' }
                    activityText.replace(',', '.').toFloatOrNull()?.let { percent ->
                        onActivityChange((percent / 100f).coerceIn(0f, 1f))
                    }
                },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.journal_curve_activity)) },
                singleLine = true,
                enabled = canEditActivity,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                suffix = { Text("%") }
            )
        }
    }
}

@Composable
private fun InteractiveJournalCurveEditor(
    points: List<JournalCurvePoint>,
    selectedPointIndex: Int,
    color: Color,
    onSelectedPointChange: (Int) -> Unit,
    onPointChange: (Int, Int, Float) -> Unit
) {
    var chartSize by remember { mutableStateOf(IntSize.Zero) }
    val guideColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)
    val selectionHaloColor = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current
    val chartPadding = with(density) { 18.dp.toPx() }
    val chartTopInset = with(density) { 12.dp.toPx() }
    val chartBottomInset = with(density) { 12.dp.toPx() }

    val pointsLatest = rememberUpdatedState(points)
    val chartSizeLatest = rememberUpdatedState(chartSize)
    val onSelectedPointChangeLatest = rememberUpdatedState(onSelectedPointChange)
    val onPointChangeLatest = rememberUpdatedState(onPointChange)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(164.dp)
            .onSizeChanged { chartSize = it }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    nearestCurvePointIndex(
                        points = pointsLatest.value,
                        chartSize = chartSizeLatest.value,
                        touch = offset,
                        horizontalPaddingPx = chartPadding,
                        topInsetPx = chartTopInset,
                        bottomInsetPx = chartBottomInset
                    )?.let { onSelectedPointChangeLatest.value(it) }
                }
            }
            .pointerInput(Unit) {
                var draggingPointIndex: Int? = null
                detectDragGestures(
                    onDragStart = { offset ->
                        draggingPointIndex = nearestCurvePointIndex(
                            points = pointsLatest.value,
                            chartSize = chartSizeLatest.value,
                            touch = offset,
                            horizontalPaddingPx = chartPadding,
                            topInsetPx = chartTopInset,
                            bottomInsetPx = chartBottomInset
                        )?.also { onSelectedPointChangeLatest.value(it) }
                    },
                    onDragEnd = { draggingPointIndex = null },
                    onDragCancel = { draggingPointIndex = null },
                    onDrag = { change, _ ->
                        val index = draggingPointIndex ?: return@detectDragGestures
                        change.consume()
                        val updatedPoint = pointFromTouch(
                            points = pointsLatest.value,
                            index = index,
                            chartSize = chartSizeLatest.value,
                            touch = change.position,
                            horizontalPaddingPx = chartPadding,
                            topInsetPx = chartTopInset,
                            bottomInsetPx = chartBottomInset
                        )
                        onPointChangeLatest.value(index, updatedPoint.minute, updatedPoint.activity)
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (points.size < 2 || chartSize.width == 0 || chartSize.height == 0) return@Canvas

            val bounds = curveBounds(
                width = size.width,
                height = size.height,
                horizontalPaddingPx = chartPadding,
                topInsetPx = chartTopInset,
                bottomInsetPx = chartBottomInset
            )
            val maxMinute = points.last().minute.coerceAtLeast(1).toFloat()

            repeat(4) { gridIndex ->
                val y = bounds.bottom - ((bounds.height / 3f) * gridIndex)
                drawLine(
                    color = guideColor,
                    start = Offset(bounds.left, y),
                    end = Offset(bounds.right, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            repeat(4) { gridIndex ->
                val x = bounds.left + ((bounds.width / 3f) * gridIndex)
                drawLine(
                    color = guideColor,
                    start = Offset(x, bounds.top),
                    end = Offset(x, bounds.bottom),
                    strokeWidth = 1.dp.toPx()
                )
            }

            val offsets = points.map { it.toOffset(bounds, maxMinute) }
            fun Path.addSmoothedOffsets(samples: List<Offset>, moveToFirst: Boolean) {
                if (samples.isEmpty()) return
                val first = samples.first()
                if (moveToFirst) {
                    moveTo(first.x, first.y)
                } else {
                    lineTo(first.x, first.y)
                }
                if (samples.size == 1) return
                if (samples.size == 2) {
                    val last = samples.last()
                    lineTo(last.x, last.y)
                    return
                }
                for (index in 1 until samples.lastIndex) {
                    val current = samples[index]
                    val next = samples[index + 1]
                    val midX = (current.x + next.x) * 0.5f
                    val midY = (current.y + next.y) * 0.5f
                    quadraticTo(current.x, current.y, midX, midY)
                }
                val last = samples.last()
                lineTo(last.x, last.y)
            }

            val curvePath = Path().apply {
                addSmoothedOffsets(offsets, moveToFirst = true)
            }
            val fillPath = Path().apply {
                moveTo(offsets.first().x, bounds.bottom)
                addSmoothedOffsets(offsets, moveToFirst = false)
                lineTo(offsets.last().x, bounds.bottom)
                close()
            }

            drawPath(path = fillPath, color = color.copy(alpha = 0.14f))
            drawPath(
                path = curvePath,
                color = color,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            points.forEachIndexed { index, point ->
                val offset = point.toOffset(bounds, maxMinute)
                val isSelected = index == selectedPointIndex
                if (isSelected) {
                    drawCircle(
                        color = selectionHaloColor,
                        radius = 8.dp.toPx(),
                        center = offset
                    )
                }
                drawCircle(
                    color = if (index == 0 || index == points.lastIndex) {
                        color.copy(alpha = 0.7f)
                    } else {
                        color
                    },
                    radius = if (isSelected) 6.dp.toPx() else 4.5.dp.toPx(),
                    center = offset
                )
            }
        }
    }
}

@Composable
private fun PresetColorDialog(
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var colorState by remember(initialColor) { mutableStateOf(initialColor.toPresetColorState()) }
    var colorText by remember(initialColor) { mutableStateOf(formatColorHex(initialColor)) }
    val composedColor = remember(colorState) { colorState.toColorInt() }
    val parsedColor = remember(colorText) { parseColorHex(colorText) }

    LaunchedEffect(colorState) {
        val resolvedHex = formatColorHex(composedColor)
        if (colorText != resolvedHex) {
            colorText = resolvedHex
        }
    }

    LaunchedEffect(parsedColor) {
        parsedColor?.let { parsed ->
            val parsedState = parsed.toPresetColorState()
            if (parsedState != colorState) {
                colorState = parsedState
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.colors)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(
                    modifier = Modifier
                        .size(60.dp)
                        .align(Alignment.CenterHorizontally),
                    shape = CircleShape,
                    color = Color(composedColor)
                ) {}
                HueWheelPicker(
                    hue = colorState.hue,
                    onHueChange = { hue -> colorState = colorState.copy(hue = hue) }
                )
                ColorControlRow(icon = Icons.Default.Palette) {
                    Slider(
                        value = colorState.saturation,
                        onValueChange = { saturation ->
                            colorState = colorState.copy(saturation = saturation.coerceIn(0f, 1f))
                        }
                    )
                }
                ColorControlRow(
                    indicator = {
                        Text(
                            text = "${(colorState.alpha * 100f).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                ) {
                    Slider(
                        value = colorState.alpha,
                        onValueChange = { alpha ->
                            colorState = colorState.copy(alpha = alpha.coerceIn(0f, 1f))
                        }
                    )
                }
                OutlinedTextField(
                    value = colorText,
                    onValueChange = { colorText = it.trim() },
                    label = { Text(text = stringResource(R.string.colors)) },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = null
                        )
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(composedColor) },
                enabled = parsedColor != null
            ) {
                Text(text = stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}

private data class PresetColorState(
    val hue: Float,
    val saturation: Float,
    val value: Float,
    val alpha: Float
)

private fun Int.toPresetColorState(): PresetColorState {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this, hsv)
    val alpha = ((this ushr 24) and 0xFF) / 255f
    return PresetColorState(
        hue = hsv[0],
        saturation = hsv[1],
        value = hsv[2].coerceAtLeast(0.65f),
        alpha = alpha.coerceIn(0f, 1f)
    )
}

private fun PresetColorState.toColorInt(): Int {
    return android.graphics.Color.HSVToColor(
        (alpha * 255f).roundToInt().coerceIn(0, 255),
        floatArrayOf(hue, saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
    )
}

@Composable
private fun ColorControlRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    indicator: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Box(
                modifier = Modifier.width(24.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                indicator?.invoke()
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

@Composable
private fun HueWheelPicker(
    hue: Float,
    onHueChange: (Float) -> Unit
) {
    val sweepColors = remember {
        listOf(
            Color(0xFFFF1744),
            Color(0xFFFF9100),
            Color(0xFFFFEA00),
            Color(0xFF00E676),
            Color(0xFF00B0FF),
            Color(0xFF651FFF),
            Color(0xFFFF1744)
        )
    }
    val handleHaloColor = MaterialTheme.colorScheme.surface

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(176.dp)
            .pointerInput(Unit) {
                fun updateHue(offset: Offset) {
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val angle = Math.toDegrees(
                        atan2((offset.y - centerY).toDouble(), (offset.x - centerX).toDouble())
                    ).toFloat()
                    onHueChange(((angle + 450f) % 360f))
                }

                detectTapGestures(onTap = ::updateHue)
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val angle = Math.toDegrees(
                        atan2((change.position.y - centerY).toDouble(), (change.position.x - centerX).toDouble())
                    ).toFloat()
                    onHueChange(((angle + 450f) % 360f))
                }
            }
    ) {
        val ringWidth = 22.dp.toPx()
        val radius = (size.minDimension / 2f) - ringWidth
        drawCircle(
            brush = Brush.sweepGradient(sweepColors),
            radius = radius,
            style = Stroke(width = ringWidth, cap = StrokeCap.Round)
        )

        val angleRadians = Math.toRadians((hue - 90f).toDouble())
        val handleCenter = Offset(
            x = center.x + (cos(angleRadians) * radius).toFloat(),
            y = center.y + (sin(angleRadians) * radius).toFloat()
        )
        drawCircle(
            color = handleHaloColor,
            radius = 12.dp.toPx(),
            center = handleCenter
        )
        drawCircle(
            color = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))),
            radius = 8.dp.toPx(),
            center = handleCenter
        )
    }
}

@Composable
private fun JournalCurvePreview(
    points: List<JournalCurvePoint>,
    color: Color,
    modifier: Modifier = Modifier
) {
    val normalizedPoints = remember(points) { normalizeJournalCurvePoints(points) }
    val guideColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
        ) {
            if (normalizedPoints.size < 2) return@Canvas
            val maxMinute = normalizedPoints.last().minute.coerceAtLeast(1).toFloat()
            val baseline = size.height - 4.dp.toPx()
            val curveHeight = (size.height - 14.dp.toPx()).coerceAtLeast(10.dp.toPx())

            repeat(3) { index ->
                val y = baseline - ((curveHeight / 2f) * index)
                drawLine(
                    color = guideColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            val path = Path()
            val fillPath = Path()
            normalizedPoints.forEachIndexed { index, point ->
                val x = (point.minute / maxMinute) * size.width
                val y = baseline - (point.activity.coerceIn(0f, 1f) * curveHeight)
                if (index == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, baseline)
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo(size.width, baseline)
            fillPath.close()

            drawPath(path = fillPath, color = color.copy(alpha = 0.14f))
            drawPath(path = path, color = color, style = Stroke(width = 2.2.dp.toPx()))
        }
    }
}

private fun buildPresetDraft(preset: JournalInsulinPreset?): JournalPresetDraft {
    val sourcePoints = preset?.curvePoints ?: builtInJournalCurve(JournalBuiltInCurveProfile.RAPID_GENERIC)
    return JournalPresetDraft(
        id = preset?.id,
        displayName = preset?.displayName.orEmpty(),
        accentColor = preset?.accentColor ?: DEFAULT_PRESET_COLOR,
        curvePoints = normalizeJournalCurvePoints(sourcePoints),
        sortOrder = preset?.sortOrder ?: Int.MAX_VALUE,
        isBuiltIn = preset?.isBuiltIn ?: false,
        isArchived = preset?.isArchived ?: false,
        countsTowardIob = preset?.countsTowardIob ?: true
    )
}

private fun buildPresetInput(
    draft: JournalPresetDraft,
    overrideArchived: Boolean = draft.isArchived
): JournalInsulinPresetInput? {
    val normalizedCurve = normalizeJournalCurvePoints(draft.curvePoints)
    if (draft.displayName.trim().isBlank() || normalizedCurve.size < 3) return null
    val onset = normalizedCurve.firstOrNull { it.activity > 0.01f }?.minute ?: 0
    val duration = normalizedCurve.lastOrNull()?.minute ?: 0
    return JournalInsulinPresetInput(
        id = draft.id,
        displayName = draft.displayName.trim(),
        onsetMinutes = onset,
        durationMinutes = duration,
        accentColor = draft.accentColor,
        curveJson = serializeJournalCurve(normalizedCurve),
        isBuiltIn = draft.isBuiltIn,
        isArchived = overrideArchived,
        countsTowardIob = draft.countsTowardIob,
        sortOrder = draft.sortOrder
    )
}

private fun updateCurvePoint(
    points: List<JournalCurvePoint>,
    index: Int,
    minute: Int,
    activity: Float
): List<JournalCurvePoint> {
    if (points.isEmpty()) return points
    val updated = points.toMutableList()
    val lastIndex = updated.lastIndex
    val previousMinute = updated.getOrNull(index - 1)?.minute?.plus(1) ?: 0
    val nextMinute = updated.getOrNull(index + 1)?.minute?.minus(1) ?: Int.MAX_VALUE

    val resolvedMinute = when (index) {
        0 -> 0
        lastIndex -> minute.coerceAtLeast(previousMinute)
        else -> minute.coerceIn(previousMinute, nextMinute.coerceAtLeast(previousMinute))
    }
    val resolvedActivity = if (index == 0 || index == lastIndex) {
        0f
    } else {
        activity.coerceIn(0f, 1f)
    }

    updated[index] = JournalCurvePoint(
        minute = resolvedMinute,
        activity = resolvedActivity
    )
    return normalizeJournalCurvePoints(updated, fallbackDurationMinutes = updated.last().minute.coerceAtLeast(120))
}

private fun insertCurvePoint(
    points: List<JournalCurvePoint>,
    selectedIndex: Int
): List<JournalCurvePoint> {
    if (points.size < 2) return points
    val insertionBaseIndex = when {
        selectedIndex <= 0 -> 0
        selectedIndex >= points.lastIndex -> points.lastIndex - 1
        else -> selectedIndex
    }
    val left = points[insertionBaseIndex]
    val right = points[insertionBaseIndex + 1]
    val minuteGap = (right.minute - left.minute).coerceAtLeast(2)
    val insertedPoint = JournalCurvePoint(
        minute = left.minute + (minuteGap / 2),
        activity = when {
            left.activity == 0f && right.activity == 0f -> 0.35f
            else -> ((left.activity + right.activity) / 2f).coerceIn(0.1f, 0.9f)
        }
    )
    val updated = points.toMutableList().also { it.add(insertionBaseIndex + 1, insertedPoint) }
    return normalizeJournalCurvePoints(updated, fallbackDurationMinutes = updated.last().minute)
}

private fun deleteCurvePoint(
    points: List<JournalCurvePoint>,
    index: Int
): List<JournalCurvePoint> {
    if (!canDeleteCurvePoint(points, index)) return points
    return normalizeJournalCurvePoints(
        points.filterIndexed { pointIndex, _ -> pointIndex != index },
        fallbackDurationMinutes = points.last().minute
    )
}

private fun canDeleteCurvePoint(points: List<JournalCurvePoint>, index: Int): Boolean {
    return points.size > 3 && index in 1 until points.lastIndex
}

private fun findInsertedPointIndex(
    before: List<JournalCurvePoint>,
    after: List<JournalCurvePoint>,
    previousSelection: Int
): Int {
    val candidate = after.firstOrNull { point -> before.none { it.minute == point.minute && it.activity == point.activity } }
    return candidate?.let { inserted ->
        after.indexOfFirst { it.minute == inserted.minute && it.activity == inserted.activity }
    }?.takeIf { it >= 0 } ?: previousSelection.coerceIn(0, after.lastIndex)
}

private fun defaultSelectedPointIndex(points: List<JournalCurvePoint>): Int {
    return points.indices.maxByOrNull { points[it].activity }?.coerceAtLeast(1) ?: 1
}

private data class CurveBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

private fun JournalCurvePoint.toOffset(bounds: CurveBounds, maxMinute: Float): Offset {
    val x = bounds.left + ((minute / maxMinute) * bounds.width)
    val y = bounds.bottom - (activity.coerceIn(0f, 1f) * bounds.height)
    return Offset(x, y)
}

private fun nearestCurvePointIndex(
    points: List<JournalCurvePoint>,
    chartSize: IntSize,
    touch: Offset,
    horizontalPaddingPx: Float,
    topInsetPx: Float,
    bottomInsetPx: Float,
    requireHitRadius: Boolean = false
): Int? {
    if (points.isEmpty() || chartSize.width == 0 || chartSize.height == 0) return null
    val bounds = curveBounds(
        width = chartSize.width.toFloat(),
        height = chartSize.height.toFloat(),
        horizontalPaddingPx = horizontalPaddingPx,
        topInsetPx = topInsetPx,
        bottomInsetPx = bottomInsetPx
    )
    val maxMinute = points.last().minute.coerceAtLeast(1).toFloat()
    val nearest = points.indices.minByOrNull { index ->
        val pointOffset = points[index].toOffset(bounds, maxMinute)
        hypot((touch.x - pointOffset.x).toDouble(), (touch.y - pointOffset.y).toDouble())
    } ?: return null
    if (!requireHitRadius) return nearest
    val offset = points[nearest].toOffset(bounds, maxMinute)
    val distance = hypot((touch.x - offset.x).toDouble(), (touch.y - offset.y).toDouble())
    return nearest.takeIf { distance <= 36.0 }
}

private fun pointFromTouch(
    points: List<JournalCurvePoint>,
    index: Int,
    chartSize: IntSize,
    touch: Offset,
    horizontalPaddingPx: Float,
    topInsetPx: Float,
    bottomInsetPx: Float
): JournalCurvePoint {
    val bounds = curveBounds(
        width = chartSize.width.toFloat(),
        height = chartSize.height.toFloat(),
        horizontalPaddingPx = horizontalPaddingPx,
        topInsetPx = topInsetPx,
        bottomInsetPx = bottomInsetPx
    )
    val maxMinute = points.last().minute.coerceAtLeast(1)
    val normalizedX = ((touch.x - bounds.left) / bounds.width).coerceIn(0f, 1f)
    val normalizedY = ((bounds.bottom - touch.y) / bounds.height).coerceIn(0f, 1f)
    val minute = (normalizedX * maxMinute).roundToInt()
    return JournalCurvePoint(
        minute = minute,
        activity = normalizedY.coerceIn(0f, 1f)
    )
}

private fun curveBounds(
    width: Float,
    height: Float,
    horizontalPaddingPx: Float,
    topInsetPx: Float,
    bottomInsetPx: Float
): CurveBounds {
    return CurveBounds(
        left = horizontalPaddingPx,
        top = topInsetPx,
        right = width - horizontalPaddingPx,
        bottom = height - bottomInsetPx
    )
}

private fun cardPosition(index: Int, size: Int): CardPosition {
    return when {
        size <= 1 -> CardPosition.SINGLE
        index == 0 -> CardPosition.TOP
        index == size - 1 -> CardPosition.BOTTOM
        else -> CardPosition.MIDDLE
    }
}

private fun curveWindowSummary(points: List<JournalCurvePoint>): String {
    val onset = points.firstOrNull { it.activity > 0.01f }?.minute ?: 0
    val duration = points.lastOrNull()?.minute ?: 0
    return "${onset}m -> ${duration}m"
}

private fun defaultBuiltInProfile(sortOrder: Int): JournalBuiltInCurveProfile? = when (sortOrder) {
    0 -> JournalBuiltInCurveProfile.RAPID_GENERIC
    1 -> JournalBuiltInCurveProfile.LONG_BASAL_GENERIC
    2 -> JournalBuiltInCurveProfile.HUMAN_REGULAR
    3 -> JournalBuiltInCurveProfile.ASPART
    4 -> JournalBuiltInCurveProfile.LISPRO
    5 -> JournalBuiltInCurveProfile.GLULISINE
    6 -> JournalBuiltInCurveProfile.FIASP
    7 -> JournalBuiltInCurveProfile.URLI
    8 -> JournalBuiltInCurveProfile.AFREZZA
    9 -> JournalBuiltInCurveProfile.NPH
    10 -> JournalBuiltInCurveProfile.ULTRA_LONG_BASAL
    else -> null
}

private fun formatColorHex(color: Int): String {
    return "#%08X".format(color)
}

private fun parseColorHex(raw: String): Int? {
    val cleaned = raw.trim().removePrefix("#")
    val normalized = when (cleaned.length) {
        6 -> "FF$cleaned"
        8 -> cleaned
        else -> return null
    }
    return normalized.toLongOrNull(16)?.toInt()
}

private val DEFAULT_PRESET_COLOR = 0xFF1565C0.toInt()

private fun String.trimTrailingLabel(): String {
    return trim().trimEnd(':').trim()
}
