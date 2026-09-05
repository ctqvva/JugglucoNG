package tk.glucodata.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tk.glucodata.R

@Composable
fun SliderControl(label: String, value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, steps: Int = 0) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps
        )
    }
}

@Composable
fun SectionLabel(text: String, isError: Boolean = false, topPadding: Dp = 24.dp, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = topPadding, bottom = 8.dp)
    )
}

enum class CardPosition {
    SINGLE, // All corners rounded
    TOP,    // Top corners rounded
    MIDDLE, // No corners rounded
    BOTTOM  // Bottom corners rounded
}

fun cardShape(position: CardPosition, radius: Dp = 12.dp): RoundedCornerShape {
    return when (position) {
        CardPosition.SINGLE -> RoundedCornerShape(radius)
        CardPosition.TOP -> RoundedCornerShape(topStart = radius, topEnd = radius, bottomStart = 4.dp, bottomEnd = 4.dp)
        CardPosition.MIDDLE -> RoundedCornerShape(4.dp)
        CardPosition.BOTTOM -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = radius, bottomEnd = radius)
    }
}

/**
 * [cardShape] for a row whose position changes at runtime, because the group it
 * belongs to grows or shrinks. Without this the row snaps between a rounded and
 * a square edge in a single frame while the neighbour beside it is still sliding
 * open, which reads as a glitch rather than as one group re-forming.
 */
@Composable
fun animatedCardShape(position: CardPosition, radius: Dp = 12.dp): RoundedCornerShape {
    val spec = spring<Dp>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
    val topTarget = when (position) {
        CardPosition.SINGLE, CardPosition.TOP -> radius
        CardPosition.MIDDLE, CardPosition.BOTTOM -> 4.dp
    }
    val bottomTarget = when (position) {
        CardPosition.SINGLE, CardPosition.BOTTOM -> radius
        CardPosition.TOP, CardPosition.MIDDLE -> 4.dp
    }
    val top by animateDpAsState(topTarget, spec, label = "cardTopRadius")
    val bottom by animateDpAsState(bottomTarget, spec, label = "cardBottomRadius")
    return RoundedCornerShape(
        topStart = top,
        topEnd = top,
        bottomStart = bottom,
        bottomEnd = bottom
    )
}

@Composable
fun SettingsItem(
    title: String,
    subtitle: String? = null,
    showArrow: Boolean = false,
    onClick: (() -> Unit)? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null, // Added tint
    trailingContent: (@Composable () -> Unit)? = null,
    position: CardPosition = CardPosition.SINGLE,
    animatePosition: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = modifier.fillMaxWidth(),
        shape = if (animatePosition) animatedCardShape(position) else cardShape(position),
        color = MaterialTheme.colorScheme.surfaceContainerHigh // restored color
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 72.dp) // M3 Two-line item standard
                .padding(horizontal = 16.dp, vertical = 16.dp), // Coherent spacing
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                val tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant
                // If tint is present, use tonal background. Else match surface.
                val background = if(iconTint != null) iconTint.copy(alpha = 0.12f) else Color.Transparent

                Surface(
                    modifier = Modifier
                        .size(40.dp)
                        .padding(end = 0.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = background
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                         Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium) // Heavier weight (500) for legibility
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (trailingContent != null) {
                trailingContent()
            } else if (showArrow) {
                Icon(
                    Icons.Filled.ChevronRight,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SettingsSwitchItem(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    position: CardPosition = CardPosition.SINGLE,
    animatePosition: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    subtitleStyle: TextStyle = MaterialTheme.typography.bodySmall
) {
    SettingsItem(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconTint = iconTint,
        onClick = if (enabled) {
            { onCheckedChange(!checked) }
        } else {
            null
        },
        modifier = modifier,
        trailingContent = {
            StyledSwitch(
                checked = checked,
                onCheckedChange = null, // Handled by parent click
                enabled = enabled
            )
        },
        position = position,
        animatePosition = animatePosition
    )
}

@Composable
fun MasterSwitchCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary
) {
    val containerColor by animateColorAsState(
        targetValue = if (checked) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.74f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "masterSwitchCardContainer"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.26f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 84.dp)
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (checked) {
                    iconTint.copy(alpha = 0.15f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (checked) iconTint else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
fun AlarmItem(
    title: String,
    enabled: Boolean,
    value: Float,
    unit: String,
    range: ClosedFloatingPointRange<Float>,
    soundMode: Int,
    onToggle: (Boolean) -> Unit,
    onValueChange: (Float) -> Unit,
    onSoundChange: (Int) -> Unit,
    position: CardPosition = CardPosition.SINGLE
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    val displayValue = if (sliderValue < 30) String.format("%.1f", sliderValue) else sliderValue.toInt().toString()

    Surface(
        onClick = { onToggle(!enabled) },
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape(position),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    if (enabled) {
                        Text("$displayValue $unit", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(16.dp))
                StyledSwitch(
                    checked = enabled,
                    onCheckedChange = null
                )
            }

            // Expandable content
            AnimatedVisibility(visible = enabled) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = { onValueChange(sliderValue) },
                        valueRange = range
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.alert_mode), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = soundMode == 0,
                            onClick = { onSoundChange(0) },
                            label = { Text(stringResource(R.string.vibrate_only)) },
                            leadingIcon = { if(soundMode == 0) Icon(Icons.Filled.Check, null) }
                        )
                        FilterChip(
                            selected = soundMode == 1,
                            onClick = { onSoundChange(1) },
                            label = { Text(stringResource(R.string.sound_vibrate)) },
                            leadingIcon = { if(soundMode == 1) Icon(Icons.Filled.Check, null) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SliderItem(
    title: String,
    value: Float,
    unit: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    position: CardPosition = CardPosition.SINGLE
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    val displayValue = if (sliderValue < 30) String.format("%.1f", sliderValue) else sliderValue.toInt().toString()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape(position),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row {
                Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text("$displayValue $unit", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onValueChange(sliderValue) },
                valueRange = range
            )
        }
    }
}

@Composable
fun DangerItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    position: CardPosition = CardPosition.SINGLE,
    severity: DangerSeverity = DangerSeverity.HIGH
) {
    val (bgColor, contentColor) = when (severity) {
        DangerSeverity.LOW -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
        DangerSeverity.MEDIUM -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        DangerSeverity.HIGH -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    val iconBgColor = when (severity) {
        DangerSeverity.LOW -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        DangerSeverity.MEDIUM -> contentColor.copy(alpha = 0.12f)
        DangerSeverity.HIGH -> contentColor.copy(alpha = 0.12f)
    }
    
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape(position),
        color = bgColor
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = iconBgColor
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        null,
                        tint = contentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.72f)
                )
            }
        }
    }
}

enum class DangerSeverity { LOW, MEDIUM, HIGH }
