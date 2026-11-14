package tk.glucodata.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tk.glucodata.R
import tk.glucodata.ui.util.rememberAdaptiveWindowMetrics

@OptIn(ExperimentalTextApi::class)
private fun jugglucoBrandFamily(weight: Int, width: Float): FontFamily {
    return try {
        FontFamily(
            Font(
                R.font.ibm_plex_sans_var,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(weight),
                    FontVariation.width(width)
                )
            )
        )
    } catch (th: Throwable) {
        android.util.Log.w("SensorSelectionCards", "Variable font fallback activated", th)
        FontFamily(Font(R.font.ibm_plex_sans_var))
    }
}

/**
 * Material 3 Expressive sensor selection cards for empty state screens.
 * Displays large, tappable cards for each sensor type.
 */
@Composable
fun SensorSelectionCards(
    onSensorSelected: (SensorType) -> Unit,
    modifier: Modifier = Modifier,
    /** Restricted mode's allowed families; null offers every family. */
    allowedTypes: Set<SensorType>? = null,
) {
    val compact = rememberAdaptiveWindowMetrics().isCompact
    val horizontalPadding = 0.dp
    val cardGap = if (compact) 8.dp else 10.dp
    var visible by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        visible = true
    }
    
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.95f)
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(cardGap) // Generous spacing (M3 Expressive)
        ) {

            sensorTypeOptions.filter { allowedTypes == null || it.type in allowedTypes }.forEach { option ->
                SensorCard(
                    type = option.type,
                    title = stringResource(option.titleRes),
                    subtitle = stringResource(option.subtitleRes),
                    // One tone for every sensor, as in the picker sheet and the wizard heroes;
                    // secondaryContainer sat too close to the card to hold the white plastic.
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    onClick = { onSensorSelected(option.type) },
                    compact = compact
                )
            }
        }
    }
}

/**
 * Individual sensor type card with M3 Expressive styling: the sensor itself leads, on a shape of
 * its own that answers the press; the text and the filled tonal arrow keep their places.
 */
@Composable
private fun SensorCard(
    type: SensorType,
    title: String,
    subtitle: String,
    containerColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    compact: Boolean
) {
    val tileSize = if (compact) 56.dp else 64.dp
    val rowPadding = if (compact) 8.dp else 12.dp
    val arrowContainer = if (compact) 32.dp else 36.dp
    val arrowPadding = if (compact) 8.dp else 9.dp
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge, // More "bubbly" M3 Expressive
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
        interactionSource = interactionSource
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = rowPadding, top = rowPadding, end = rowPadding + 4.dp, bottom = rowPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SensorArtTile(
                type = type,
                containerColor = containerColor,
                tileSize = tileSize,
                interactionSource = interactionSource
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Text content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMediumEmphasized,)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Spacer(modifier = Modifier.width(if (compact) 8.dp else 8.dp))
            
            // Filled tonal arrow indicator (M3 Expressive)
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(arrowContainer)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(arrowPadding)
                )
            }
        }
    }
}

/**
 * Import History card for Dashboard empty state.
 */
@Composable
fun ImportHistoryCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SetupAlternativeCard(
        icon = Icons.Default.Download,
        title = stringResource(R.string.import_history),
        description = stringResource(R.string.import_history_desc),
        onClick = onClick,
        modifier = modifier
    )
}

/** One of the first-run screen's ways forward that does not involve connecting a sensor. */
@Composable
private fun SetupAlternativeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Complete Dashboard empty state with welcome message, sensor cards, and import option.
 */
@Composable
fun DashboardEmptyState(
    onSensorSelected: (SensorType) -> Unit,
    /** Null hides the choice. */
    onImportHistory: (() -> Unit)?,
    /** Null hides the choice. */
    onContinueWithoutSensor: (() -> Unit)?,
    modifier: Modifier = Modifier,
    readinessContent: (@Composable () -> Unit)? = null,
    /** Restricted mode's allowed families; null offers every family, empty offers none. */
    allowedTypes: Set<SensorType>? = null,
) {
    val compact = rememberAdaptiveWindowMetrics().isCompact
    val sidePadding = 16.dp
    val scrollState = rememberScrollState()
    val brandWeight = if (compact) 450 else 470
    val brandWidth = if (compact) 94f else 90f
    val brandFontFamily = remember(brandWeight, brandWidth) {
        jugglucoBrandFamily(weight = brandWeight, width = brandWidth)
    }
    val brandStyle = if (compact) {
        MaterialTheme.typography.displaySmall.copy(
            fontFamily = brandFontFamily,
            fontWeight = FontWeight(brandWeight),
            letterSpacing = (-0.55).sp
        )
    } else {
        MaterialTheme.typography.displayMedium.copy(
            fontFamily = brandFontFamily,
            fontWeight = FontWeight(brandWeight),
            letterSpacing = (-0.75).sp
        )
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = sidePadding)
            .padding(top = if (compact) 8.dp else 16.dp)
            .padding(bottom = if (compact) 104.dp else 120.dp),
        horizontalAlignment = Alignment.Start
    ) {
        // Welcome header
        Text(
            text = stringResource(R.string.app_name),
            style = brandStyle,
            textAlign = TextAlign.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(if (compact) 16.dp else 20.dp))

        readinessContent?.let { content ->
            content()
            Spacer(modifier = Modifier.height(if (compact) 12.dp else 16.dp))
        }

        val hasAlternative = onImportHistory != null || onContinueWithoutSensor != null
        if (allowedTypes == null || allowedTypes.isNotEmpty()) {
            SensorSelectionCards(
                onSensorSelected = onSensorSelected,
                allowedTypes = allowedTypes,
            )
        }
        // "or" only between the sensors and something else to choose.
        if ((allowedTypes == null || allowedTypes.isNotEmpty()) && hasAlternative) {
            Spacer(modifier = Modifier.height(if (compact) 12.dp else 16.dp))
        
            // Divider with "or"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (compact) 4.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.or_divider),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }
        
            Spacer(modifier = Modifier.height(if (compact) 8.dp else 12.dp))
        
        }

        if (onImportHistory != null) {
            ImportHistoryCard(
                onClick = onImportHistory,
                modifier = Modifier.padding(horizontal = if (compact) 4.dp else 8.dp)
            )
        }

        if (onContinueWithoutSensor != null) {
            if (onImportHistory != null) Spacer(modifier = Modifier.height(8.dp))

            SetupAlternativeCard(
                icon = Icons.Default.EditNote,
                title = stringResource(R.string.continue_without_sensor),
                description = stringResource(R.string.continue_without_sensor_desc),
                onClick = onContinueWithoutSensor,
                modifier = Modifier.padding(horizontal = if (compact) 4.dp else 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(if (compact) 8.dp else 12.dp))
    }
}

/**
 * Sensors screen empty state - just the sensor cards without import.
 */
@Composable
fun SensorsEmptyState(
    onSensorSelected: (SensorType) -> Unit,
    modifier: Modifier = Modifier,
    allowedTypes: Set<SensorType>? = null,
) {
    val compact = rememberAdaptiveWindowMetrics().isCompact
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = if (compact) 8.dp else 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.no_sensors_connected),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = if (compact) 8.dp else 16.dp)
        )
        
        SensorSelectionCards(
            onSensorSelected = onSensorSelected,
            allowedTypes = allowedTypes,
        )
    }
}
