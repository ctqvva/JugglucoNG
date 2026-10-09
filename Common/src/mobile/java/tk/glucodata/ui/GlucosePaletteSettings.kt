package tk.glucodata.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.GlucoseRangeColors.Band
import tk.glucodata.GlucoseRangeColors.Palette
import tk.glucodata.R
import tk.glucodata.ui.components.ColorSwatchButton

private val BASE_PRESETS =
    listOf(Palette.MUTED, Palette.VIBRANT, Palette.AURORA, Palette.GDH_LIKE)

private fun presetLabelRes(palette: Palette): Int = when (palette) {
    Palette.MUTED -> R.string.glucose_palette_preset_muted
    Palette.VIBRANT -> R.string.glucose_palette_preset_vibrant
    Palette.AURORA -> R.string.glucose_palette_preset_aurora
    Palette.GDH_LIKE -> R.string.glucose_palette_preset_gdh
    Palette.CUSTOM -> R.string.glucose_palette_preset_custom
}

private fun bandLabelRes(band: Band): Int = when (band) {
    Band.VERY_LOW -> R.string.glucose_palette_band_very_low
    Band.LOW -> R.string.glucose_palette_band_low
    Band.IN_RANGE -> R.string.glucose_palette_band_in_range
    Band.HIGH -> R.string.glucose_palette_band_high
    Band.VERY_HIGH -> R.string.glucose_palette_band_very_high
}

private fun effectiveBandColor(band: Band, dark: Boolean): Int = when (band) {
    Band.VERY_LOW -> GlucoseRangeColors.veryLow(dark)
    Band.LOW -> GlucoseRangeColors.low(dark)
    Band.IN_RANGE -> GlucoseRangeColors.inRange(dark)
    Band.HIGH -> GlucoseRangeColors.high(dark)
    Band.VERY_HIGH -> GlucoseRangeColors.veryHigh(dark)
}

@Composable
fun GlucosePalettePresetSelector(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val revision = GlucosePaletteState.revision
    val activePreset = remember(revision) { GlucosePaletteState.palette() }
    val hasOverrides = remember(revision) { GlucosePaletteState.hasAnyOverride() }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BASE_PRESETS.forEach { preset ->
            FilterChip(
                selected = activePreset == preset && !hasOverrides,
                onClick = {
                    GlucosePaletteState.setPalette(context, preset)
                    GlucosePaletteState.clearOverrides(context)
                },
                label = { Text(stringResource(presetLabelRes(preset))) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun GlucosePaletteResetAllButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val revision = GlucosePaletteState.revision
    val hasOverrides = remember(revision) { GlucosePaletteState.hasAnyOverride() }

    if (hasOverrides) {
        Column(modifier = modifier.fillMaxWidth()) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
            TextButton(
                onClick = { GlucosePaletteState.clearOverrides(context) },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.glucose_palette_reset_all))
            }
        }
    }
}

@Composable
fun GlucoseBandColorButton(
    band: Band,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh
) {
    val context = LocalContext.current
    val revision = GlucosePaletteState.revision
    val isDark = tk.glucodata.ui.isAppInDarkTheme()
    var showDialog by remember { mutableStateOf(false) }
    val color = remember(revision, isDark, band) { effectiveBandColor(band, isDark) }

    ColorSwatchButton(
        color = Color(color),
        onClick = { showDialog = true },
        modifier = modifier,
        containerColor = containerColor
    )

    if (showDialog) {
        ExpressiveColorPickerDialog(
            title = stringResource(bandLabelRes(band)),
            initialColor = color,
            onDismiss = { showDialog = false },
            onReset = if (GlucosePaletteState.override(band) != null) {
                {
                    GlucosePaletteState.setOverride(context, band, null)
                    showDialog = false
                }
            } else {
                null
            },
            onConfirm = { updatedColor ->
                GlucosePaletteState.setOverride(context, band, updatedColor)
                showDialog = false
            }
        )
    }
}

@Composable
fun GlucoseTargetBackgroundColorButton(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh
) {
    val context = LocalContext.current
    val revision = GlucosePaletteState.revision
    val isDark = tk.glucodata.ui.isAppInDarkTheme()
    var showDialog by remember { mutableStateOf(false) }
    val color = remember(revision, isDark) {
        GlucoseRangeColors.targetBackground(isDark)
    }

    ColorSwatchButton(
        color = Color(color),
        onClick = { showDialog = true },
        modifier = modifier,
        containerColor = containerColor
    )

    if (showDialog) {
        ExpressiveColorPickerDialog(
            title = stringResource(R.string.glucose_target_background_title),
            initialColor = color or 0xFF000000.toInt(),
            showOpacity = false,
            onDismiss = { showDialog = false },
            onReset = if (GlucosePaletteState.targetBackgroundOverride() != null) {
                {
                    GlucosePaletteState.setTargetBackgroundOverride(context, null)
                    showDialog = false
                }
            } else {
                null
            },
            onConfirm = { updatedColor ->
                GlucosePaletteState.setTargetBackgroundOverride(
                    context,
                    updatedColor or 0xFF000000.toInt()
                )
                showDialog = false
            }
        )
    }
}

@Composable
fun ExpressiveColorPickerDialog(
    title: String,
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    onReset: (() -> Unit)? = null,
    showOpacity: Boolean = true
) {
    var colorState by remember(initialColor) { mutableStateOf(initialColor.toPaletteColorState()) }
    var colorText by remember(initialColor) { mutableStateOf(formatColorHex(initialColor)) }
    val composedColor = remember(colorState) { colorState.toColorInt() }
    val parsedColor = remember(colorText) { parseColorHex(colorText) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    fun updateColorState(updated: PaletteColorState) {
        colorState = updated
        colorText = formatColorHex(updated.toColorInt())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tk.glucodata.ui.components.ExpressiveHueWheelPicker(
                    hue = colorState.hue,
                    onHueChange = { hue ->
                        updateColorState(colorState.copy(hue = hue))
                    },
                    previewColor = Color(composedColor)
                )
                LabeledColorSlider(
                    label = stringResource(R.string.glucose_palette_saturation),
                    value = colorState.saturation,
                    onValueChange = { saturation ->
                        updateColorState(
                            colorState.copy(saturation = saturation.coerceIn(0f, 1f))
                        )
                    }
                )
                LabeledColorSlider(
                    label = stringResource(R.string.glucose_palette_brightness),
                    value = colorState.value,
                    onValueChange = { brightness ->
                        updateColorState(colorState.copy(value = brightness.coerceIn(0f, 1f)))
                    }
                )
                if (showOpacity) {
                    LabeledColorSlider(
                        label = stringResource(
                            R.string.opacity_percent,
                            (colorState.alpha * 100f).roundToInt()
                        ),
                        value = colorState.alpha,
                        showTrailingValue = false,
                        onValueChange = { alpha ->
                            updateColorState(colorState.copy(alpha = alpha.coerceIn(0f, 1f)))
                        }
                    )
                }
                OutlinedTextField(
                    value = colorText,
                    onValueChange = { raw ->
                        val updatedText = raw.trim().uppercase().take(9)
                        colorText = updatedText
                        parseColorHex(updatedText)?.let { parsed ->
                            colorState = parsed.toPaletteColorState()
                        }
                    },
                    label = { Text(stringResource(R.string.colors)) },
                    singleLine = true,
                    isError = parsedColor == null,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = null
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(composedColor) }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row {
                if (onReset != null) {
                    TextButton(onClick = onReset) {
                        Text(stringResource(R.string.glucose_palette_reset))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}

@Composable
private fun LabeledColorSlider(
    label: String,
    value: Float,
    showTrailingValue: Boolean = true,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            if (showTrailingValue) {
                Text(
                    text = "${(value * 100f).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private data class PaletteColorState(
    val hue: Float,
    val saturation: Float,
    val value: Float,
    val alpha: Float
)

private fun Int.toPaletteColorState(): PaletteColorState {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(this, hsv)
    return PaletteColorState(
        hue = hsv[0],
        saturation = hsv[1],
        value = hsv[2],
        alpha = ((this ushr 24) and 0xFF) / 255f
    )
}

private fun PaletteColorState.toColorInt(): Int {
    return AndroidColor.HSVToColor(
        (alpha * 255f).roundToInt(),
        floatArrayOf(hue, saturation, value)
    )
}

private fun formatColorHex(color: Int): String = "#%08X".format(color)

private fun parseColorHex(raw: String): Int? {
    val cleaned = raw.trim().removePrefix("#")
    val normalized = when (cleaned.length) {
        6 -> "FF$cleaned"
        8 -> cleaned
        else -> return null
    }
    return normalized.toLongOrNull(16)?.toInt()
}
