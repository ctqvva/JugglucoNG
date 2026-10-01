package tk.glucodata.ui.journal

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.luminance
import tk.glucodata.ui.LocalDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import tk.glucodata.R
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.ui.ChartViewportSnapshot
import kotlin.math.roundToInt

fun journalReachActionTypes(): List<JournalEntryType> = listOf(
    JournalEntryType.NOTE,
    JournalEntryType.ACTIVITY,
    JournalEntryType.FINGERSTICK,
    JournalEntryType.CARBS,
    JournalEntryType.INSULIN
)

@Composable
fun JournalEntryType.journalActionLabel(): String = when (this) {
    JournalEntryType.INSULIN -> stringResource(R.string.journal_type_insulin)
    JournalEntryType.CARBS -> stringResource(R.string.journal_type_food)
    JournalEntryType.FINGERSTICK -> stringResource(R.string.journal_type_bg_short)
    JournalEntryType.ACTIVITY -> stringResource(R.string.journal_type_activity)
    JournalEntryType.NOTE -> stringResource(R.string.journal_type_note)
}

fun JournalEntryType.journalActionIcon(): ImageVector = when (this) {
    JournalEntryType.INSULIN -> Icons.Default.Vaccines
    JournalEntryType.CARBS -> Icons.Default.Restaurant
    JournalEntryType.FINGERSTICK -> Icons.Default.Bloodtype
    JournalEntryType.ACTIVITY -> Icons.Default.DirectionsRun
    JournalEntryType.NOTE -> Icons.AutoMirrored.Filled.Label
}

@Composable
fun JournalFloatingActionMenu(
    visible: Boolean,
    selectedTimestamp: Long,
    onDismissRequest: () -> Unit,
    viewportSnapshot: ChartViewportSnapshot?,
    onTypeSelected: (JournalEntryType) -> Unit,
    menuTopOffset: Dp = 86.dp,
    menuItemSpacing: Dp = 6.dp,
    menuYOffset: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val anchorFraction = remember(selectedTimestamp, viewportSnapshot) {
        viewportSnapshot
            ?.takeIf { it.endMillis > it.startMillis }
            ?.let { snapshot ->
                ((selectedTimestamp - snapshot.startMillis).toFloat() /
                    (snapshot.endMillis - snapshot.startMillis).toFloat()).coerceIn(0f, 1f)
            }
    }
    val menuReveal = remember { Animatable(0f) }
    LaunchedEffect(visible, selectedTimestamp, anchorFraction) {
        if (visible && anchorFraction != null) {
            menuReveal.snapTo(0f)
            menuReveal.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        } else {
            menuReveal.animateTo(0f, animationSpec = tween(durationMillis = 120))
        }
    }
    val menuProgress = menuReveal.value
    val menuScale = 0.86f + (0.14f * menuProgress)

    val isDark = LocalDarkTheme.current ||
        MaterialTheme.colorScheme.surface.luminance() < 0.5f ||
        isSystemInDarkTheme()

    if (anchorFraction != null && (visible || menuProgress > 0.01f)) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val popupWidth = maxWidth
            val popupHeight = maxHeight
            Popup(
                onDismissRequest = onDismissRequest,
                properties = PopupProperties(focusable = true)
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .size(popupWidth, popupHeight)
                        .graphicsLayer { alpha = menuProgress.coerceIn(0f, 1f) }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismissRequest
                        )
                ) {
                    val density = LocalDensity.current
                    val resolvedAnchorFraction = anchorFraction
                    val containerWidthPx = with(density) { maxWidth.toPx() }
                    val containerHeightPx = with(density) { maxHeight.toPx() }
                    val menuWidth = 260.dp
                    val menuWidthPx = with(density) { menuWidth.toPx() }
                    val menuHeight = 144.dp
                    val menuHeightPx = with(density) { menuHeight.toPx() }
                    val edgePaddingPx = with(density) { 10.dp.toPx() }
                    val anchorGapPx = with(density) { 12.dp.toPx() }
                    val menuTopPx = with(density) { menuTopOffset.toPx() }
                    val menuYOffsetPx = with(density) { menuYOffset.toPx() }
                    val anchorX = containerWidthPx * resolvedAnchorFraction
                    val placeMenuLeft = resolvedAnchorFraction > 0.52f
                    val desiredX = if (placeMenuLeft) {
                        anchorX - menuWidthPx - anchorGapPx
                    } else {
                        anchorX + anchorGapPx
                    }
                    val clampedX = desiredX.coerceIn(
                        edgePaddingPx,
                        (containerWidthPx - menuWidthPx - edgePaddingPx).coerceAtLeast(edgePaddingPx)
                    )
                    val clampedY = (menuTopPx + menuYOffsetPx).coerceIn(
                        edgePaddingPx,
                        (containerHeightPx - menuHeightPx - edgePaddingPx).coerceAtLeast(edgePaddingPx)
                    )

                    JournalFloatingActionMenuCard(
                        itemSpacing = menuItemSpacing.coerceAtMost(8.dp),
                        onTypeSelected = onTypeSelected,
                        isDark = isDark,
                        modifier = Modifier
                            .offset {
                                androidx.compose.ui.unit.IntOffset(
                                    x = clampedX.roundToInt(),
                                    y = clampedY.roundToInt()
                                )
                            }
                            .graphicsLayer {
                                alpha = menuProgress
                                scaleX = menuScale
                                scaleY = menuScale
                                translationY = (8.dp.toPx() * (1f - menuProgress))
                            }
                            .width(menuWidth)
                    )
                }
            }
        }
    }
}

@Composable
fun JournalFloatingActionMenuCard(
    itemSpacing: Dp = 6.dp,
    onTypeSelected: (JournalEntryType) -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = LocalDarkTheme.current || MaterialTheme.colorScheme.surface.luminance() < 0.5f || isSystemInDarkTheme()
) {
    val cardBackgroundColor = if (isDark) Color(0xF4141720) else Color(0xF8F8FAFC)
    val cardBorderColor = if (isDark) Color(0x38FFFFFF) else Color(0x22000000)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = cardBackgroundColor,
        border = BorderStroke(1.dp, cardBorderColor),
        shadowElevation = if (isDark) 10.dp else 6.dp,
        tonalElevation = if (isDark) 8.dp else 3.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(itemSpacing)
        ) {
            // Riga 1: Attività (sinistra) e Cibo (destra)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(itemSpacing)
            ) {
                JournalPopupActionButton(
                    actionType = JournalEntryType.ACTIVITY,
                    onClick = { onTypeSelected(JournalEntryType.ACTIVITY) },
                    modifier = Modifier.weight(1f),
                    isDark = isDark
                )
                JournalPopupActionButton(
                    actionType = JournalEntryType.CARBS,
                    onClick = { onTypeSelected(JournalEntryType.CARBS) },
                    modifier = Modifier.weight(1f),
                    isDark = isDark
                )
            }
            // Riga 2: Nota (sinistra) e Glicemia (destra)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(itemSpacing)
            ) {
                JournalPopupActionButton(
                    actionType = JournalEntryType.NOTE,
                    onClick = { onTypeSelected(JournalEntryType.NOTE) },
                    modifier = Modifier.weight(1f),
                    isDark = isDark
                )
                JournalPopupActionButton(
                    actionType = JournalEntryType.FINGERSTICK,
                    onClick = { onTypeSelected(JournalEntryType.FINGERSTICK) },
                    modifier = Modifier.weight(1f),
                    isDark = isDark
                )
            }
            // Riga 3: Insulina (a tutta larghezza)
            JournalPopupActionButton(
                actionType = JournalEntryType.INSULIN,
                onClick = { onTypeSelected(JournalEntryType.INSULIN) },
                modifier = Modifier.fillMaxWidth(),
                isDark = isDark
            )
        }
    }
}

@Composable
fun JournalPopupActionButton(
    actionType: JournalEntryType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = LocalDarkTheme.current || MaterialTheme.colorScheme.surface.luminance() < 0.5f || isSystemInDarkTheme()
) {
    val view = LocalView.current
    val actionTint = journalTypeColor(actionType)
    
    // Tema scuro: sfondo scuro a contrasto (#222633) con testo chiaro
    // Tema chiaro: colori più tenui (tonalità sfumata) e testo nero
    val containerColor = if (isDark) {
        Color(0xFF222633)
    } else {
        androidx.compose.ui.graphics.lerp(Color(0xFFF1F4F9), actionTint, 0.12f)
    }
    val borderColor = if (isDark) {
        actionTint.copy(alpha = 0.42f)
    } else {
        actionTint.copy(alpha = 0.35f)
    }
    val textColor = if (isDark) Color(0xFFF0F3F8) else Color(0xFF0F172A)

    Surface(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        },
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = if (isDark) 2.dp else 1.dp,
        modifier = modifier.height(38.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = actionType.journalActionIcon(),
                contentDescription = null,
                tint = actionTint,
                modifier = Modifier.size(21.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = actionType.journalActionLabel(),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun JournalExpandableFab(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onTypeSelected: (JournalEntryType) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val actionTypes = remember { journalReachActionTypes() }
    val menuReveal = remember { Animatable(0f) }
    LaunchedEffect(expanded) {
        menuReveal.animateTo(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = if (expanded) {
                spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                )
            } else {
                tween(durationMillis = 140)
            }
        )
    }
    val menuProgress = menuReveal.value
    val rowTravelPx = with(density) { 18.dp.toPx() }
    val itemLiftPx = with(density) { 18.dp.toPx() }
    Box(modifier = modifier) {
        if (expanded || menuProgress > 0.01f) {
            JournalFabMenuPopup(
                menuProgress = menuProgress,
                onDismissRequest = { onExpandedChange(false) },
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                actionTypes.forEachIndexed { index, actionType ->
                    val itemProgress = ((menuProgress - (index * 0.07f)) / 0.72f).coerceIn(0f, 1f)
                    JournalActionMenuRow(
                        actionType = actionType,
                        placeIconAfterLabel = true,
                        itemProgress = itemProgress,
                        rowTravelPx = rowTravelPx,
                        itemLiftPx = itemLiftPx,
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onTypeSelected(actionType)
                            onExpandedChange(false)
                        }
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onExpandedChange(!expanded)
            },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(20.dp),
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
            modifier = Modifier.graphicsLayer {
                scaleX = 1f + (0.04f * menuProgress)
                scaleY = 1f + (0.04f * menuProgress)
            }
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.Close else Icons.Default.Add,
                contentDescription = stringResource(if (expanded) R.string.close else R.string.additem),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun JournalActionMenuRow(
    actionType: JournalEntryType,
    placeIconAfterLabel: Boolean,
    itemProgress: Float,
    rowTravelPx: Float,
    itemLiftPx: Float,
    onClick: () -> Unit
) {
    val label = actionType.journalActionLabel()
    val actionTint = journalTypeColor(actionType)
    val iconContainerColor = journalTypeSelectedContainerColor(actionType)
    val labelContainerColor = journalTypeSubtleContainerColor(actionType)
    Row(
        modifier = Modifier
            .wrapContentWidth(if (placeIconAfterLabel) Alignment.End else Alignment.Start)
            .graphicsLayer {
                alpha = itemProgress
                translationX = (if (placeIconAfterLabel) rowTravelPx else -rowTravelPx) * (1f - itemProgress)
                translationY = itemLiftPx * (1f - itemProgress)
                scaleX = 0.78f + (0.22f * itemProgress)
                scaleY = 0.78f + (0.22f * itemProgress)
                rotationZ = (if (placeIconAfterLabel) -7f else 7f) * (1f - itemProgress)
            }
            // The whole row is one touch target: without this, the 10dp gap
            // between the label pill and the icon belongs to nothing, and a
            // tap there falls through to the content behind the menu (journal
            // rows, or the dashboard chart's calibration tap). No indication:
            // the pills keep their own ripples.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!placeIconAfterLabel) {
            JournalActionFab(actionType, label, actionTint, iconContainerColor, onClick)
        }
        // Clickable Surface overload, not an outer .clickable: the ripple
        // must be clipped to the pill, otherwise it paints a square.
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(18.dp),
            color = labelContainerColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        if (placeIconAfterLabel) {
            JournalActionFab(actionType, label, actionTint, iconContainerColor, onClick)
        }
    }
}

@Composable
private fun JournalActionFab(
    actionType: JournalEntryType,
    label: String,
    actionTint: androidx.compose.ui.graphics.Color,
    iconContainerColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    SmallFloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = iconContainerColor,
        contentColor = actionTint,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp)
    ) {
        Icon(
            imageVector = actionType.journalActionIcon(),
            contentDescription = label,
            modifier = Modifier.size(26.dp)
        )
    }
}

@Preview(name = "Glucose Tap Menu - Dark", showBackground = true, backgroundColor = 0xFF121316)
@Composable
private fun JournalFloatingActionMenuCardPreviewDark() {
    MaterialTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            JournalFloatingActionMenuCard(
                onTypeSelected = {},
                isDark = true
            )
        }
    }
}

@Preview(name = "Glucose Tap Menu - Light", showBackground = true, backgroundColor = 0xFFF5F5F5)
@Composable
private fun JournalFloatingActionMenuCardPreviewLight() {
    MaterialTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            JournalFloatingActionMenuCard(
                onTypeSelected = {},
                isDark = false
            )
        }
    }
}

internal fun generateDebugSampleGlucoseHistory(unit: String): List<tk.glucodata.ui.GlucosePoint> {
    val now = System.currentTimeMillis()
    val isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit)
    val points = mutableListOf<tk.glucodata.ui.GlucosePoint>()
    for (i in 72 downTo 0) {
        val t = now - i * 5 * 60 * 1000L
        val phase = (i.toDouble() / 18.0) * Math.PI
        val baseMg = 125.0 + 35.0 * kotlin.math.sin(phase) + 12.0 * kotlin.math.cos(phase * 2.3)
        val value = if (isMmol) tk.glucodata.ui.util.GlucoseFormatter.mgToMmol(baseMg.toFloat()) else baseMg.toFloat()
        val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
        points.add(
            tk.glucodata.ui.GlucosePoint(
                value = value,
                time = timeStr,
                timestamp = t,
                rawValue = value
            )
        )
    }
    return points
}


