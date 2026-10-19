package tk.glucodata.ui

import tk.glucodata.GlucoseValuePlausibility

import java.util.Locale

data class DisplayValues(
    val primaryValue: Float,
    val secondaryValue: Float? = null,
    val tertiaryValue: Float? = null,
    val primaryStr: String,
    val secondaryStr: String? = null,
    val tertiaryStr: String? = null,
    val fullFormatted: String
)

object DisplayValueResolver {
    private val SPEECH_SAFE_LOCALE = Locale.US

    private fun format(value: Float, isMmol: Boolean): String {
        return if (isMmol) {
            String.format(Locale.getDefault(), "%.1f", value)
        } else {
            String.format(Locale.getDefault(), "%.0f", value)
        }
    }

    @JvmStatic
    fun formatForSpeech(value: Float, isMmol: Boolean): String {
        return if (isMmol) {
            String.format(SPEECH_SAFE_LOCALE, "%.1f", value)
        } else {
            String.format(SPEECH_SAFE_LOCALE, "%.0f", value)
        }
    }

    private fun appendUnit(text: String, unitLabel: String): String {
        return if (unitLabel.isBlank()) text else "$text $unitLabel"
    }

    @JvmStatic
    @JvmOverloads
    fun resolve(
        autoValue: Float,
        rawValue: Float,
        viewMode: Int,
        isMmol: Boolean,
        unitLabel: String = "",
        calibratedValue: Float? = null,
        hideInitialWhenCalibrated: Boolean = false,
        calibratedIsRecorded: Boolean = false
    ): DisplayValues {
        val autoDisplayValue = autoValue.takeIf { it.isFinite() && it > 0f } ?: Float.NaN
        val rawDisplayValue = rawValue.takeIf {
            GlucoseValuePlausibility.isPlausibleDisplayValue(it, isMmol)
        } ?: Float.NaN
        val rawStr = if (rawDisplayValue.isFinite()) format(rawDisplayValue, isMmol) else "--"
        val valStr = if (autoDisplayValue.isFinite()) format(autoDisplayValue, isMmol) else "--"
        val isRawPrimaryMode = viewMode == 1 || viewMode == 3
        val effectiveCalibratedValue = when {
            calibratedValue == null -> null
            !calibratedValue.isFinite() || calibratedValue <= 0f -> null
            // A live calibration of a missing raw value means nothing. A
            // recorded value does not derive from this reading's raw value: it
            // is the number already shown, possibly from the auto lane, and
            // the chart keeps it whatever the raw input is now.
            isRawPrimaryMode && !rawDisplayValue.isFinite() && !calibratedIsRecorded -> null
            else -> calibratedValue
        }
        val calStr = effectiveCalibratedValue?.let { format(it, isMmol) }

        if (effectiveCalibratedValue != null && calStr != null) {
            if (hideInitialWhenCalibrated) {
                return when (viewMode) {
                    2 -> DisplayValues(
                        primaryValue = effectiveCalibratedValue,
                        secondaryValue = rawDisplayValue.takeIf { it.isFinite() },
                        primaryStr = calStr,
                        secondaryStr = rawStr.takeUnless { it == "--" },
                        fullFormatted = appendUnit(
                            if (rawDisplayValue.isFinite()) "$calStr · $rawStr" else calStr,
                            unitLabel
                        )
                    )
                    3 -> DisplayValues(
                        primaryValue = effectiveCalibratedValue,
                        secondaryValue = autoDisplayValue.takeIf { it.isFinite() },
                        primaryStr = calStr,
                        secondaryStr = valStr.takeUnless { it == "--" },
                        fullFormatted = appendUnit(
                            if (autoDisplayValue.isFinite()) "$calStr · $valStr" else calStr,
                            unitLabel
                        )
                    )
                    else -> DisplayValues(
                        primaryValue = effectiveCalibratedValue,
                        primaryStr = calStr,
                        fullFormatted = appendUnit(calStr, unitLabel)
                    )
                }
            }

            return when (viewMode) {
                1 -> DisplayValues(
                    primaryValue = effectiveCalibratedValue,
                    secondaryValue = rawDisplayValue.takeIf { it.isFinite() },
                    primaryStr = calStr,
                    secondaryStr = rawStr.takeUnless { it == "--" },
                    fullFormatted = appendUnit(
                        if (rawDisplayValue.isFinite()) "$calStr · $rawStr" else calStr,
                        unitLabel
                    )
                )
                2 -> DisplayValues(
                    primaryValue = effectiveCalibratedValue,
                    secondaryValue = autoDisplayValue.takeIf { it.isFinite() },
                    tertiaryValue = rawDisplayValue.takeIf { it.isFinite() },
                    primaryStr = calStr,
                    secondaryStr = valStr.takeUnless { it == "--" },
                    tertiaryStr = rawStr.takeUnless { it == "--" },
                    fullFormatted = appendUnit(
                        when {
                            autoDisplayValue.isFinite() && rawDisplayValue.isFinite() -> "$calStr · $valStr · $rawStr"
                            autoDisplayValue.isFinite() -> "$calStr · $valStr"
                            else -> calStr
                        },
                        unitLabel
                    )
                )
                3 -> DisplayValues(
                    primaryValue = effectiveCalibratedValue,
                    secondaryValue = if (rawDisplayValue.isFinite()) rawDisplayValue
                        else autoDisplayValue.takeIf { it.isFinite() },
                    tertiaryValue = autoDisplayValue.takeIf { it.isFinite() && rawDisplayValue.isFinite() },
                    primaryStr = calStr,
                    secondaryStr = if (rawDisplayValue.isFinite()) rawStr else valStr.takeUnless { it == "--" },
                    tertiaryStr = valStr.takeIf { autoDisplayValue.isFinite() && rawDisplayValue.isFinite() },
                    fullFormatted = appendUnit(
                        when {
                            rawDisplayValue.isFinite() && autoDisplayValue.isFinite() -> "$calStr · $rawStr · $valStr"
                            rawDisplayValue.isFinite() -> "$calStr · $rawStr"
                            autoDisplayValue.isFinite() -> "$calStr · $valStr"
                            else -> calStr
                        },
                        unitLabel
                    )
                )
                else -> DisplayValues(
                    primaryValue = effectiveCalibratedValue,
                    secondaryValue = autoDisplayValue.takeIf { it.isFinite() },
                    primaryStr = calStr,
                    secondaryStr = valStr.takeUnless { it == "--" },
                    fullFormatted = appendUnit(
                        if (autoDisplayValue.isFinite()) "$calStr · $valStr" else calStr,
                        unitLabel
                    )
                )
            }
        }

        return when (viewMode) {
            1 -> DisplayValues(
                primaryValue = if (rawDisplayValue.isFinite()) rawDisplayValue else autoDisplayValue,
                primaryStr = if (rawDisplayValue.isFinite()) rawStr else valStr,
                fullFormatted = appendUnit(if (rawDisplayValue.isFinite()) rawStr else valStr, unitLabel)
            )
            2 -> DisplayValues(
                primaryValue = autoDisplayValue,
                secondaryValue = rawDisplayValue.takeIf { it.isFinite() },
                primaryStr = valStr,
                secondaryStr = rawStr.takeUnless { it == "--" },
                fullFormatted = appendUnit(
                    if (rawDisplayValue.isFinite()) "$valStr · $rawStr" else valStr,
                    unitLabel
                )
            )
            3 -> DisplayValues(
                primaryValue = if (rawDisplayValue.isFinite()) rawDisplayValue else autoDisplayValue,
                secondaryValue = autoDisplayValue.takeIf { rawDisplayValue.isFinite() && it.isFinite() },
                primaryStr = if (rawDisplayValue.isFinite()) rawStr else valStr,
                secondaryStr = valStr.takeIf { rawDisplayValue.isFinite() && autoDisplayValue.isFinite() },
                fullFormatted = appendUnit(
                    if (rawDisplayValue.isFinite() && autoDisplayValue.isFinite()) "$rawStr · $valStr"
                    else if (rawDisplayValue.isFinite()) rawStr
                    else valStr,
                    unitLabel
                )
            )
            else -> DisplayValues(
                primaryValue = autoDisplayValue,
                primaryStr = valStr,
                fullFormatted = appendUnit(valStr, unitLabel)
            )
        }
    }
}
