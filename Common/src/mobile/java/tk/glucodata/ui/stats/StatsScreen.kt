@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui.stats

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.toArgb
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DateRangePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.viewmodel.compose.viewModel
import tk.glucodata.R
import tk.glucodata.ui.components.CompactSheetDragHandle
import tk.glucodata.ui.components.StableModalBottomSheet
import tk.glucodata.ui.util.ConnectedButtonGroup
import tk.glucodata.ui.util.GlucoseFormatter
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import tk.glucodata.ui.theme.labelLargeExpressive

// Band colours live in StatsVisualizations.kt so every stats surface reads the same
// palette from one place.
private const val PrefKeyReportPdfStyle = "stats_report_pdf_style"

/** Below this a calendar grid is just a row of squares — the daily view already covers it. */
private const val MIN_CALENDAR_DAYS = 3

internal enum class TirBand {
    VERY_LOW,
    LOW,
    IN_RANGE,
    HIGH,
    VERY_HIGH
}

private data class TirRowDescriptor(
    val band: TirBand,
    val label: String,
    val rangeLabel: String,
    val percent: Float,
    val color: Color
)

@Composable
fun StatsScreen(
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = rememberStatsViewModel(),
    onOpenHypoEpisodes: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val isSwitchingRange by viewModel.isSwitchingRange.collectAsState()
    val excludedEpisodes by viewModel.excludedEpisodesInRange.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val reportPrefs = remember(context) {
        context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
    }
    var showShareSheet by rememberSaveable { mutableStateOf(false) }
    var workerUrl by rememberSaveable {
        mutableStateOf(reportPrefs.getString("stats_report_worker_url", "") ?: "")
    }
    var reportDaysInput by rememberSaveable { mutableStateOf("") }
    var pendingReportDays by rememberSaveable { mutableIntStateOf(90) }
    val quickReportRanges = remember { listOf(14, 30, 90, 180, 365) }
    var showPatientInfo by rememberSaveable {
        mutableStateOf(reportPrefs.getBoolean("stats_report_show_patient_info", false))
    }
    var patientNameInput by rememberSaveable {
        mutableStateOf(reportPrefs.getString("stats_report_patient_name", "") ?: "")
    }
    var patientIdInput by rememberSaveable {
        mutableStateOf(reportPrefs.getString("stats_report_patient_id", "") ?: "")
    }
    var patientDobInput by rememberSaveable {
        mutableStateOf(reportPrefs.getString("stats_report_patient_dob", "") ?: "")
    }
    var patientClinicianInput by rememberSaveable {
        mutableStateOf(reportPrefs.getString("stats_report_patient_clinician", "") ?: "")
    }
    var reportStylePref by rememberSaveable {
        mutableStateOf(
            reportPrefs.getString(PrefKeyReportPdfStyle, StatsReportExporter.PdfVisualStyle.CURRENT.prefValue)
                ?: StatsReportExporter.PdfVisualStyle.CURRENT.prefValue
        )
    }
    var pendingReportStylePref by rememberSaveable { mutableStateOf(reportStylePref) }
    val selectedReportStyle = StatsReportExporter.PdfVisualStyle.fromPref(reportStylePref)
    val view = LocalView.current
    LaunchedEffect(context) { StatsLayoutStore.ensureLoaded(context) }
    val layout by StatsLayoutStore.state.collectAsState()
    val editingLayout by StatsArrangeMode.editing.collectAsState()
    // Every ordinary way out of a mode, not just the button: system back, and simply
    // leaving the screen.
    BackHandler(enabled = editingLayout) { StatsArrangeMode.close() }
    DisposableEffect(Unit) { onDispose { StatsArrangeMode.close() } }
    val listState = rememberLazyListState()
    val visibleCards = layout.visibleCards.filter { card -> cardHasContent(card, uiState) }
    val cardDrag = rememberStatsCardDragState(
        listState = listState,
        order = layout.cardOrder,
        onReordered = StatsLayoutStore::setCardOrder
    )
    var selectedTirBand by remember(uiState.summary.tir) { mutableStateOf<TirBand?>(null) }
    // Keyed on the window, not on the data: a new reading must not slam the day sheet shut.
    var selectedDayDate by remember(uiState.activeRange) { mutableStateOf<java.time.LocalDate?>(null) }
    val selectedDay = uiState.summary.days.firstOrNull { it.date == selectedDayDate }
    var pendingPatientInfo by remember { mutableStateOf<StatsReportExporter.PatientInfo?>(null) }
    var exportedReportUri by remember { mutableStateOf<Uri?>(null) }

    var isPublishing by remember { mutableStateOf(false) }
    var showDateRangePicker by rememberSaveable { mutableStateOf(false) }
    val clearSelectionInteraction = remember { MutableInteractionSource() }

    fun buildPatientInfo(): StatsReportExporter.PatientInfo? {
        val info = StatsReportExporter.PatientInfo(
            name = patientNameInput.trim(),
            identifier = patientIdInput.trim(),
            dateOfBirth = patientDobInput.trim(),
            clinician = patientClinicianInput.trim()
        )
        return info.takeIf { it.hasContent() }
    }

    fun persistReportPrefs() {
        reportPrefs.edit()
            .putString("stats_report_worker_url", workerUrl.trim())
            .putBoolean("stats_report_show_patient_info", showPatientInfo)
            .putString("stats_report_patient_name", patientNameInput.trim())
            .putString("stats_report_patient_id", patientIdInput.trim())
            .putString("stats_report_patient_dob", patientDobInput.trim())
            .putString("stats_report_patient_clinician", patientClinicianInput.trim())
            .putString(PrefKeyReportPdfStyle, reportStylePref)
            .apply()
    }

    val pdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val reportState = viewModel.buildReportUiState(pendingReportDays)
            val result = StatsReportExporter.exportComprehensivePdf(
                context = context,
                uri = uri,
                uiState = reportState,
                reportDays = pendingReportDays,
                patientInfo = pendingPatientInfo,
                reportStyle = StatsReportExporter.PdfVisualStyle.fromPref(pendingReportStylePref)
            )
            result.onSuccess {
                exportedReportUri = uri
            }.onFailure { throwable ->
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.export_failed_with_error,
                        throwable.message ?: context.getString(R.string.unknown_error)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    LaunchedEffect(exportedReportUri) {
        val shownUri = exportedReportUri ?: return@LaunchedEffect
        delay(12_000L)
        if (exportedReportUri == shownUri) exportedReportUri = null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .clickable(
                indication = null,
                interactionSource = clearSelectionInteraction
            ) {
                selectedTirBand = null
            }
    ) {
        val showLoadingPlaceholder = uiState.isLoading && uiState.summary.readingCount == 0

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 100.dp)
        ) {
            item(key = "header") {
                HeaderBlock(onShareClick = {
                    reportDaysInput = uiState.selectedRange?.days
                        ?.takeIf { it > 0 }
                        ?.toString()
                        ?: uiState.activeRange?.daySpan
                        ?.takeIf { it > 0 }
                        ?.toString()
                        ?: "90"
                    showShareSheet = true
                })
            }

            item(key = "range") {
                Spacer(modifier = Modifier.height(4.dp))
                StatsRangeSelectorControl(
                    selectedRange = uiState.selectedRange,
                    activeRange = uiState.activeRange,
                    isLoading = uiState.isLoading || isSwitchingRange,
                    hasData = uiState.summary.readingCount > 0,
                    readingCount = uiState.summary.readingCount,
                    coveragePercent = uiState.summary.coverage.percent
                        .takeIf { uiState.summary.readingCount > 0 },
                    onRangeSelected = viewModel::setTimeRange,
                    onCustomRangeClick = { showDateRangePicker = true }
                )
            }

            if (showLoadingPlaceholder) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    LoadingCard()
                }
            } else if (!uiState.hasSensor) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    EmptyStateCard(
                        title = stringResource(R.string.start_tracking),
                        subtitle = stringResource(R.string.no_active_sensor_selected)
                    )
                }
            } else if (uiState.summary.readingCount == 0) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    EmptyStateCard(
                        title = stringResource(R.string.start_tracking),
                        subtitle = stringResource(R.string.stats_no_readings_in_range)
                    )
                }
            } else {
                item {
                    AnimatedVisibility(
                        visible = uiState.isLoading,
                        enter = fadeIn(tween(180)) + expandVertically(),
                        exit = fadeOut(tween(180)) + shrinkVertically()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Spacer(modifier = Modifier.height(12.dp))
                            RangeLoadingCard()
                        }
                    }
                }

                // Sections render in the user's own order. Long press lifts a card and
                // drags it inline; the Arrange button opens the list editor instead.
                itemsIndexed(
                    items = visibleCards,
                    key = { _, card -> card.name }
                ) { index, card ->
                    val isDragging = cardDrag.dragging == card
                    val lift by animateFloatAsState(
                        targetValue = if (isDragging) 1f else 0f,
                        label = "cardLift"
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isDragging) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null))
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragging) cardDrag.offset else 0f
                                scaleX = 1f + 0.02f * lift
                                scaleY = 1f + 0.02f * lift
                                shadowElevation = 12.dp.toPx() * lift
                                alpha = 1f - 0.06f * lift
                            }
                            .draggableStatsCard(
                                card = card,
                                state = cardDrag,
                                onLift = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) },
                                onTick = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                            )
                    ) {
                        Spacer(
                            modifier = Modifier.height(if (index == 0) 16.dp else 20.dp)
                        )
                        StatsCardContent(
                            card = card,
                            uiState = uiState,
                            layout = layout,
                            selectedTirBand = selectedTirBand,
                            onBandSelected = { selectedTirBand = it },
                            selectedDayDate = selectedDayDate,
                            onDaySelected = { selectedDayDate = it.date },
                            excludedEpisodes = excludedEpisodes,
                            onOpenHypoEpisodes = onOpenHypoEpisodes
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(20.dp))
                    StatsEditLayoutButton(onClick = { StatsArrangeMode.open() })
                }
            }
        }

        if (editingLayout) {
            ArrangeSheet(
                layout = layout,
                summary = uiState.summary,
                targets = uiState.targets,
                unit = uiState.unit,
                onDismiss = { StatsArrangeMode.close() }
            )
        }

        selectedDay?.let { day ->
            DayDetailSheet(
                day = day,
                readings = uiState.readings,
                episodes = uiState.summary.episodes,
                targets = uiState.targets,
                unit = uiState.unit,
                onDismiss = { selectedDayDate = null }
            )
        }

        if (showShareSheet) {
            val parsedReportDays = reportDaysInput.toIntOrNull()?.coerceIn(1, 365)
            StableModalBottomSheet(
                onDismissRequest = { showShareSheet = false },
                dragHandle = { CompactSheetDragHandle() }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.export_history_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = stringResource(R.string.statistics_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.quick_range),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ConnectedButtonGroup(
                        options = quickReportRanges,
                        selectedOption = parsedReportDays?.takeIf { it in quickReportRanges },
                        onOptionSelected = { quickDays ->
                            reportDaysInput = quickDays.toString()
                        },
                        labelText = { days -> "${days}D" },
                        label = { days ->
                            Text(
                                text = "${days}D",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false
                            )
                        },
                        itemHeight = 36.dp,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = reportDaysInput,
                        onValueChange = { value ->
                            reportDaysInput = value.filter { it.isDigit() }.take(3)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.days_to_export)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Text(
                        text = stringResource(R.string.report_style_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    PdfVisualStylePicker(
                        selectedStyle = selectedReportStyle,
                        onStyleSelected = { style -> reportStylePref = style.prefValue },
                        modifier = Modifier.fillMaxWidth()
                    )
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { showPatientInfo = !showPatientInfo },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (showPatientInfo) {
                                stringResource(R.string.hide_patient_info)
                            } else {
                                stringResource(R.string.add_patient_info)
                            }
                        )
                    }
                    AnimatedVisibility(visible = showPatientInfo) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.patient_info_optional),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = patientNameInput,
                                onValueChange = { patientNameInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.patient_name_label)) },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = patientIdInput,
                                onValueChange = { patientIdInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.patient_id_label)) },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = patientDobInput,
                                onValueChange = { patientDobInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.patient_dob_label)) },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = patientClinicianInput,
                                onValueChange = { patientClinicianInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.patient_clinician_label)) },
                                singleLine = true
                            )
                        }
                    }

                    androidx.compose.material3.FilledTonalButton(
                        onClick = {
                            val reportDays = parsedReportDays ?: return@FilledTonalButton
                            pendingReportDays = reportDays
                            pendingPatientInfo = buildPatientInfo()
                            pendingReportStylePref = reportStylePref
                            persistReportPrefs()
                            showShareSheet = false
                            val reportDate = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                            pdfLauncher.launch("cgm_report_$reportDate.pdf")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = parsedReportDays != null
                    ) {
                        Text(text = stringResource(R.string.export_readable_report))
                    }

                    OutlinedTextField(
                        value = workerUrl,
                        onValueChange = { workerUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.urls)) },
                        singleLine = true
                    )

                    androidx.compose.material3.Button(
                        onClick = {
                            val reportDays = parsedReportDays ?: return@Button
                            if (workerUrl.isBlank() || isPublishing) return@Button
                            coroutineScope.launch {
                                isPublishing = true
                                val patientInfo = buildPatientInfo()
                                val reportState = viewModel.buildReportUiState(reportDays)
                                val publishResult = StatsReportExporter.publishInteractiveReport(
                                    workerBaseUrl = workerUrl,
                                    uiState = reportState,
                                    reportDays = reportDays,
                                    patientInfo = patientInfo
                                )
                                isPublishing = false
                                publishResult
                                    .onSuccess { viewUrl ->
                                        persistReportPrefs()
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, viewUrl)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.open)))
                                        Toast.makeText(context, context.getString(R.string.success), Toast.LENGTH_SHORT).show()
                                        showShareSheet = false
                                    }
                                    .onFailure { throwable ->
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.export_failed_with_error,
                                                throwable.message ?: context.getString(R.string.unknown_error)
                                            ),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isPublishing && workerUrl.isNotBlank() && parsedReportDays != null
                    ) {
                        Text(text = if (isPublishing) stringResource(R.string.loading_data) else stringResource(R.string.export))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        if (showDateRangePicker) {
            StatsDateRangeSheet(
                availableRange = uiState.availableRange,
                activeRange = uiState.activeRange,
                onDismiss = { showDateRangePicker = false },
                onApply = { start, end ->
                    viewModel.setCustomRange(start, end)
                    showDateRangePicker = false
                }
            )
        }

        AnimatedVisibility(
            visible = exportedReportUri != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { it / 2 }
        ) {
            ReportExportConfirmation(
                onOpen = {
                    exportedReportUri?.let { openPdfReport(context, it) }
                    exportedReportUri = null
                },
                onShare = {
                    exportedReportUri?.let { sharePdfReport(context, it) }
                    exportedReportUri = null
                }
            )
        }
    }
}

/**
 * Sections with nothing to show are skipped rather than rendered empty — a sensor
 * without a thermistor should not leave a temperature placeholder in the list.
 */
private fun cardHasContent(card: StatsCard, uiState: StatsUiState): Boolean = when (card) {
    StatsCard.TEMPERATURE -> uiState.temperaturePoints.isNotEmpty()
    // Episodes stays even at zero: "none in this window" is the answer someone opened
    // the card to get, and a section that comes and goes is worse than a quiet one.
    StatsCard.DAY_BY_DAY -> uiState.summary.days.size >= MIN_CALENDAR_DAYS
    StatsCard.INSIGHTS -> uiState.summary.insights.isNotEmpty()
    StatsCard.METRICS -> true
    else -> true
}

@Composable
private fun StatsCardContent(
    card: StatsCard,
    uiState: StatsUiState,
    layout: StatsLayoutState,
    selectedTirBand: TirBand?,
    onBandSelected: (TirBand?) -> Unit,
    selectedDayDate: java.time.LocalDate?,
    onDaySelected: (DayBreakdown) -> Unit,
    excludedEpisodes: Int = 0,
    onOpenHypoEpisodes: (() -> Unit)? = null
) {
    when (card) {
        StatsCard.OVERVIEW -> GlycemicOverviewCard(
            summary = uiState.summary,
            targets = uiState.targets,
            unit = uiState.unit,
            selectedRange = uiState.selectedRange,
            selectedBand = selectedTirBand,
            onBandSelected = onBandSelected
        )

        StatsCard.METRICS -> MetricsSection(
            metrics = layout.visibleMetrics,
            wideMetrics = layout.wideMetrics,
            fullOrder = layout.metricOrder,
            summary = uiState.summary,
            targets = uiState.targets,
            unit = uiState.unit
        )

        StatsCard.EPISODES -> Column {
            EpisodesCard(
                lows = uiState.summary.lowEpisodes,
                highs = uiState.summary.highEpisodes,
                episodes = uiState.summary.episodes,
                unit = uiState.unit
            )
            // The honest label: cleaned numbers must say they are cleaned, and the row
            // is the door to the log where the cleaning is done and undone.
            if (onOpenHypoEpisodes != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenHypoEpisodes,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (excludedEpisodes > 0) {
                            stringResource(R.string.stats_excluded_episodes_label, excludedEpisodes)
                        } else {
                            stringResource(R.string.stats_open_hypo_log)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                }
            }
        }

        StatsCard.PATTERNS -> PatternsCard(
            agpByHour = uiState.summary.agpByHour,
            hourlyStats = uiState.summary.hourlyStats,
            dailyStats = uiState.summary.dailyStats,
            dayParts = uiState.summary.dayParts,
            weekdays = uiState.summary.weekdays,
            targets = uiState.targets,
            unit = uiState.unit,
            activeRange = uiState.activeRange
        )

        StatsCard.DAY_BY_DAY -> DayByDayCard(
            days = uiState.summary.days,
            selectedDate = selectedDayDate,
            onDaySelected = onDaySelected
        )

        StatsCard.RISK_INDEX -> RiskIndexCard(
            gri = uiState.summary.gri,
            risk = uiState.summary.risk,
            comparison = uiState.summary.comparison
        )

        StatsCard.TEMPERATURE -> TemperatureOverviewCard(
            temperaturePoints = uiState.temperaturePoints
        )

        StatsCard.INSIGHTS -> InsightsCard(insights = uiState.summary.insights)
    }
}

/**
 * Arrange's title bar, fixed above the list.
 *
 * A mode needs a frame that stays put. This one carries the name of the mode, what the
 * gestures do, and the way out — and none of it scrolls away underneath twenty-eight rows
 * of things to drag.
 */
@Composable
private fun ArrangeSheet(
    layout: StatsLayoutState,
    summary: StatsSummary,
    targets: StatsTargets,
    unit: GlucoseUnit,
    onDismiss: () -> Unit
) {
    // Built exactly like Show on Dashboard: default sheet state, no height of its own, the
    // header outside the scrolling part and everything else inside it.
    //
    // The previous version pinned the height and then animated it larger off a nested
    // scroll connection. Both halves of that fought the sheet's own gesture handling — the
    // connection saw scrolls the sheet needed in order to decide between scrolling and
    // dismissing, and the animating height moved its drag anchors underneath it — so it
    // dismissed on an ordinary scroll, and on any scroll begun before the open animation
    // had settled.
    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { CompactSheetDragHandle() }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Fixed: the title says which mode you are in and the button leaves it, so
            // neither belongs in the part that scrolls away.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.stats_arrange_title),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = stringResource(R.string.stats_arrange_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.libre_setup_done))
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp)
            ) {
                StatsLayoutEditor(
                    layout = layout,
                    summary = summary,
                    targets = targets,
                    unit = unit,
                    onDone = onDismiss
                )
            }
        }
    }
}

@Composable
private fun MetricsSection(
    metrics: List<StatsMetric>,
    wideMetrics: Set<StatsMetric>,
    fullOrder: List<StatsMetric>,
    summary: StatsSummary,
    targets: StatsTargets,
    unit: GlucoseUnit
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(emptySet<StatsMetric>()) }
    // One drag state for both grids so a tile can be dragged across the fold, and one
    // expansion set so the two halves agree about which tiles are open.
    val dragState = rememberMetricDragState(
        order = fullOrder,
        onReordered = StatsLayoutStore::setMetricOrder
    )
    val rows = remember(metrics, wideMetrics) { packMetricRows(metrics, wideMetrics) }
    val headMetrics = rows.take(StatsMetric.DEFAULT_VISIBLE_ROWS)
        .flatMap { listOfNotNull(it.first, it.second) }
    val tailMetrics = rows.drop(StatsMetric.DEFAULT_VISIBLE_ROWS)
        .flatMap { listOfNotNull(it.first, it.second) }

    val toggleExpanded: (StatsMetric) -> Unit = { metric ->
        // A long press that turned into a drag must not also open the tile.
        if (dragState.dragging == null) {
            expanded = if (metric in expanded) expanded - metric else expanded + metric
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        // The grid keeps its own even spacing; the disclosure below is deliberately not
        // part of it.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (metrics.isEmpty()) {
                SectionEmptyLine(text = stringResource(R.string.stats_metrics_all_hidden))
            } else {
                MetricsGrid(
                    metrics = headMetrics,
                    wideMetrics = wideMetrics,
                    summary = summary,
                    targets = targets,
                    unit = unit,
                    expanded = expanded,
                    onToggleExpanded = toggleExpanded,
                    dragState = dragState
                )
            }

            if (tailMetrics.isNotEmpty()) {
                AnimatedVisibility(
                    visible = showAll,
                    enter = fadeIn(tween(180)) + expandVertically(tween(240)),
                    exit = fadeOut(tween(140)) + shrinkVertically(tween(200))
                ) {
                    MetricsGrid(
                        metrics = tailMetrics,
                        wideMetrics = wideMetrics,
                        summary = summary,
                        targets = targets,
                        unit = unit,
                        expanded = expanded,
                        onToggleExpanded = toggleExpanded,
                        dragState = dragState
                    )
                }
            }
        }

        // Back to the label and chevron this shipped with. Four attempts at replacing it
        // with a bare glyph produced, in order: a disc with dead space round it, a strip
        // that read as "scroll down", a full-bleed bar that ran into the tiles, and a
        // chevron adrift in the gap between two cards. The label is what says this belongs
        // to the metrics; the padding is tighter than before so it takes less room doing
        // it.
        if (tailMetrics.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { showAll = !showAll }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (showAll) {
                        stringResource(R.string.stats_fewer_metrics)
                    } else {
                        stringResource(R.string.stats_more_metrics)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = if (showAll) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ReportExportConfirmation(
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.inversePrimary,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = stringResource(R.string.export_successful),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Surface(
                    onClick = onOpen,
                    shape = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50),
                    color = MaterialTheme.colorScheme.inversePrimary,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Box(
                        modifier = Modifier
                            .height(48.dp)
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.open),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                Surface(
                    onClick = onShare,
                    shape = RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50),
                    color = MaterialTheme.colorScheme.inversePrimary,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(R.string.share),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun openPdfReport(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        clipData = ClipData.newUri(context.contentResolver, "CGM report", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, context.getString(R.string.wentwrong), Toast.LENGTH_SHORT).show()
        }
}

private fun sharePdfReport(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, "CGM report", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.wentwrong), Toast.LENGTH_SHORT).show()

    }
}


private data class PdfStylePreviewPalette(
    val paper: Color,
    val stripe: Color,
    val accent: Color,
    val text: Color
)

private fun resolvePdfStylePreviewPalette(
    style: StatsReportExporter.PdfVisualStyle
): PdfStylePreviewPalette = when (style) {
    StatsReportExporter.PdfVisualStyle.CURRENT -> PdfStylePreviewPalette(
        paper = Color(0xFFF5F8FD),
        stripe = Color(0xFF1A52B0),
        accent = Color(0xFFD65F3F),
        text = Color(0xFF1B2637)
    )
    StatsReportExporter.PdfVisualStyle.MINIMAL_SWISS -> PdfStylePreviewPalette(
        paper = Color(0xFFFAFBFD),
        stripe = Color(0xFF181818),
        accent = Color(0xFFBB2025),
        text = Color(0xFF21252C)
    )
    StatsReportExporter.PdfVisualStyle.MEDICAL_JOURNAL -> PdfStylePreviewPalette(
        paper = Color(0xFFF8F6F1),
        stripe = Color(0xFF295789),
        accent = Color(0xFF774935),
        text = Color(0xFF30353F)
    )
    StatsReportExporter.PdfVisualStyle.PREMIUM_DARK_INK -> PdfStylePreviewPalette(
        paper = Color(0xFF1A202B),
        stripe = Color(0xFF7FABFF),
        accent = Color(0xFFDB9E6E),
        text = Color(0xFFE8EDF6)
    )
    StatsReportExporter.PdfVisualStyle.ELEGANT_TYPOGRAPHY -> PdfStylePreviewPalette(
        paper = Color(0xFFFCFBF9),
        stripe = Color(0xFF56448B),
        accent = Color(0xFFB07F57),
        text = Color(0xFF413952)
    )
}

@Composable
private fun PdfVisualStylePicker(
    selectedStyle: StatsReportExporter.PdfVisualStyle,
    onStyleSelected: (StatsReportExporter.PdfVisualStyle) -> Unit,
    modifier: Modifier = Modifier
) {
    val styles = remember { StatsReportExporter.PdfVisualStyle.entries.toList() }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        styles.forEach { style ->
            val selected = style == selectedStyle
            val palette = resolvePdfStylePreviewPalette(style)
            Surface(
                onClick = { onStyleSelected(style) },
                shape = RoundedCornerShape(18.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                tonalElevation = if (selected) 3.dp else 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = 146.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.paper)
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .fillMaxHeight()
                                .width(8.dp)
                                .background(palette.stripe)
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(width = 56.dp, height = 14.dp)
                                .background(palette.accent.copy(alpha = 0.75f))
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 16.dp)
                                .size(width = 78.dp, height = 5.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .background(palette.text.copy(alpha = 0.7f))
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 16.dp, bottom = 8.dp)
                                .size(width = 52.dp, height = 4.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .background(palette.text.copy(alpha = 0.45f))
                        )
                    }
                    Text(
                        text = stringResource(style.labelResId),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
@Composable
private fun HeaderBlock(
    onShareClick: () -> Unit
)
{
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.statistics_title),
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(start = 16.dp)
        )
        IconButton(
            onClick = onShareClick,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = stringResource(R.string.export),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun LoadingCard() {
    val animatedAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "loadingAlpha"
    )

    Card(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Analytics,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = animatedAlpha),
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = stringResource(R.string.loading_data),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun RangeLoadingCard() {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.DateRange,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.loading_data),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
    }
}

@Composable
private fun EmptyStateCard(title: String, subtitle: String) {
    Card(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun GlycemicOverviewCard(
    summary: StatsSummary,
    targets: StatsTargets,
    unit: GlucoseUnit,
    selectedRange: StatsTimeRange?,
    selectedBand: TirBand?,
    onBandSelected: (TirBand?) -> Unit
) {
    val rows = listOf(
        TirRowDescriptor(
            band = TirBand.VERY_HIGH,
            label = stringResource(R.string.very_high),
            rangeLabel = ">= ${formatMgDl(targets.veryHighMgDl, unit)}",
            percent = summary.tir.veryHighPercent,
            color = TirVeryHighColor
        ),
        TirRowDescriptor(
            band = TirBand.HIGH,
            label = stringResource(R.string.high_range),
            rangeLabel = "${formatMgDl(targets.highMgDl, unit)}-${formatMgDl(targets.veryHighMgDl, unit)}",
            percent = summary.tir.highPercent,
            color = TirHighColor
        ),
        TirRowDescriptor(
            band = TirBand.IN_RANGE,
            label = stringResource(R.string.in_range),
            rangeLabel = "${formatMgDl(targets.lowMgDl, unit)}-${formatMgDl(targets.highMgDl, unit)}",
            percent = summary.tir.inRangePercent,
            color = TirInRangeColor
        ),
        TirRowDescriptor(
            band = TirBand.LOW,
            label = stringResource(R.string.low_range),
            rangeLabel = "${formatMgDl(targets.veryLowMgDl, unit)}-${formatMgDl(targets.lowMgDl, unit)}",
            percent = summary.tir.lowPercent,
            color = TirLowColor
        ),
        TirRowDescriptor(
            band = TirBand.VERY_LOW,
            label = stringResource(R.string.very_low),
            rangeLabel = "< ${formatMgDl(targets.veryLowMgDl, unit)}",
            percent = summary.tir.veryLowPercent,
            color = TirVeryLowColor
        )
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                onBandSelected(null)
            },
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 34.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                // The band rows are what decide this, not a round number.
                //
                // A row needs its label, a 62 dp range column and a 50 dp percentage
                // column; below about 180 dp it starts eliding the labels. So the ring
                // keeps whatever is left over and the two only stop sharing a line when
                // the ring would have to drop under 96 dp — a threshold of 340 dp put a
                // 360 dp phone into the stacked layout with 150 dp of width to spare,
                // which is the ring taking a whole row on someone else's device while it
                // looked fine on a Pixel.
                val minRowsWidth = 180.dp
                val ringSpacing = 10.dp
                val ringMin = 88.dp
                val ringMax = 176.dp
                val isCompact = maxWidth < ringMin + ringSpacing + minRowsWidth
                val ringSize = if (isCompact) {
                    (maxWidth * 0.55f).coerceIn(118.dp, 206.dp)
                } else {
                    (maxWidth * 0.34f)
                        .coerceIn(ringMin, ringMax)
                        // Never at the rows' expense.
                        .coerceAtMost(maxWidth - ringSpacing - minRowsWidth)
                }
                val compactText = maxWidth < 372.dp
                val tirRows: @Composable () -> Unit = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rows.forEach { row ->
                            TirCompactRow(
                                label = row.label,
                                rangeLabel = row.rangeLabel,
                                percent = row.percent,
                                color = row.color,
                                selected = selectedBand == row.band,
                                compactText = compactText,
                                onClick = {
                                    onBandSelected(if (selectedBand == row.band) null else row.band)
                                }
                            )
                        }
                    }
                }

                if (isCompact) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        OverviewRing(
                            tir = summary.tir,
                            comparison = summary.comparison,
                            selectedBand = selectedBand,
                            onBandSelected = onBandSelected,
                            modifier = Modifier.size(ringSize)
                        )
                        tirRows()
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OverviewRing(
                            tir = summary.tir,
                            comparison = summary.comparison,
                            selectedBand = selectedBand,
                            onBandSelected = onBandSelected,
                            modifier = Modifier.size(ringSize)
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            tirRows()
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun OverviewRing(
    tir: TimeInRangeBreakdown,
    comparison: StatsComparison?,
    selectedBand: TirBand?,
    onBandSelected: (TirBand?) -> Unit,
    modifier: Modifier = Modifier
) {
    val veryLow by animateFloatAsState(tir.veryLowPercent.coerceIn(0f, 100f), label = "ringVeryLow")
    val low by animateFloatAsState(tir.lowPercent.coerceIn(0f, 100f), label = "ringLow")
    val inRange by animateFloatAsState(tir.inRangePercent.coerceIn(0f, 100f), label = "ringInRange")
    val high by animateFloatAsState(tir.highPercent.coerceIn(0f, 100f), label = "ringHigh")
    val veryHigh by animateFloatAsState(tir.veryHighPercent.coerceIn(0f, 100f), label = "ringVeryHigh")

    val inRangeTone by animateColorAsState(
        targetValue = when {
            inRange >= 70f -> TirInRangeColor
            inRange >= 55f -> TirHighColor
            else -> TirVeryHighColor
        },
        label = "ringCenterTone"
    )

    val segments = listOf(
        Triple(TirBand.VERY_LOW, veryLow, TirVeryLowColor),
        Triple(TirBand.LOW, low, TirLowColor),
        Triple(TirBand.IN_RANGE, inRange, TirInRangeColor),
        Triple(TirBand.HIGH, high, TirHighColor),
        Triple(TirBand.VERY_HIGH, veryHigh, TirVeryHighColor)
    )

    Box(
        modifier = modifier.pointerInput(veryLow, low, inRange, high, veryHigh, selectedBand) {
            detectTapGestures { tapOffset ->
                val center = Offset(size.width / 2f, size.height / 2f)
                val dx = tapOffset.x - center.x
                val dy = tapOffset.y - center.y
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                val outerRadius = minOf(size.width, size.height) / 2f
                val strokePx = 15.dp.toPx()
                val innerRadius = (outerRadius - strokePx * 1.35f).coerceAtLeast(0f)
                if (distance < innerRadius || distance > outerRadius + strokePx * 0.35f) {
                    onBandSelected(null)
                    return@detectTapGestures
                }

                val angle = ((atan2(dy, dx) * 180f / PI.toFloat()) + 450f) % 360f
                var start = 0f
                for ((band, percent, _) in segments) {
                    val sweep = 360f * (percent / 100f)
                    if (sweep > 0f && angle >= start && angle < start + sweep) {
                        onBandSelected(if (selectedBand == band) null else band)
                        return@detectTapGestures
                    }
                    start += sweep
                }
                onBandSelected(null)
            }
        },
        contentAlignment = Alignment.Center
    ) {
        val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 15.dp.toPx()
            val diameter = size.minDimension - stroke
            drawCircle(
                color = trackColor,
                radius = diameter / 2f,
                center = center,
                style = Stroke(width = stroke)
            )

            var startAngle = -90f
            segments.forEach { (band, percent, color) ->
                val sweepAngle = 360f * (percent / 100f)
                if (sweepAngle > 0.8f) {
                    val gap = 1.2f
                    val drawColor = if (selectedBand == null || selectedBand == band) {
                        color
                    } else {
                        color.copy(alpha = 0.35f)
                    }
                    val drawStroke = if (selectedBand == band) stroke + 2.dp.toPx() else stroke
                    drawArc(
                        color = drawColor,
                        startAngle = startAngle + (gap / 2f),
                        sweepAngle = (sweepAngle - gap).coerceAtLeast(0.2f),
                        useCenter = false,
                        topLeft = Offset(center.x - diameter / 2f, center.y - diameter / 2f),
                        size = Size(diameter, diameter),
                        style = Stroke(width = drawStroke, cap = StrokeCap.Butt)
                    )
                }
                startAngle += sweepAngle
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = buildAnnotatedString {
                    withStyle(
                        style = SpanStyle(
                            color = inRangeTone,
                            fontFeatureSettings = "tnum" // Keeps digits monospaced so they don't jitter during animation
                        )
                    ) {
                        // Rounds to integer and formats based on user locale
                        append(String.format(Locale.getDefault(), "%.0f", inRange))
                    }
                    withStyle(
                        style = SpanStyle(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        append("%")
                    }
                },
                style = MaterialTheme.typography.headlineLarge
            )
            Text(
                text = "TIR",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // The change against the previous period belongs on the number it describes,
            // not on a line of its own below the card.
            comparison?.takeIf { abs(it.inRangeDelta) >= 1f }?.let { delta ->
                val rising = delta.inRangeDelta >= 0f
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Icon(
                        imageVector = if (rising) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = if (rising) TirInRangeColor else TirHighColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = abs(delta.inRangeDelta).roundToInt().toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = if (rising) TirInRangeColor else TirHighColor,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun TirCompactRow(
    label: String,
    rangeLabel: String,
    percent: Float,
    color: Color,
    selected: Boolean,
    compactText: Boolean,
    onClick: () -> Unit
) {
    val rangeColumnWidth = if (compactText) 62.dp else 74.dp
    val percentColumnWidth = if (compactText) 50.dp else 56.dp
    val labelStyle = if (compactText) {
        MaterialTheme.typography.bodySmall
    } else {
        MaterialTheme.typography.bodyMedium
    }
    val rangeStyle = if (compactText) {
        MaterialTheme.typography.labelSmall
    } else {
        MaterialTheme.typography.labelMedium
    }
    val percentStyle = if (compactText) {
        MaterialTheme.typography.labelMedium
    } else {
        MaterialTheme.typography.labelLarge
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f)
                } else {
                    Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 0.dp, top = if (compactText) 3.dp else 4.dp, bottom = if (compactText) 3.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            style = labelStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = rangeLabel,
            style = rangeStyle.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.width(rangeColumnWidth),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End
        )
        Text(
            text = String.format(Locale.getDefault(), "%.1f%%", percent),
            style = percentStyle.copy(
                fontFeatureSettings = "tnum",
                fontWeight = FontWeight.SemiBold
            ),
            color = color.copy(alpha = 0.82f),
            modifier = Modifier.width(percentColumnWidth),
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.End
        )
    }
}

/**
 * Key metric tile with left accent strip — matches GVI/PSG ScoreTile design language.
 * Used for Average and A1c in the GlycemicOverviewCard.
 */
@Composable
private fun PatternsCard(
    agpByHour: List<AgpHourBin>,
    hourlyStats: List<HourlyGlucoseStats>,
    dailyStats: List<DailyStats>,
    dayParts: List<DayPartStats>,
    weekdays: List<WeekdayStats>,
    targets: StatsTargets,
    unit: GlucoseUnit,
    activeRange: StatsDateRange?
) {
    val availableBins = agpByHour.count { it.sampleCount > 0 }
    val availableTabs = remember(agpByHour, dailyStats, dayParts, weekdays) {
        buildList {
            if (availableBins > 0) add(PatternTab.AGP)
            if (dailyStats.size > 1) add(PatternTab.DAILY)
            if (hourlyStats.any { it.sampleCount > 0 }) add(PatternTab.TIME_OF_DAY)
            // Weekday only pays off once more than one week is in view.
            if (weekdays.count { it.readingCount > 0 } >= 3) add(PatternTab.WEEKDAY)
        }.ifEmpty { listOf(PatternTab.AGP) }
    }
    var selectedTab by remember(availableTabs) { mutableStateOf(availableTabs.first()) }
    var selectedAgpHour by remember(agpByHour) {
        mutableStateOf(agpByHour.firstOrNull { it.sampleCount > 0 }?.hour ?: 0)
    }
    var selectedDailyIndex by remember(dailyStats) {
        mutableIntStateOf(dailyStats.lastIndex.coerceAtLeast(0))
    }
    val allValuesLabel = stringResource(R.string.allvalues)
    val windowLabel = remember(activeRange, allValuesLabel) {
        formatStatsDateRange(activeRange) ?: allValuesLabel
    }
    val contextLabel = if (selectedTab == PatternTab.AGP && availableBins < 24) {
        "$windowLabel · ${stringResource(R.string.stats_coverage, availableBins)}"
    } else {
        windowLabel
    }
    val selectedAgpBin = agpByHour.getOrNull(selectedAgpHour.coerceIn(0, 23))
    val selectedDaily = dailyStats.getOrNull(selectedDailyIndex.coerceIn(0, dailyStats.lastIndex.coerceAtLeast(0)))
    val dayFormatter = remember { DateTimeFormatter.ofPattern("MMM d") }
    val selectedHourly = hourlyStats.getOrNull(selectedAgpHour.coerceIn(0, 23))
    val bestWeekday = weekdays.filter { it.readingCount > 0 }.maxByOrNull { it.tir.inRangePercent }
    val headerPrimary = when (selectedTab) {
        PatternTab.AGP -> {
            val hour = selectedAgpHour.coerceIn(0, 23)
            selectedAgpBin?.medianMgDl?.let { median ->
                String.format(
                    Locale.getDefault(),
                    "%02d:00 · %s %s",
                    hour,
                    stringResource(R.string.median),
                    formatMgDl(median, unit)
                )
            } ?: String.format(Locale.getDefault(), "%02d:00 · %s", hour, stringResource(R.string.stats_agp_no_sample))
        }

        PatternTab.DAILY -> selectedDaily?.let { day ->
            "${day.date.format(dayFormatter)} · ${formatMgDl(day.averageMgDl, unit)} ~"
        } ?: windowLabel

        PatternTab.TIME_OF_DAY -> selectedHourly?.takeIf { it.sampleCount > 0 }?.let { hourly ->
            String.format(
                Locale.getDefault(),
                "%02d:00 · %s",
                hourly.hour,
                formatMgDl(hourly.averageMgDl, unit)
            )
        } ?: String.format(
            Locale.getDefault(),
            "%02d:00 · %s",
            selectedAgpHour.coerceIn(0, 23),
            stringResource(R.string.stats_agp_no_sample)
        )

        PatternTab.WEEKDAY -> bestWeekday?.let { weekday ->
            stringResource(
                R.string.stats_best_stretch,
                weekday.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
            )
        } ?: windowLabel
    }
    val headerSecondary = when (selectedTab) {
        PatternTab.AGP -> {
            val iqrLow = selectedAgpBin?.p25MgDl
            val iqrHigh = selectedAgpBin?.p75MgDl
            if (iqrLow != null && iqrHigh != null) {
                "IQR ${formatMgDl(iqrLow, unit)}-${formatMgDl(iqrHigh, unit)}"
            } else {
                contextLabel
            }
        }

        PatternTab.DAILY -> selectedDaily?.let { day ->
            String.format(Locale.getDefault(), "%.0f%% TIR", day.inRangePercent)
        } ?: contextLabel

        PatternTab.TIME_OF_DAY -> selectedHourly?.takeIf { it.sampleCount > 0 }?.let { hourly ->
            String.format(
                Locale.getDefault(),
                "%.0f%% TIR · %.0f%% %s",
                hourly.tir.inRangePercent,
                hourly.tir.belowRangePercent,
                stringResource(R.string.low_range)
            )
        } ?: contextLabel

        PatternTab.WEEKDAY -> bestWeekday?.let { weekday ->
            String.format(Locale.getDefault(), "%.0f%% TIR", weekday.tir.inRangePercent)
        } ?: contextLabel
    }

    Card(
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 30.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        val chartHeight = 352.dp
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                val subtitle = when (selectedTab) {
                    PatternTab.AGP -> stringResource(R.string.hourly_patterns)
                    PatternTab.DAILY -> stringResource(R.string.daily_trends)
                    PatternTab.TIME_OF_DAY -> stringResource(R.string.stats_time_of_day_subtitle)
                    PatternTab.WEEKDAY -> stringResource(R.string.stats_weekday_subtitle)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.stats_patterns),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                    )
                }
                Column(
                    modifier = Modifier
                        .padding(start = 12.dp, top = 2.dp)
                        .width(190.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = headerPrimary,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFeatureSettings = "tnum",
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = headerSecondary,
                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (availableTabs.size > 1) {
                val tabLabels = mapOf(
                    PatternTab.AGP to "AGP",
                    PatternTab.DAILY to stringResource(R.string.stats_tab_days),
                    PatternTab.TIME_OF_DAY to stringResource(R.string.stats_tab_hours),
                    PatternTab.WEEKDAY to stringResource(R.string.stats_tab_weekdays)
                )
                ConnectedButtonGroup(
                    options = availableTabs,
                    selectedOption = selectedTab,
                    onOptionSelected = { selectedTab = it },
                    labelText = { tab -> tabLabels[tab].orEmpty() },
                    label = { tab ->
                        Text(
                            text = tabLabels[tab].orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false
                        )
                    },
                    itemHeight = 38.dp,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }

            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(220)) + slideInVertically { it / 8 })
                        .togetherWith(fadeOut(animationSpec = tween(170)) + slideOutVertically { -it / 8 })
                },
                label = "patternsSwitch"
            ) { tab ->
                when (tab) {
                    PatternTab.AGP -> {
                        AgpChart(
                            agpByHour = agpByHour,
                            targets = targets,
                            unit = unit,
                            selectedHour = selectedAgpHour,
                            onSelectedHourChange = { selectedAgpHour = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(chartHeight)
                        )
                    }

                    PatternTab.DAILY -> {
                        DailyTrendSparkline(
                            dailyStats = dailyStats,
                            targets = targets,
                            unit = unit,
                            selectedIndex = selectedDailyIndex.coerceIn(0, dailyStats.lastIndex.coerceAtLeast(0)),
                            onSelectedIndexChange = { selectedDailyIndex = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(chartHeight)
                        )
                    }

                    PatternTab.TIME_OF_DAY -> {
                        // The ribbon is the hour-by-hour read; the six-hour rows below
                        // are the same day condensed to something you can say out loud.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            HourlyExposureRibbon(
                                hourlyStats = hourlyStats,
                                selectedHour = selectedAgpHour,
                                onSelectedHourChange = { selectedAgpHour = it },
                                contentDescription = stringResource(R.string.stats_hourly_exposure),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            DayPartPatterns(
                                dayParts = dayParts,
                                unit = unit,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    PatternTab.WEEKDAY -> {
                        WeekdayPatterns(
                            weekdays = weekdays,
                            unit = unit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

private enum class PatternTab {
    AGP,
    DAILY,
    TIME_OF_DAY,
    WEEKDAY
}

@Composable
private fun AgpChart(
    agpByHour: List<AgpHourBin>,
    targets: StatsTargets,
    unit: GlucoseUnit,
    selectedHour: Int,
    onSelectedHourChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val values = remember(agpByHour) {
        agpByHour.flatMap { bin ->
            listOfNotNull(bin.p10MgDl, bin.p25MgDl, bin.medianMgDl, bin.p75MgDl, bin.p90MgDl)
        }
    }
    var chartSize by remember { mutableStateOf(IntSize.Zero) }

    if (values.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.stats_no_agp_data),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val lowAnchor = targets.lowMgDl
    val highAnchor = targets.highMgDl
    val targetSpan = (highAnchor - lowAnchor).coerceAtLeast(40f)
    val yPadding = (targetSpan * 0.2f).coerceIn(12f, 40f)
    val minY = minOf(values.minOrNull() ?: lowAnchor, lowAnchor - yPadding)
    val maxY = maxOf(values.maxOrNull() ?: highAnchor, highAnchor + yPadding).coerceAtLeast(minY + 10f)

    val chartContext = LocalContext.current
    val trafficLineColors = remember(chartContext) {
        chartContext.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
            .getBoolean("glucose_app_chart_range_colors_enabled", false)
    }
    val isDarkTheme = isSystemInDarkTheme()
    val targetBandColor = TirInRangeColor.copy(alpha = 0.16f)
    val iqrBandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    val p10P90Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val medianColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.94f)
    val gridLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    val selectorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val tickHours = listOf(0, 6, 12, 18, 23)
    val axisLabelValues = remember(targets, minY, maxY) {
        listOf(
            maxY,
            targets.highMgDl,
            targets.lowMgDl,
            minY
        )
            .map { it.coerceIn(minY, maxY) }
            .distinct()
            .sortedDescending()
            .fold(mutableListOf<Float>()) { acc, value ->
                if (acc.isEmpty() || abs(acc.last() - value) >= 6f) {
                    acc.add(value)
                }
                acc
            }
    }
    val density = LocalDensity.current
    val leftAxisInset = 0.dp
    val rightPlotPadding = 0.dp
    val verticalChartPadding = 6.dp
    val leftAxisInsetPx = with(density) { leftAxisInset.toPx() }
    val rightPlotPaddingPx = with(density) { rightPlotPadding.toPx() }

    val selectHourFromX = { x: Float ->
        if (chartSize.width <= 0) 0
        else {
            val chartWidth = chartSize.width.toFloat().coerceAtLeast(1f)
            val localX = x.coerceIn(0f, chartWidth)
            ((localX / chartWidth) * 23f).roundToInt().coerceIn(0, 23)
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { chartSize = it }
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(agpByHour, chartSize) {
                        detectTapGestures { offset ->
                            onSelectedHourChange(selectHourFromX(offset.x))
                        }
                    }
                    .pointerInput(agpByHour, chartSize) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset -> onSelectedHourChange(selectHourFromX(offset.x)) },
                            onHorizontalDrag = { change, _ ->
                                onSelectedHourChange(selectHourFromX(change.position.x))
                                change.consume()
                            }
                        )
                    }
            ) {
                val horizontalPadding = leftAxisInsetPx
                val verticalPadding = with(density) { verticalChartPadding.toPx() }
                val chartWidth = size.width.coerceAtLeast(1f)
                val chartHeight = size.height - verticalPadding * 2f

                fun xForHour(hour: Int): Float = horizontalPadding + (hour / 23f) * chartWidth
                fun yFor(valueMg: Float): Float {
                    val ratio = ((valueMg - minY) / (maxY - minY)).coerceIn(0f, 1f)
                    return verticalPadding + chartHeight - ratio * chartHeight
                }

                val targetTop = yFor(targets.highMgDl)
                val targetBottom = yFor(targets.lowMgDl)
                drawRoundRect(
                    color = targetBandColor,
                    topLeft = Offset(horizontalPadding, targetTop),
                    size = Size(chartWidth, targetBottom - targetTop),
                    cornerRadius = CornerRadius(8.dp.toPx())
                )

                listOf(targets.lowMgDl, targets.highMgDl).forEach { guideValue ->
                    val y = yFor(guideValue)
                    drawLine(
                        color = gridLineColor.copy(alpha = 0.42f),
                        start = Offset(horizontalPadding, y),
                        end = Offset(horizontalPadding + chartWidth, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

            val p10Path = Path()
            var p10Started = false
            val p90Path = Path()
            var p90Started = false
            val medianPath = Path()
            var medianStarted = false
            var prevMedianPoint: Offset? = null
            var prevMedianValue = 0f
            val medianSegments = mutableListOf<Triple<Offset, Offset, Float>>()

            val upperIqr = mutableListOf<Offset>()
            val lowerIqr = mutableListOf<Offset>()

            agpByHour.forEach { bin ->
                val x = xForHour(bin.hour)

                val p10 = bin.p10MgDl
                if (p10 != null) {
                    val y = yFor(p10)
                    if (!p10Started) {
                        p10Path.moveTo(x, y)
                        p10Started = true
                    } else {
                        p10Path.lineTo(x, y)
                    }
                } else {
                    p10Started = false
                }

                val p90 = bin.p90MgDl
                if (p90 != null) {
                    val y = yFor(p90)
                    if (!p90Started) {
                        p90Path.moveTo(x, y)
                        p90Started = true
                    } else {
                        p90Path.lineTo(x, y)
                    }
                } else {
                    p90Started = false
                }

                val median = bin.medianMgDl
                if (median != null) {
                    val y = yFor(median)
                    if (!medianStarted) {
                        medianPath.moveTo(x, y)
                        medianStarted = true
                    } else {
                        medianPath.lineTo(x, y)
                    }
                    val point = Offset(x, y)
                    prevMedianPoint?.let { previous ->
                        medianSegments += Triple(previous, point, (median + prevMedianValue) / 2f)
                    }
                    prevMedianPoint = point
                    prevMedianValue = median
                } else {
                    medianStarted = false
                    prevMedianPoint = null
                }

                bin.p75MgDl?.let { upperIqr += Offset(x, yFor(it)) }
                bin.p25MgDl?.let { lowerIqr += Offset(x, yFor(it)) }
            }

            if (upperIqr.size >= 2 && lowerIqr.size >= 2) {
                val iqrPath = Path()
                iqrPath.moveTo(upperIqr.first().x, upperIqr.first().y)
                upperIqr.drop(1).forEach { iqrPath.lineTo(it.x, it.y) }
                lowerIqr.asReversed().forEach { iqrPath.lineTo(it.x, it.y) }
                iqrPath.close()
                drawPath(iqrPath, color = iqrBandColor, style = Fill)
            }

            drawPath(
                path = p10Path,
                color = p10P90Color,
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
            )
            drawPath(
                path = p90Path,
                color = p10P90Color,
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
            )
            if (trafficLineColors) {
                val medianFallbackArgb = medianColor.toArgb()
                medianSegments.forEach { (segmentStart, segmentEnd, valueMg) ->
                    drawLine(
                        color = Color(
                            tk.glucodata.GlucoseRangeColors.trafficColorForValue(
                                valueMg,
                                targets.lowMgDl,
                                targets.highMgDl,
                                targets.veryLowMgDl,
                                targets.veryHighMgDl,
                                isDarkTheme,
                                false,
                                medianFallbackArgb
                            )
                        ),
                        start = segmentStart,
                        end = segmentEnd,
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            } else {
                drawPath(
                    path = medianPath,
                    color = medianColor,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            tickHours.forEach { hour ->
                val x = xForHour(hour)
                drawLine(
                    color = gridLineColor,
                    start = Offset(x, verticalPadding),
                    end = Offset(x, verticalPadding + chartHeight),
                    strokeWidth = 1.dp.toPx()
                )
            }

            val selectedX = xForHour(selectedHour.coerceIn(0, 23))
            drawLine(
                color = selectorColor,
                start = Offset(selectedX, verticalPadding),
                end = Offset(selectedX, verticalPadding + chartHeight),
                strokeWidth = 1.5.dp.toPx()
            )
            agpByHour.getOrNull(selectedHour.coerceIn(0, 23))?.medianMgDl?.let { median ->
                drawCircle(
                    color = medianColor,
                    radius = 4.dp.toPx(),
                    center = Offset(selectedX, yFor(median))
                )
            }
            }

            if (chartSize.height > 0) {
                val verticalPaddingPx = with(density) { verticalChartPadding.toPx() }
                val chartHeightPx = (chartSize.height.toFloat() - (verticalPaddingPx * 2f)).coerceAtLeast(1f)

                fun yOffsetFor(valueMg: Float): Float {
                    val ratio = ((valueMg - minY) / (maxY - minY)).coerceIn(0f, 1f)
                    return verticalPaddingPx + chartHeightPx - ratio * chartHeightPx
                }

                axisLabelValues.forEach { value ->
                    val yPx = yOffsetFor(value)
                    Text(
                        text = formatMgDl(value, unit),
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = 4.dp, y = with(density) { yPx.toDp() - 8.dp })
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            tickHours.forEach { hour ->
                Text(
                    text = String.format(Locale.getDefault(), "%02d:00", hour),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            LegendDot(
                color = targetBandColor,
                label = "${stringResource(R.string.in_range)} ${formatMgDl(targets.lowMgDl, unit)}-${formatMgDl(targets.highMgDl, unit)}"
            )
            LegendDot(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), label = "P10-90")
            LegendDot(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), label = "IQR")
            LegendDot(color = MaterialTheme.colorScheme.primary, label = stringResource(R.string.median))
            LegendDot(color = selectorColor, label = stringResource(R.string.stats_selected))
        }

    }
}

@Composable
private fun DailyTrendSparkline(
    dailyStats: List<DailyStats>,
    targets: StatsTargets,
    unit: GlucoseUnit,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (dailyStats.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.stats_no_daily_data),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    var chartSize by remember { mutableStateOf(IntSize.Zero) }
    val safeSelectedIndex = selectedIndex.coerceIn(0, dailyStats.lastIndex)
    val values = remember(dailyStats) { dailyStats.map { it.averageMgDl } }
    val normalizedXPositions = remember(dailyStats) {
        val first = dailyStats.firstOrNull()?.date?.toEpochDay()?.toFloat() ?: 0f
        val last = dailyStats.lastOrNull()?.date?.toEpochDay()?.toFloat() ?: first
        val span = (last - first).coerceAtLeast(1f)
        dailyStats.map { ((it.date.toEpochDay().toFloat() - first) / span).coerceIn(0f, 1f) }
    }
    val lowAnchor = targets.lowMgDl
    val highAnchor = targets.highMgDl
    val targetSpan = (highAnchor - lowAnchor).coerceAtLeast(40f)
    val yPadding = (targetSpan * 0.2f).coerceIn(12f, 40f)
    val minY = minOf(values.minOrNull() ?: lowAnchor, lowAnchor - yPadding)
    val maxY = maxOf(values.maxOrNull() ?: highAnchor, highAnchor + yPadding).coerceAtLeast(minY + 10f)
    val areaBrush = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.04f)
        )
    )
    val lineColor = MaterialTheme.colorScheme.primary
    val selectorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val gridLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
    val density = LocalDensity.current
    val verticalChartPadding = 6.dp
    val axisLabelValues = remember(targets, minY, maxY) {
        listOf(
            maxY,
            targets.highMgDl,
            targets.lowMgDl,
            minY
        )
            .map { it.coerceIn(minY, maxY) }
            .distinct()
            .sortedDescending()
            .fold(mutableListOf<Float>()) { acc, value ->
                if (acc.isEmpty() || abs(acc.last() - value) >= 6f) {
                    acc.add(value)
                }
                acc
            }
    }
    val tickIndexes = remember(dailyStats) {
        if (dailyStats.size <= 1) listOf(0)
        else listOf(0, dailyStats.lastIndex / 2, dailyStats.lastIndex).distinct()
    }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("MMM d") }

    val indexFromX = { x: Float ->
        if (chartSize.width <= 0 || dailyStats.size <= 1) 0
        else {
            val chartWidth = chartSize.width.toFloat().coerceAtLeast(1f)
            val ratio = (x / chartWidth).coerceIn(0f, 1f)
            normalizedXPositions.indices.minByOrNull { index ->
                abs(normalizedXPositions[index] - ratio)
            } ?: 0
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { chartSize = it }
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(dailyStats, chartSize) {
                        detectTapGestures { offset ->
                            onSelectedIndexChange(indexFromX(offset.x))
                        }
                    }
                    .pointerInput(dailyStats, chartSize) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset -> onSelectedIndexChange(indexFromX(offset.x)) },
                            onHorizontalDrag = { change, _ ->
                                onSelectedIndexChange(indexFromX(change.position.x))
                                change.consume()
                            }
                        )
                    }
            ) {
                val horizontalPadding = 0f
                val verticalPadding = with(density) { verticalChartPadding.toPx() }
                val width = size.width.coerceAtLeast(1f)
                val height = size.height - verticalPadding * 2f

                fun xForIndex(index: Int): Float = horizontalPadding + (normalizedXPositions.getOrElse(index) { 0f } * width)
                fun yFor(value: Float): Float {
                    val ratio = ((value - minY) / (maxY - minY)).coerceIn(0f, 1f)
                    return verticalPadding + height - ratio * height
                }

                listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { ratio ->
                    val x = horizontalPadding + width * ratio
                    drawLine(
                        color = gridLineColor,
                        start = Offset(x, verticalPadding),
                        end = Offset(x, verticalPadding + height),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                val targetTop = yFor(targets.highMgDl)
                val targetBottom = yFor(targets.lowMgDl)
                drawRoundRect(
                    color = Color(0xFF43A047).copy(alpha = 0.14f),
                    topLeft = Offset(horizontalPadding, targetTop),
                    size = Size(width, targetBottom - targetTop),
                    cornerRadius = CornerRadius(8.dp.toPx())
                )

                listOf(targets.lowMgDl, targets.highMgDl).forEach { guideValue ->
                    val y = yFor(guideValue)
                    drawLine(
                        color = gridLineColor.copy(alpha = 0.4f),
                        start = Offset(horizontalPadding, y),
                        end = Offset(horizontalPadding + width, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                val linePath = Path()
                val areaPath = Path()

                dailyStats.forEachIndexed { index, day ->
                    val x = xForIndex(index)
                    val y = yFor(day.averageMgDl)

                    if (index == 0) {
                        linePath.moveTo(x, y)
                        areaPath.moveTo(x, y)
                    } else {
                        linePath.lineTo(x, y)
                        areaPath.lineTo(x, y)
                    }
                }

                areaPath.lineTo(horizontalPadding + width, verticalPadding + height)
                areaPath.lineTo(horizontalPadding, verticalPadding + height)
                areaPath.close()

                drawPath(path = areaPath, brush = areaBrush)
                drawPath(
                    path = linePath,
                    color = lineColor,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )

                dailyStats.forEachIndexed { index, day ->
                    val x = xForIndex(index)
                    val y = yFor(day.averageMgDl)
                    // Same scale as the day-by-day grid, so one day is one colour
                    // whichever view you look at it in.
                    val tone = tirHeatColor(day.inRangePercent)
                    drawCircle(
                        color = tone,
                        radius = if (index == safeSelectedIndex) 4.8.dp.toPx() else 3.dp.toPx(),
                        center = Offset(x, y)
                    )
                }

                val selectedX = xForIndex(safeSelectedIndex)
                drawLine(
                    color = selectorColor,
                    start = Offset(selectedX, verticalPadding),
                    end = Offset(selectedX, verticalPadding + height),
                    strokeWidth = 1.5.dp.toPx()
                )
            }

            if (chartSize.height > 0) {
                val verticalPaddingPx = with(density) { verticalChartPadding.toPx() }
                val chartHeightPx = (chartSize.height.toFloat() - (verticalPaddingPx * 2f)).coerceAtLeast(1f)

                fun yOffsetFor(valueMg: Float): Float {
                    val ratio = ((valueMg - minY) / (maxY - minY)).coerceIn(0f, 1f)
                    return verticalPaddingPx + chartHeightPx - ratio * chartHeightPx
                }

                axisLabelValues.forEach { value ->
                    val yPx = yOffsetFor(value)
                    Text(
                        text = formatMgDl(value, unit),
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = 4.dp, y = with(density) { yPx.toDp() - 8.dp })
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            tickIndexes.forEach { index ->
                Text(
                    text = dailyStats[index].date.format(dateFormatter),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LegendDot(color = tirHeatColor(85f), label = ">=70%")
            LegendDot(color = tirHeatColor(60f), label = "50-69%")
            LegendDot(color = tirHeatColor(30f), label = "<50%")
        }

        Spacer(modifier = Modifier.height(2.dp))
    }
}

@Composable
private fun TemperatureOverviewCard(temperaturePoints: List<TemperaturePoint>) {
    val min = temperaturePoints.minOf { it.temperatureCelsius }
    val max = temperaturePoints.maxOf { it.temperatureCelsius }
    val avg = temperaturePoints.map { it.temperatureCelsius }.average().toFloat()
    val sampledPoints = remember(temperaturePoints) {
        downsampleTemperaturePoints(temperaturePoints, maxPoints = 240)
    }
    var selectedIndex by remember(sampledPoints) {
        mutableStateOf<Int?>(null)
    }
    val resolvedIndex = (selectedIndex ?: sampledPoints.lastIndex).coerceIn(0, sampledPoints.lastIndex)
    val selected = sampledPoints.getOrNull(resolvedIndex) ?: sampledPoints.last()
    val selectedTone by animateColorAsState(
        targetValue = if (selectedIndex == null) Color(0xFF00838F) else Color(0xFF0277BD),
        label = "tempSelectedTone"
    )
    val timeLabel = remember(selected.timestamp) {
        val formatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")
        Instant.ofEpochMilli(selected.timestamp).atZone(ZoneId.systemDefault()).format(formatter)
    }

    Card(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.stats_temperature_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium)
                    )
                    Text(
                        text = if (selectedIndex == null) {
                            stringResource(R.string.stats_current_reading)
                        } else {
                            stringResource(R.string.stats_selected_reading)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "${String.format(Locale.getDefault(), "%.1f", selected.temperatureCelsius)} °C",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFeatureSettings = "tnum",
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = selectedTone
                )
            }

            TemperatureSparkline(
                points = sampledPoints,
                selectedIndex = resolvedIndex,
                onSelectedIndexChange = { selectedIndex = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(166.dp)
            )

            Text(
                text = stringResource(
                    R.string.stats_temperature_vs_avg,
                    timeLabel,
                    String.format(Locale.getDefault(), "%.1f", selected.temperatureCelsius - avg)
                ),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TemperatureStat(
                    label = stringResource(R.string.min),
                    value = "${String.format(Locale.getDefault(), "%.1f", min)} °C",
                    modifier = Modifier.weight(1f)
                )
                TemperatureStat(
                    label = stringResource(R.string.avg),
                    value = "${String.format(Locale.getDefault(), "%.1f", avg)} °C",
                    modifier = Modifier.weight(1f)
                )
                TemperatureStat(
                    label = stringResource(R.string.max),
                    value = "${String.format(Locale.getDefault(), "%.1f", max)} °C",
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun TemperatureSparkline(
    points: List<TemperaturePoint>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (points.isEmpty()) return

    val minT = points.minOf { it.temperatureCelsius }
    val maxT = points.maxOf { it.temperatureCelsius }
    val range = (maxT - minT).coerceAtLeast(0.1f)
    var chartSize by remember { mutableStateOf(IntSize.Zero) }
    val maxVisibleSpan = remember(points) { points.lastIndex.toFloat().coerceAtLeast(1f) }
    val minVisibleSpan = remember(points, maxVisibleSpan) {
        if (points.size <= 16) {
            maxVisibleSpan
        } else {
            maxOf(maxVisibleSpan * 0.18f, 12f)
        }
    }
    var viewportStart by remember(points) { mutableFloatStateOf(0f) }
    var visibleSpan by remember(points, maxVisibleSpan) { mutableFloatStateOf(maxVisibleSpan) }
    val lineColor = Color(0xFF00838F)
    val selectorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)

    val clampedViewportStart = { start: Float, span: Float ->
        start.coerceIn(0f, (maxVisibleSpan - span).coerceAtLeast(0f))
    }
    val indexFromX = { x: Float ->
        if (chartSize.width <= 0 || points.size <= 1) {
            0
        } else {
            val widthPx = chartSize.width.toFloat().coerceAtLeast(1f)
            val ratio = (x / widthPx).coerceIn(0f, 1f)
            (viewportStart + (ratio * visibleSpan)).roundToInt().coerceIn(0, points.lastIndex)
        }
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { chartSize = it }
            .pointerInput(points, chartSize, viewportStart, visibleSpan) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    onSelectedIndexChange(indexFromX(firstDown.position.x))

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressedPointers = event.changes.filter { it.pressed }
                        if (pressedPointers.isEmpty()) break

                        if (pressedPointers.size > 1) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val widthPx = chartSize.width.toFloat().coerceAtLeast(1f)
                            val previousSpan = visibleSpan
                            val updatedSpan = (previousSpan / zoomChange).coerceIn(minVisibleSpan, maxVisibleSpan)
                            val focusRatio = (centroid.x / widthPx).coerceIn(0f, 1f)
                            val focusIndex = viewportStart + (previousSpan * focusRatio)
                            val startAfterZoom = focusIndex - (updatedSpan * focusRatio)
                            val startAfterPan = startAfterZoom - ((panChange.x / widthPx) * updatedSpan)
                            visibleSpan = updatedSpan
                            viewportStart = clampedViewportStart(startAfterPan, updatedSpan)

                            event.changes.forEach { change ->
                                if (change.position != change.previousPosition) {
                                    change.consume()
                                }
                            }
                        } else {
                            val change = pressedPointers.first()
                            if (change.position != change.previousPosition) {
                                onSelectedIndexChange(indexFromX(change.position.x))
                                change.consume()
                            }
                        }
                    }
                }
            }
    ) {
        val horizontalPadding = 0.dp.toPx()
        val verticalPadding = 8.dp.toPx()
        val width = size.width - horizontalPadding * 2f
        val height = size.height - verticalPadding * 2f
        val startIndex = viewportStart.toInt().coerceIn(0, points.lastIndex)
        val endIndex = (viewportStart + visibleSpan).toInt()
            .coerceAtLeast(startIndex + 1)
            .coerceAtMost(points.lastIndex)

        fun xForIndex(index: Int): Float {
            if (visibleSpan <= 0f) return horizontalPadding
            val ratio = ((index - viewportStart) / visibleSpan).coerceIn(0f, 1f)
            return horizontalPadding + (ratio * width)
        }

        val path = Path()
        val areaPath = Path()

        (startIndex..endIndex).forEach { index ->
            val point = points[index]
            val ratio = ((point.temperatureCelsius - minT) / range).coerceIn(0f, 1f)
            val x = xForIndex(index)
            val y = verticalPadding + height - ratio * height
            if (index == startIndex) path.moveTo(x, y) else path.lineTo(x, y)
            if (index == startIndex) areaPath.moveTo(x, y) else areaPath.lineTo(x, y)
        }

        areaPath.lineTo(xForIndex(endIndex), verticalPadding + height)
        areaPath.lineTo(horizontalPadding, verticalPadding + height)
        areaPath.close()

        drawPath(
            path = areaPath,
            brush = Brush.verticalGradient(
                listOf(
                    lineColor.copy(alpha = 0.22f),
                    lineColor.copy(alpha = 0.04f)
                )
            )
        )
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )

        val safeIndex = selectedIndex.coerceIn(0, points.lastIndex)
        val selectedPoint = points[safeIndex]
        if (safeIndex in startIndex..endIndex) {
            val ratio = ((selectedPoint.temperatureCelsius - minT) / range).coerceIn(0f, 1f)
            val selectedX = xForIndex(safeIndex)
            val selectedY = verticalPadding + height - ratio * height

            drawLine(
                color = selectorColor,
                start = Offset(selectedX, verticalPadding),
                end = Offset(selectedX, verticalPadding + height),
                strokeWidth = 1.2.dp.toPx()
            )
            drawCircle(
                color = lineColor,
                radius = 4.dp.toPx(),
                center = Offset(selectedX, selectedY)
            )
        }
    }
}

@Composable
private fun TemperatureStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun InsightsCard(insights: List<StatsInsight>) {
    AnimatedVisibility(
        visible = insights.isNotEmpty(),
        enter = fadeIn(animationSpec = tween(250)) + slideInVertically(initialOffsetY = { it / 4 }),
        exit = fadeOut(animationSpec = tween(180)) + slideOutVertically(targetOffsetY = { it / 5 })
    ) {
        Card(
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 26.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.insights),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                )

                Spacer(modifier = Modifier.height(4.dp))

                insights.forEachIndexed { index, insight ->
                    InsightRow(insight = insight)
                    if (index < insights.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                            modifier = Modifier.padding(start = 52.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightRow(insight: StatsInsight) {
    val (contentColor, icon) = when (insight.severity) {
        InsightSeverity.POSITIVE -> TirInRangeColor to Icons.Default.Shield
        InsightSeverity.ATTENTION -> TirHighColor to Icons.Default.WarningAmber
        InsightSeverity.CAUTION -> TirVeryHighColor to Icons.Default.ErrorOutline
    }
    val bgColor = contentColor.copy(alpha = 0.16f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = CircleShape,
            color = bgColor,
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = insight.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor
            )
            Text(
                text = insight.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun downsampleTemperaturePoints(
    points: List<TemperaturePoint>,
    maxPoints: Int
): List<TemperaturePoint> {
    if (points.size <= maxPoints || maxPoints < 8) return points

    val bucketSize = points.size.toFloat() / maxPoints.toFloat()
    val reduced = ArrayList<TemperaturePoint>(maxPoints + 2)
    var cursor = 0f

    while (cursor < points.size) {
        val start = cursor.toInt().coerceIn(0, points.lastIndex)
        val endExclusive = (cursor + bucketSize).toInt().coerceIn(start + 1, points.size)
        val bucket = points.subList(start, endExclusive)
        val low = bucket.minByOrNull { it.temperatureCelsius } ?: bucket.first()
        val high = bucket.maxByOrNull { it.temperatureCelsius } ?: bucket.last()
        if (low.timestamp <= high.timestamp) {
            reduced += low
            if (high !== low) reduced += high
        } else {
            reduced += high
            if (high !== low) reduced += low
        }
        cursor += bucketSize
    }

    return reduced.distinctBy { it.timestamp }.sortedBy { it.timestamp }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun formatMgDl(valueMgDl: Float, unit: GlucoseUnit): String {
    return GlucoseFormatter.formatFromMgDl(
        valueMgDl = valueMgDl,
        isMmol = unit == GlucoseUnit.MMOL
    )
}
