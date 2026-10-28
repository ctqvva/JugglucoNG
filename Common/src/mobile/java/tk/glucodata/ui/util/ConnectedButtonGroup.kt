package tk.glucodata.ui.util

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer

/**
 * M3 Expressive connected button group: material3's [ToggleButton]s with the
 * [ButtonGroupDefaults] connected shapes, so the outer ends are round, inner corners small,
 * the selected item a pill, and a press squeezes the inner corners with the expressive spring.
 *
 * On top of the library pieces this adds what the app's groups need: colours that cross-fade
 * (ToggleButton swaps them instantly), per-option colours, and labels that step down from
 * labelLarge to labelSmall until every option fits.
 *
 * Each item is a checkable node, so TalkBack reads which option is selected. In a
 * [multiSelect] group a text option shows a check mark while selected, so the group reads
 * as "any of these" rather than "one of these".
 *
 * @param itemHeight Default 40.dp, the M3 small button height
 * @param spacing Default 2.dp, [ButtonGroupDefaults.ConnectedSpaceBetween]
 */
@Composable
fun <T> ConnectedButtonGroup(
    options: List<T>,
    selectedOption: T? = null,
    selectedOptions: List<T> = emptyList(),
    onOptionSelected: (T) -> Unit,
    label: @Composable (T) -> Unit,
    labelText: ((T) -> String)? = null,
    icon: (@Composable (T) -> ImageVector?)? = null,
    modifier: Modifier = Modifier,
    multiSelect: Boolean = false,
    iconOnly: Boolean = false,
    itemHeight: Dp = 40.dp,
    spacing: Dp = ButtonGroupDefaults.ConnectedSpaceBetween,
    selectedContainerColor: Color = MaterialTheme.colorScheme.primary,
    selectedContentColor: Color = MaterialTheme.colorScheme.onPrimary,
    unselectedContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh, // Slightly darker than surface for contrast
    unselectedContentColor: Color = MaterialTheme.colorScheme.onSurface,
    selectedContainerColorFor: ((T) -> Color)? = null,
    selectedContentColorFor: ((T) -> Color)? = null,
    iconTint: ((T, Boolean) -> Color)? = null,
) {
    // Each label's own height, measured unclipped. The buttons' intrinsic height can't tell this:
    // it never reports less than the 40dp minimum or the 48dp touch target, which hides a label
    // that outgrows a 36dp group while staying under 48dp.
    val labelHeights = remember(options.size) { mutableStateMapOf<Int, Int>() }
    Row(
        modifier = modifier
            .selectableGroup()
            .layout { measurable, constraints ->
                // itemHeight as a fixed height, as before, unless a label needs more (large fonts).
                val height = constraints.constrainHeight(
                    maxOf(itemHeight.roundToPx(), labelHeights.values.maxOrNull() ?: 0)
                )
                val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            },
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = if (multiSelect) selectedOptions.contains(option) else option == selectedOption

            val containerColor by animateColorAsState(
                targetValue = if (isSelected) {
                    selectedContainerColorFor?.invoke(option) ?: selectedContainerColor
                } else {
                    unselectedContainerColor
                },
                label = "containerColor"
            )
            val contentColor by animateColorAsState(
                targetValue = if (isSelected) {
                    selectedContentColorFor?.invoke(option) ?: selectedContentColor
                } else {
                    unselectedContentColor
                },
                label = "contentColor"
            )

            ToggleButton(
                checked = isSelected,
                onCheckedChange = { onOptionSelected(option) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                shapes = when {
                    options.size == 1 -> ToggleButtonDefaults.shapesFor(itemHeight)
                    index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    index == options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                // The animated colour serves both states, so the swap cross-fades.
                colors = ToggleButtonDefaults.colors(
                    containerColor = containerColor,
                    contentColor = contentColor,
                    checkedContainerColor = containerColor,
                    checkedContentColor = contentColor
                ),
                elevation = null,
                contentPadding = PaddingValues(0.dp)
            ) {
                var availableWidthPx by remember(option) { mutableIntStateOf(0) }
                val customIcon = icon?.invoke(option)
                val showsCheck = multiSelect && customIcon == null && !iconOnly
                val density = LocalDensity.current
                val textMeasurer = rememberTextMeasurer()
                val availableWidth = with(density) { availableWidthPx.toDp() }
                val labelTextStyle: TextStyle = if (labelText != null && availableWidthPx > 0) {
                    val horizontalPadding = 4.dp
                    val iconAllowance = if (customIcon != null || showsCheck) 26.dp else 0.dp
                    val textWidthPx = with(density) { (availableWidth - horizontalPadding - iconAllowance).coerceAtLeast(24.dp).toPx() }
                    listOf(
                        MaterialTheme.typography.labelLarge,
                        MaterialTheme.typography.labelMedium,
                        MaterialTheme.typography.labelSmall
                    ).firstOrNull { style ->
                        options.maxOfOrNull { item ->
                            textMeasurer.measure(
                                text = AnnotatedString(labelText(item)),
                                style = style,
                                maxLines = 1
                            ).size.width
                        }?.let { it <= textWidthPx } == true
                    } ?: MaterialTheme.typography.labelSmall
                } else {
                    when {
                        availableWidthPx == 0 -> MaterialTheme.typography.labelLarge
                        availableWidth >= 92.dp -> MaterialTheme.typography.labelLarge
                        availableWidth >= 72.dp -> MaterialTheme.typography.labelMedium
                        else -> MaterialTheme.typography.labelSmall
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Centred, and free to overflow so its size is the label's real height.
                        .wrapContentHeight(unbounded = true)
                        .onSizeChanged {
                            availableWidthPx = it.width
                            labelHeights[index] = it.height
                        }
                        .padding(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showsCheck) {
                        AnimatedVisibility(
                            visible = isSelected,
                            enter = fadeIn() + expandHorizontally(),
                            exit = fadeOut() + shrinkHorizontally()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = contentColor
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                    }
                    if (customIcon != null) {
                        Icon(
                            imageVector = customIcon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = iconTint?.invoke(option, isSelected) ?: contentColor
                        )
                        if (!iconOnly && (labelText != null)) {
                            Spacer(Modifier.width(if (availableWidth >= 72.dp) 8.dp else 4.dp))
                        }
                    }

                    if (!iconOnly && labelText != null) {
                        Text(
                            text = labelText(option),
                            style = labelTextStyle,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    } else if (!iconOnly) {
                        androidx.compose.material3.ProvideTextStyle(value = labelTextStyle) {
                            label(option)
                        }
                    }
                }
            }
        }
    }
}
