package tk.glucodata.ui.journal

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.LunchDining
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.ui.ChartViewportSnapshot
import kotlin.math.roundToInt

private enum class MealRow { NEW, CURRENT }

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
    JournalEntryType.CARBS -> Icons.Default.LunchDining
    JournalEntryType.FINGERSTICK -> Icons.Default.Bloodtype
    JournalEntryType.ACTIVITY -> Icons.Default.DirectionsRun
    JournalEntryType.NOTE -> Icons.AutoMirrored.Filled.Label
}

@Composable
fun JournalFloatingActionMenu(
    visible: Boolean,
    selectedTimestamp: Long,
    viewportSnapshot: ChartViewportSnapshot?,
    onTypeSelected: (JournalEntryType) -> Unit,
    menuTopOffset: Dp = 86.dp,
    menuItemSpacing: Dp = 10.dp,
    menuYOffset: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val actionTypes = remember { journalReachActionTypes() }
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
    val menuScale = 0.82f + (0.18f * menuProgress)

    if (anchorFraction != null && (visible || menuProgress > 0.01f)) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer { alpha = menuProgress.coerceIn(0f, 1f) }
        ) {
            val density = LocalDensity.current
            val resolvedAnchorFraction = anchorFraction ?: return@BoxWithConstraints
            val containerWidthPx = with(density) { maxWidth.toPx() }
            val containerHeightPx = with(density) { maxHeight.toPx() }
            val menuWidthPx = with(density) { 176.dp.toPx() }
            val edgePaddingPx = with(density) { 12.dp.toPx() }
            val anchorGapPx = with(density) { 14.dp.toPx() }
            val menuTopPx = with(density) { menuTopOffset.toPx() }
            val menuHeightPx = with(density) { (252.dp + (menuItemSpacing * 4)).toPx() }
            val menuYOffsetPx = with(density) { menuYOffset.toPx() }
            val rowTravelPx = with(density) { 18.dp.toPx() }
            val itemLiftPx = with(density) { 16.dp.toPx() }
            val anchorX = containerWidthPx * resolvedAnchorFraction
            val placeMenuLeft = resolvedAnchorFraction > 0.56f
            val desiredX = if (placeMenuLeft) {
                anchorX - menuWidthPx - anchorGapPx
            } else {
                anchorX + anchorGapPx
            }
            val clampedX = desiredX.coerceIn(
                edgePaddingPx,
                (containerWidthPx - menuWidthPx - edgePaddingPx).coerceAtLeast(edgePaddingPx)
            )
            val clampedY = menuTopPx.coerceIn(
                edgePaddingPx,
                (containerHeightPx - menuHeightPx).coerceAtLeast(edgePaddingPx)
            )

            Column(
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
                        translationY = menuYOffsetPx + (12.dp.toPx() * (1f - menuProgress))
                    }
                    .width(176.dp),
                horizontalAlignment = if (placeMenuLeft) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(menuItemSpacing)
            ) {
                actionTypes.forEachIndexed { index, actionType ->
                    val itemProgress = ((menuProgress - (index * 0.08f)) / 0.92f).coerceIn(0f, 1f)
                    JournalActionMenuRow(
                        actionType = actionType,
                        placeIconAfterLabel = placeMenuLeft,
                        itemProgress = itemProgress,
                        rowTravelPx = rowTravelPx,
                        itemLiftPx = itemLiftPx,
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onTypeSelected(actionType)
                        }
                    )
                }
            }
        }
    }
}

/**
 * @param onMealSelected when set, a "New meal" row sits right above Insulin — a meal is composed
 *   while cooking and logged later, so it is not an entry type.
 * @param currentMealLabel the open meal most recently worked on; with [onCurrentMealSelected] it
 *   adds a "Current meal: …" row between New meal and Insulin that jumps straight back into it.
 */
@Composable
fun JournalExpandableFab(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onTypeSelected: (JournalEntryType) -> Unit,
    modifier: Modifier = Modifier,
    onMealSelected: (() -> Unit)? = null,
    currentMealLabel: String? = null,
    onCurrentMealSelected: (() -> Unit)? = null
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
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (expanded || menuProgress > 0.01f) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.graphicsLayer {
                    alpha = menuProgress.coerceIn(0f, 1f)
                    translationY = 8.dp.toPx() * (1f - menuProgress)
                }
            ) {
                // Top to bottom: the entry types, with the meal rows slotted in right above Insulin
                // (Insulin stays closest to the button).
                val rows: List<Any> = buildList {
                    actionTypes.forEach { type ->
                        if (type == JournalEntryType.INSULIN) {
                            if (onMealSelected != null) add(MealRow.NEW)
                            if (currentMealLabel != null && onCurrentMealSelected != null) add(MealRow.CURRENT)
                        }
                        add(type)
                    }
                }
                rows.forEachIndexed { index, row ->
                    val itemProgress = ((menuProgress - (index * 0.07f)) / 0.72f).coerceIn(0f, 1f)
                    when (row) {
                        is JournalEntryType -> JournalActionMenuRow(
                            actionType = row,
                            placeIconAfterLabel = true,
                            itemProgress = itemProgress,
                            rowTravelPx = rowTravelPx,
                            itemLiftPx = itemLiftPx,
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onTypeSelected(row)
                                onExpandedChange(false)
                            }
                        )
                        MealRow.NEW -> JournalMenuRow(
                            label = stringResource(R.string.meal_new),
                            icon = Icons.Default.Restaurant,
                            actionTint = journalTypeColor(JournalEntryType.CARBS),
                            iconContainerColor = journalTypeSelectedContainerColor(JournalEntryType.CARBS),
                            labelContainerColor = journalTypeSubtleContainerColor(JournalEntryType.CARBS),
                            placeIconAfterLabel = true,
                            itemProgress = itemProgress,
                            rowTravelPx = rowTravelPx,
                            itemLiftPx = itemLiftPx,
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onMealSelected?.invoke()
                                onExpandedChange(false)
                            }
                        )
                        MealRow.CURRENT -> JournalMenuRow(
                            label = stringResource(R.string.meal_current_prefix, currentMealLabel.orEmpty()),
                            icon = Icons.Default.Restaurant,
                            actionTint = journalTypeColor(JournalEntryType.CARBS),
                            iconContainerColor = journalTypeSelectedContainerColor(JournalEntryType.CARBS),
                            labelContainerColor = journalTypeSubtleContainerColor(JournalEntryType.CARBS),
                            placeIconAfterLabel = true,
                            itemProgress = itemProgress,
                            rowTravelPx = rowTravelPx,
                            itemLiftPx = itemLiftPx,
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onCurrentMealSelected?.invoke()
                                onExpandedChange(false)
                            }
                        )
                    }
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
    JournalMenuRow(
        label = actionType.journalActionLabel(),
        icon = actionType.journalActionIcon(),
        actionTint = journalTypeColor(actionType),
        iconContainerColor = journalTypeSelectedContainerColor(actionType),
        labelContainerColor = journalTypeSubtleContainerColor(actionType),
        placeIconAfterLabel = placeIconAfterLabel,
        itemProgress = itemProgress,
        rowTravelPx = rowTravelPx,
        itemLiftPx = itemLiftPx,
        onClick = onClick
    )
}

@Composable
private fun JournalMenuRow(
    label: String,
    icon: ImageVector,
    actionTint: androidx.compose.ui.graphics.Color,
    iconContainerColor: androidx.compose.ui.graphics.Color,
    labelContainerColor: androidx.compose.ui.graphics.Color,
    placeIconAfterLabel: Boolean,
    itemProgress: Float,
    rowTravelPx: Float,
    itemLiftPx: Float,
    onClick: () -> Unit
) {
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
            JournalActionFab(icon, label, actionTint, iconContainerColor, onClick)
        }
        Surface(
            modifier = Modifier.clickable(onClick = onClick),
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
            JournalActionFab(icon, label, actionTint, iconContainerColor, onClick)
        }
    }
}

@Composable
private fun JournalActionFab(
    icon: ImageVector,
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
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(22.dp)
        )
    }
}
