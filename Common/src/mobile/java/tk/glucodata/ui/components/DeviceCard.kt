@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import tk.glucodata.ui.symbols.Symbols

/** One labelled fact about a device: "Route" / "TURN relay". */
@Immutable
data class DeviceDetail(val label: String, val value: String)

enum class DeviceActionTone { Neutral, Selected, Destructive }

/** One of a device's own controls: an ordinary button with an icon and a label. */
@Immutable
data class DeviceAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val tone: DeviceActionTone = DeviceActionTone.Neutral,
    val enabled: Boolean = true,
)

/** Between two device cards in a list. */
val DeviceCardGap = 8.dp

/**
 * A thing the app is connected to (a Clone link, a glucose meter). It sits among settings rows,
 * so it keeps their surface and the guide's 20dp status-card corner, and its text starts on the
 * card's 16dp edge like theirs.
 *
 * - Header: the name, its state in words under it, and Pause / Play at the far edge.
 * - Facts: one quiet panel. [glance] is always shown (what you check most); a tap anywhere on
 *   the panel adds [details] and [detailsContent] under them in the same table, and a second tap
 *   folds them away. The chevron sits under the facts, never in a column beside them.
 * - Controls: ordinary buttons in one row ([QR code] [Edit] [Delete]); the destructive one is
 *   outlined in the error colour, last.
 * - [extra]: what a control opens inline, then a task only this device offers.
 */
@Composable
fun DeviceCard(
    title: String,
    active: Boolean,
    actions: List<DeviceAction>,
    modifier: Modifier = Modifier,
    status: String? = null,
    statusIcon: ImageVector? = null,
    glance: List<DeviceDetail> = emptyList(),
    details: List<DeviceDetail> = emptyList(),
    detailsContent: (@Composable ColumnScope.() -> Unit)? = null,
    detailsOpen: Boolean = false,
    onDetailsOpenChange: (Boolean) -> Unit = {},
    headerAction: (@Composable () -> Unit)? = null,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val container by animateColorAsState(
        if (active) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        label = "device_container",
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = DeviceCardDefaults.Shape,
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (status != null) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (statusIcon != null) {
                                Icon(
                                    statusIcon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                status,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (headerAction != null) {
                    Spacer(Modifier.width(12.dp))
                    headerAction()
                }
            }
            if (glance.isNotEmpty() || details.isNotEmpty() || detailsContent != null) {
                Spacer(Modifier.height(12.dp))
                DeviceFacts(glance, details, detailsContent, detailsOpen, onDetailsOpenChange)
            }
            if (actions.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                DeviceActionRow(actions)
            }
            extra()
        }
    }
}

object DeviceCardDefaults {
    /** The style guide's corner for a status card beside settings rows. */
    val Shape = RoundedCornerShape(20.dp)
}

/** Pause while running, Play once paused; the way back to running is the filled one. */
@Composable
fun DevicePauseButton(
    paused: Boolean,
    pauseLabel: String,
    resumeLabel: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val label = if (paused) resumeLabel else pauseLabel
    IconButtonTooltip(label) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(DeviceHeaderButtonDefaults.Width, DeviceHeaderButtonDefaults.Height),
            shapes = IconButtonDefaults.shapes(shape = DeviceHeaderButtonDefaults.Shape),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (paused) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (paused) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Icon(
                if (paused) Symbols.PlayArrowFilled else Symbols.PauseFilled,
                contentDescription = label,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun DeviceFacts(
    glance: List<DeviceDetail>,
    details: List<DeviceDetail>,
    detailsContent: (@Composable ColumnScope.() -> Unit)?,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
) {
    val discloses = details.isNotEmpty() || detailsContent != null
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (discloses) Modifier.clickable(role = Role.Button) { onOpenChange(!open) } else Modifier)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (discloses) 4.dp else 12.dp),
        ) {
            if (glance.isNotEmpty()) DeviceDetailTable(glance)
            AnimatedVisibility(visible = open && discloses) {
                Column(
                    modifier = Modifier.padding(top = if (glance.isNotEmpty()) 8.dp else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (details.isNotEmpty()) DeviceDetailTable(details)
                    detailsContent?.invoke(this)
                }
            }
            if (discloses) {
                // Under the facts, centred: it says the panel opens without taking a column
                // from every row.
                val turn by animateFloatAsState(if (open) 180f else 0f, label = "device_facts_chevron")
                Icon(
                    Symbols.ExpandMoreFilled,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 4.dp)
                        .size(20.dp)
                        .graphicsLayer { rotationZ = turn },
                )
            }
        }
    }
}

/**
 * Label and value pairs: a table (label at the start, value at the end) while every pair fits
 * on one line, otherwise each value under its label.
 */
@Composable
fun DeviceDetailTable(details: List<DeviceDetail>, modifier: Modifier = Modifier) {
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            details.forEach { detail ->
                Text(
                    detail.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    detail.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 12.dp.roundToPx()
        val pairs = measurables.chunked(2).filter { it.size == 2 }
        val table = pairs.all { (label, value) ->
            label.maxIntrinsicWidth(Constraints.Infinity) + gap + value.maxIntrinsicWidth(Constraints.Infinity) <= width
        }
        val loose = Constraints(maxWidth = width)
        val placed = pairs.map { (label, value) -> label.measure(loose) to value.measure(loose) }
        val rowGap = (if (table) 8.dp else 12.dp).roundToPx()
        val heights = placed.map { (label, value) ->
            if (table) maxOf(label.height, value.height) else label.height + value.height
        }
        val height = heights.sum() + rowGap * (heights.size - 1).coerceAtLeast(0)
        layout(width, height) {
            var y = 0
            placed.forEachIndexed { index, (label, value) ->
                if (table) {
                    label.placeRelative(0, y)
                    value.placeRelative(width - value.width, y)
                } else {
                    label.placeRelative(0, y)
                    value.placeRelative(0, y + label.height)
                }
                y += heights[index] + rowGap
            }
        }
    }
}

private val DeviceActionPadding = 12.dp
private val DeviceActionGap = 8.dp

/**
 * Ordinary buttons in one row, 8dp apart. Equal widths while every label fits its share;
 * otherwise each takes its own width and the spare is shared out, so a long word (Редактировать)
 * isn't cut beside a short one. Icons give way before any label is cut.
 */
@Composable
private fun DeviceActionRow(actions: List<DeviceAction>) {
    var rowWidth by remember { mutableIntStateOf(0) }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    val labelWidths = actions.map { measurer.measure(it.label, labelStyle, maxLines = 1).size.width }
    val (showIcons, widths) = with(density) {
        val gaps = DeviceActionGap.roundToPx() * (actions.size - 1)
        val room = rowWidth - gaps
        val padding = DeviceActionPadding.roundToPx() * 2
        val icon = (18.dp + 8.dp).roundToPx()
        fun natural(icons: Boolean) = labelWidths.map { it + padding + if (icons) icon else 0 }
        fun spread(natural: List<Int>): List<Int> {
            val share = room / actions.size
            if (natural.all { it <= share }) return List(actions.size) { share }
            val spare = (room - natural.sum()) / actions.size
            return natural.map { it + spare }
        }
        when {
            rowWidth == 0 -> true to null
            natural(true).sum() <= room -> true to spread(natural(true))
            natural(false).sum() <= room -> false to spread(natural(false))
            else -> true to null
        }
    }
    // Only when not even the bare labels fit one line (large text) does each take its own line.
    val stacked = rowWidth > 0 && widths == null
    val arrangement = Arrangement.spacedBy(DeviceActionGap)
    val buttons: @Composable (Modifier.(Int) -> Modifier) -> Unit = { size ->
        actions.forEachIndexed { index, action ->
            val sizing = Modifier.size(index)
            val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                if (showIcons) {
                    Icon(action.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(action.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val padding = PaddingValues(horizontal = DeviceActionPadding)
            when (action.tone) {
                DeviceActionTone.Destructive -> OutlinedButton(
                    onClick = action.onClick,
                    enabled = action.enabled,
                    modifier = sizing,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    contentPadding = padding,
                    content = content,
                )
                else -> FilledTonalButton(
                    onClick = action.onClick,
                    enabled = action.enabled,
                    modifier = sizing,
                    shapes = ButtonDefaults.shapes(),
                    colors = if (action.tone == DeviceActionTone.Selected) {
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        ButtonDefaults.filledTonalButtonColors()
                    },
                    contentPadding = padding,
                    content = content,
                )
            }
        }
    }
    if (stacked) {
        Column(
            modifier = Modifier.fillMaxWidth().onSizeChanged { rowWidth = it.width },
            verticalArrangement = arrangement,
        ) {
            buttons { fillMaxWidth() }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().onSizeChanged { rowWidth = it.width },
            horizontalArrangement = arrangement,
        ) {
            buttons { index ->
                val width = widths?.get(index)
                if (width != null) width(with(density) { width.toDp() }) else fillMaxWidth(1f / (actions.size - index))
            }
        }
    }
}
