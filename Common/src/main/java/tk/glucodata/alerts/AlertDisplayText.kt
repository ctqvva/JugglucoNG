package tk.glucodata.alerts

/**
 * Pure text rules for how a triggered alert is worded on the notification and
 * the full-screen alarm. Kept free of Android dependencies so the rules are
 * unit-testable; the callers ([tk.glucodata.Notify], AlarmActivity) supply the
 * localized strings.
 */
object AlertDisplayText {

    // These alerts embed a duration/lead time in their message ("Sensor expires
    // in 12 hours", "Missed reading - 30 min", "Forecast low: 33 mg/dL (30 min)");
    // the numbers ARE the payload. The forecast horizon is a number too -
    // stripping digits turned the title into "Forecast low:  ( min)".
    private fun carriesDurationNumbers(type: AlertType?): Boolean =
        type == AlertType.SENSOR_EXPIRY ||
            type == AlertType.MISSED_READING ||
            type == AlertType.PRE_LOW ||
            type == AlertType.PRE_HIGH

    /**
     * Badge/title of the separate alert notification. Glucose-carrying messages
     * ("Forecast Low 4.0 mmol/L") lose the value because the notification shows
     * it in its own row; duration-carrying messages keep their numbers -
     * stripping them produced "Sensor expires in hours".
     */
    @JvmStatic
    fun notificationBadge(type: AlertType?, isCustomAlert: Boolean, message: String): String {
        if (isCustomAlert || carriesDurationNumbers(type)) {
            return message
        }
        val cleaned = message
            .replace(Regex("[0-9.,]+"), "")
            .replace("mmol/L", "", ignoreCase = true)
            .replace("mg/dL", "", ignoreCase = true)
            .trim()
        return cleaned.ifEmpty { message }
    }

    /**
     * Value shown for a triggered alert on the alarm screen and the separate
     * notification. A usable reading is rendered by the caller-supplied
     * formatter; without one the snapshot's preformatted string is reused. A
     * message-only alert (no reading at all: NaN value, blank snapshot) shows
     * no value - never the literal "NaN", a stale figure, or a fabricated one.
     * Values at or below the 0.1 sentinel count as absent, like the legacy
     * "wasvalue" convention.
     */
    @JvmStatic
    fun alarmDisplayValue(glvalue: Float, snapshotValue: String?, formatValue: (Float) -> String): String {
        if (glvalue.isFinite() && glvalue > 0.1f) {
            return formatValue(glvalue)
        }
        return snapshotValue?.takeIf { it.isNotBlank() } ?: ""
    }

    /**
     * Secondary line on the full-screen alarm. Keeps whatever the alert message
     * adds over the big label and the glucose hero (expiry lead time,
     * missed-reading duration, forecast horizon) and drops messages that merely
     * repeat "<label> <value>".
     */
    @JvmStatic
    fun alarmSupportingText(
        parsedValueMessage: String,
        rawMessage: String,
        rawValue: String,
        parsedValueRaw: String,
        alertLabel: String,
        unitLabels: List<String>
    ): String {
        val withoutValues = unitLabels
            .fold(rawMessage.replace(Regex("[0-9.,:()]+"), " ")) { acc, unit ->
                if (unit.isEmpty()) acc else acc.replace(unit, " ", ignoreCase = true)
            }
            .replace(Regex("\\s+"), " ")
            .trim()
        val fromMessage = rawMessage.takeIf {
            it.isNotBlank() &&
                !it.equals(alertLabel, ignoreCase = true) &&
                !it.equals(rawValue, ignoreCase = true) &&
                // "<label> <glucose>" repeats what the hero already shows.
                !withoutValues.equals(alertLabel, ignoreCase = true)
        }
        if (fromMessage != null) {
            return fromMessage
        }

        val fromParsed = parsedValueMessage.takeIf {
            it.isNotBlank() &&
                !it.equals(alertLabel, ignoreCase = true) &&
                !it.equals("low", ignoreCase = true) &&
                !it.equals("high", ignoreCase = true) &&
                !it.equals("alarm", ignoreCase = true)
        }
        if (fromParsed != null) {
            return fromParsed
        }

        return rawValue.takeIf { it.isNotBlank() && it != parsedValueRaw } ?: ""
    }
}
