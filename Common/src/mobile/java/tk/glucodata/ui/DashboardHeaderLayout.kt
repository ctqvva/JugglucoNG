package tk.glucodata.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tk.glucodata.CloneTransport

/** Space between the reading card and the sensor panel. */
internal val HeroSensorGap = 4.dp

private const val HERO_SHARE = 0.7f
private const val SENSOR_SHARE = 0.3f
/** However little the reading needs, the sensor panel never becomes the larger card. */
private const val SENSOR_MAX_SHARE = 0.5f
/** The panel grows in whole steps, so a counter gaining a digit rarely moves the split. */
private val SensorGrowthStep = 8.dp

/**
 * The two cards' default widths in [availablePx] (the row less its gap): 70 / 30, rounded
 * the way a weighted `Row` rounds them. `first` is the reading card, `second` the panel.
 */
internal fun heroSensorBaseSplit(availablePx: Int): Pair<Int, Int> {
    val available = availablePx.coerceAtLeast(0)
    val unit = available / (HERO_SHARE + SENSOR_SHARE)
    var hero = (unit * HERO_SHARE).roundToInt()
    val sensor = (unit * SENSOR_SHARE).roundToInt()
    hero += (available - hero - sensor).sign
    return hero to available - hero
}

/**
 * How wide the sensor panel is. It keeps its 30% while its lines fit there; when they do
 * not, it takes width the reading card is not using, and nothing more.
 *
 * @param preferredPx the width at which none of the panel's own lines is cut.
 * @param heroNeedPx the width the reading card's content takes at its full size, or null
 *   when the card has nothing to give (a wrapped status line, paired sensors, a value that
 *   is already shrunk to fit).
 */
internal fun sensorPanelWidth(availablePx: Int, preferredPx: Int, heroNeedPx: Int?, stepPx: Int): Int {
    val base = heroSensorBaseSplit(availablePx).second
    if (heroNeedPx == null || preferredPx <= base) return base
    val limit = minOf(availablePx - heroNeedPx, (availablePx * SENSOR_MAX_SHARE).roundToInt())
    if (limit <= base) return base
    val step = stepPx.coerceAtLeast(1)
    val steps = (preferredPx - base + step - 1) / step
    return minOf(base + steps * step, limit)
}

/** Whether the panel's lines are cut even with all the width the reading card can spare. */
internal fun isSensorPanelCrowded(availablePx: Int, preferredPx: Int, heroRoomyNeedPx: Int?): Boolean =
    heroRoomyNeedPx != null &&
        preferredPx > heroSensorBaseSplit(availablePx).second &&
        preferredPx > availablePx - heroRoomyNeedPx

/**
 * The portrait header row: the reading card and, when there is a sensor, its panel, both
 * as tall as the taller of the two.
 *
 * A weighted `Row` gave the panel 30% whatever was in it, so on a narrow screen with a
 * large font its top line ran out of room while the reading card beside it stood half
 * empty. Here the panel asks for the width its lines need and the split moves into the
 * reading card's spare width — see [sensorPanelWidth]. With enough room for both, the
 * split is the same 70 / 30 as before.
 *
 * When the spare width is not enough and the reading card has a tighter arrangement to
 * fall back on, the row says so through [onPanelCrowded] and the card takes it.
 *
 * @param heroNeedPx see [sensorPanelWidth].
 * @param heroRoomyNeedPx what the reading card needs in the arrangement it would choose
 *   with the row to itself, or null when it has no tighter one. The answer to "is the
 *   panel crowded" is taken against this, never against the arrangement on screen, so
 *   it cannot flip back and forth.
 * @param content the reading card, then optionally the sensor panel.
 */
@Composable
internal fun HeroSensorSplit(
    heroNeedPx: Int?,
    heroRoomyNeedPx: Int?,
    onPanelCrowded: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val need by rememberUpdatedState(heroNeedPx)
    val roomyNeed by rememberUpdatedState(heroRoomyNeedPx)
    val reportCrowded by rememberUpdatedState(onPanelCrowded)
    // One policy for the life of the row: the need is read while measuring, so a new
    // reading costs a layout pass and the header's other animations cost none.
    val policy = remember(scope) {
        val panelWidth = AnimatedPanelWidth(scope)
        MeasurePolicy { measurables, constraints ->
            val hero = measurables[0]
            val sensor = measurables.getOrNull(1)
            val totalWidth = constraints.maxWidth
            if (sensor == null) {
                reportCrowded(false)
                val height = constraints.constrainHeight(hero.minIntrinsicHeight(totalWidth))
                val placeable = hero.measure(Constraints.fixed(totalWidth, height))
                return@MeasurePolicy layout(totalWidth, height) { placeable.placeRelative(0, 0) }
            }
            val gap = HeroSensorGap.roundToPx()
            val available = (totalWidth - gap).coerceAtLeast(0)
            val heroNeed = need
            val base = heroSensorBaseSplit(available).second
            val preferred = sensor.maxIntrinsicWidth(Constraints.Infinity)
            reportCrowded(isSensorPanelCrowded(available, preferred, roomyNeed))
            val target = sensorPanelWidth(
                availablePx = available,
                preferredPx = preferred,
                heroNeedPx = heroNeed,
                stepPx = SensorGrowthStep.roundToPx()
            )
            // The move is animated, but the reading is never squeezed while it runs: when
            // the value grows a digit the panel gives the width back at once.
            val sensorWidth = panelWidth.widthFor(target, totalWidth)
                .coerceIn(base, maxOf(base, available - (heroNeed ?: available)))
            val heroWidth = available - sensorWidth
            val height = constraints.constrainHeight(
                maxOf(hero.minIntrinsicHeight(heroWidth), sensor.minIntrinsicHeight(sensorWidth))
            )
            val heroPlaceable = hero.measure(Constraints.fixed(heroWidth, height))
            val sensorPlaceable = sensor.measure(Constraints.fixed(sensorWidth, height))
            layout(totalWidth, height) {
                heroPlaceable.placeRelative(0, 0)
                sensorPlaceable.placeRelative(heroWidth + gap, 0)
            }
        }
    }
    Layout(content = content, modifier = modifier, measurePolicy = policy)
}

/** The panel's width on its way to a new target. The first width, and a new row width, snap. */
private class AnimatedPanelWidth(private val scope: CoroutineScope) {
    private var width: Animatable<Int, AnimationVector1D>? = null
    private var rowWidth = -1

    fun widthFor(target: Int, rowWidth: Int): Int {
        val current = width
        if (current == null || rowWidth != this.rowWidth) {
            this.rowWidth = rowWidth
            width = Animatable(target, Int.VectorConverter)
            return target
        }
        if (current.targetValue != target) {
            scope.launch {
                current.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 1))
            }
        }
        return current.value
    }
}

/**
 * For a line that may be cut short (a sensor name, a status sentence): it is laid out in
 * the width it is given and never asks its card to grow for it.
 */
internal fun Modifier.neverWidensParent(): Modifier = this.then(NeverWidensParent)

private object NeverWidensParent : LayoutModifier {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = 0

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = 0
}

/**
 * The sensor panel beside the reading: signal quality and reading age, the sensor's name,
 * and its lifetime (or its status when that matters more), over a fill showing how much
 * of the lifetime is used.
 */
@Composable
internal fun DashboardSensorPanel(
    displayName: String,
    isCloneSource: Boolean,
    cloneTransport: CloneTransport?,
    activeSensors: List<String>,
    /** The sensor's status, shown in place of the lifetime; null to show the lifetime. */
    statusText: String?,
    daysText: String,
    hoursRemaining: Long,
    currentDay: Int,
    progress: Float,
    noiseLevel: Float,
    /** Null hides the reading-age counter. */
    readingMillis: Long?,
    quietWindowUntilMs: Long,
    containerColor: Color,
    contentColor: Color,
    shape: Shape,
    onClick: () -> Unit,
    onQuietWindowClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
        shape = shape
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (progress > 0f) {
                val fillColor = when {
                    progress > 0.95f -> MaterialTheme.colorScheme.error
                    progress > 0.80f -> MaterialTheme.colorScheme.tertiary
                    else -> StatusPalette.sensorLifeFill
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .background(fillColor.copy(alpha = 0.25f))
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = SensorPanelPadding, vertical = 12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start
            ) {
                // Quiet window: visible while it runs, so nobody forgets it.
                if (quietWindowUntilMs > 0L) {
                    QuietWindowHeaderChip(
                        untilMs = quietWindowUntilMs,
                        onClick = onQuietWindowClick,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                // Signal quality (start) and how old the newest reading is (end). The
                // counter is opt-in via Display → Dashboard readouts.
                if (noiseLevel > 0f || readingMillis != null) {
                    SignalQualityAndReadingAgeRow(
                        noiseLevel = noiseLevel,
                        readingMillis = readingMillis,
                        contentColor = contentColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                    )
                }

                if (displayName.isNotEmpty()) {
                    SensorNameLine(
                        displayName = displayName,
                        isCloneSource = isCloneSource,
                        cloneTransport = cloneTransport,
                        activeSensors = activeSensors,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                }

                if (statusText != null) {
                    if (statusText.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelLarge,
                            color = contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.neverWidensParent()
                        )
                    }
                } else {
                    val showRemainingHours = hoursRemaining in 0L..24L
                    val lifetimeText = if (showRemainingHours) "$hoursRemaining" + "h" else daysText
                    if (lifetimeText.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        SensorLifetimeLine(
                            text = lifetimeText,
                            color = contentColor,
                            // The hourglass is the warning, so it always shows; the calendar
                            // is decoration and steps aside when the text needs its room.
                            iconIsDecorative = !showRemainingHours
                        ) {
                            if (showRemainingHours) {
                                // Turns faster as the last hours run out.
                                val duration = (500 + (1500 * (hoursRemaining / 24f))).toInt().coerceAtLeast(500)
                                AnimatedHourglassIcon(
                                    color = contentColor.copy(alpha = 0.8f),
                                    modifier = Modifier.size(SensorPanelIconSize),
                                    cycleDuration = duration
                                )
                            } else {
                                CustomCalendarIcon(
                                    color = contentColor.copy(alpha = 0.6f),
                                    modifier = Modifier
                                        .size(SensorPanelIconSize)
                                        .padding(SensorPanelDrawnIconInset),
                                    currentDay = currentDay
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val SensorPanelPadding = 16.dp
/**
 * Every line of the panel that leads with an icon uses this slot and this gap, so the
 * icons share one column and the text after them one left edge.
 */
internal val SensorPanelIconSize = 14.dp
internal val SensorPanelIconGap = 8.dp
/**
 * The calendar is drawn edge to edge while the font glyphs beside it keep the usual
 * margin inside their box; inset, it reads as the same size as they do.
 */
private val SensorPanelDrawnIconInset = 1.dp

/** The sensor's name, or where a clone's readings come from, then a dot per shown sensor. */
@Composable
private fun SensorNameLine(
    displayName: String,
    isCloneSource: Boolean,
    cloneTransport: CloneTransport?,
    activeSensors: List<String>,
    color: Color
) {
    Row(
        modifier = Modifier.neverWidensParent(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isCloneSource) {
            CloneSourceMark(
                transport = cloneTransport,
                showLabel = true,
                tint = color,
                textStyle = MaterialTheme.typography.labelMedium,
            )
        } else {
            Text(
                text = displayName,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false) // shrinks to leave the dots their room
            )
        }

        // One dot per sensor on screen; a single sensor needs none.
        if (activeSensors.size > 1) {
            val sensorColors = remember(activeSensors) {
                tk.glucodata.SensorVisuals.distinctColorArgbMap(activeSensors)
            }
            Spacer(modifier = Modifier.width(8.dp))
            activeSensors.forEach { serial ->
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            Color(
                                sensorColors.entries.firstOrNull { (selected, _) ->
                                    tk.glucodata.SensorIdentity.matches(selected, serial)
                                }?.value ?: tk.glucodata.SensorVisuals.colorArgb(serial)
                            ),
                            CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
        }
    }
}

/**
 * The lifetime icon, then the lifetime ("5 / 15", "7h").
 *
 * Whether a decorative icon fits is decided while measuring, against the width this line
 * really gets, and its intrinsic width always counts the icon — so the panel asks for
 * the room to show it, and drops it only when that room is not there.
 */
@Composable
private fun SensorLifetimeLine(
    text: String,
    color: Color,
    iconIsDecorative: Boolean,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit
) {
    Layout(
        modifier = modifier,
        content = {
            icon()
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val iconMeasurable = measurables[0]
        val textMeasurable = measurables[1]
        val gap = SensorPanelIconGap.roundToPx()
        val iconWidth = iconMeasurable.maxIntrinsicWidth(loose.maxHeight)
        val showIcon = !iconIsDecorative || !constraints.hasBoundedWidth ||
            iconWidth + gap + textMeasurable.maxIntrinsicWidth(loose.maxHeight) <= constraints.maxWidth
        // An icon that is left out is not measured, and so neither placed nor drawn.
        val iconPlaceable = if (showIcon) iconMeasurable.measure(loose) else null
        val textX = if (iconPlaceable != null) iconPlaceable.width + gap else 0
        val textPlaceable = textMeasurable.measure(
            if (constraints.hasBoundedWidth) {
                loose.copy(maxWidth = (constraints.maxWidth - textX).coerceAtLeast(0))
            } else {
                loose
            }
        )
        val width = constraints.constrainWidth(textX + textPlaceable.width)
        val height = constraints.constrainHeight(maxOf(iconPlaceable?.height ?: 0, textPlaceable.height))
        layout(width, height) {
            iconPlaceable?.placeRelative(0, (height - iconPlaceable.height) / 2)
            textPlaceable.placeRelative(textX, (height - textPlaceable.height) / 2)
        }
    }
}
