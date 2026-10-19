package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertDisplayTextTests {

    private val units = listOf("mmol/L", "mg/dL")

    // --- alarmDisplayValue ---

    @Test
    fun usableReadingIsRenderedByTheCallerFormatter() {
        assertEquals(
            "123.4!",
            AlertDisplayText.alarmDisplayValue(123.4f, "999") { v -> "$v!" }
        )
    }

    @Test
    fun messageOnlyAlertShowsNoValueInsteadOfNaN() {
        // #98: an expiry alarm delivered without a reading carries NaN and a
        // blank snapshot. The display must stay empty - formatting would render
        // the literal "NaN", and any number would be stale or fabricated.
        val noFormatting = { _: Float -> throw AssertionError("must not format a non-value") }
        assertEquals("", AlertDisplayText.alarmDisplayValue(Float.NaN, null, noFormatting))
        assertEquals("", AlertDisplayText.alarmDisplayValue(Float.NaN, "  ", noFormatting))
    }

    @Test
    fun missingLiveValueFallsBackToSnapshotString() {
        assertEquals(
            "5.6",
            AlertDisplayText.alarmDisplayValue(Float.NaN, "5.6") { v -> "$v!" }
        )
    }

    @Test
    fun sentinelZeroCountsAsAbsent() {
        assertEquals(
            "120",
            AlertDisplayText.alarmDisplayValue(0f, "120") { v -> "$v!" }
        )
    }

    // --- notificationBadge ---

    @Test
    fun badgeKeepsNumbersForDurationCarryingAlerts() {
        assertEquals(
            "Sensor expires in 12 hours",
            AlertDisplayText.notificationBadge(AlertType.SENSOR_EXPIRY, false, "Sensor expires in 12 hours")
        )
        assertEquals(
            "Missed reading - 30 min",
            AlertDisplayText.notificationBadge(AlertType.MISSED_READING, false, "Missed reading - 30 min")
        )
    }

    @Test
    fun badgeKeepsForecastMessagesIntact() {
        // The forecast message carries the projected value AND the horizon
        // ("(30 Min)"); the digit-stripping meant for "Low 4.0 mmol/L" mangled
        // it into "Forecast low:  ( min)" - live log, PRE_LOW at reboot.
        assertEquals(
            "Forecast low: 33 mg/dL (30 min)",
            AlertDisplayText.notificationBadge(AlertType.PRE_LOW, false, "Forecast low: 33 mg/dL (30 min)")
        )
        assertEquals(
            "Forecast high: 12.5 mmol/L (20 min)",
            AlertDisplayText.notificationBadge(AlertType.PRE_HIGH, false, "Forecast high: 12.5 mmol/L (20 min)")
        )
    }

    @Test
    fun badgeStripsGlucoseValueFromGlucoseAlerts() {
        assertEquals(
            "Low",
            AlertDisplayText.notificationBadge(AlertType.LOW, false, "Low 4.0 mmol/L")
        )
        assertEquals(
            "High",
            AlertDisplayText.notificationBadge(AlertType.HIGH, false, "High 220 mg/dL")
        )
    }

    @Test
    fun badgeLeavesCustomAlertMessagesAlone() {
        assertEquals(
            "My rule 5.5",
            AlertDisplayText.notificationBadge(AlertType.LOW, true, "My rule 5.5")
        )
    }

    @Test
    fun badgeFallsBackToMessageWhenStrippingEmptiesIt() {
        assertEquals("4.0", AlertDisplayText.notificationBadge(AlertType.LOW, false, "4.0"))
    }

    // --- alarmSupportingText ---

    @Test
    fun alarmKeepsExpiryLeadTime() {
        assertEquals(
            "Sensor expires in 12 hours",
            AlertDisplayText.alarmSupportingText(
                parsedValueMessage = "",
                rawMessage = "Sensor expires in 12 hours",
                rawValue = "5.6",
                parsedValueRaw = "5.6",
                alertLabel = "Sensor Expiry",
                unitLabels = units
            )
        )
    }

    @Test
    fun alarmKeepsMissedReadingDuration() {
        assertEquals(
            "Missed reading - 30 min",
            AlertDisplayText.alarmSupportingText(
                parsedValueMessage = "",
                rawMessage = "Missed reading - 30 min",
                rawValue = "5.6",
                parsedValueRaw = "5.6",
                alertLabel = "Missed reading",
                unitLabels = units
            )
        )
    }

    @Test
    fun alarmDropsLabelPlusValueRepetition() {
        // "Low 4.0" adds nothing over the "Low" header and the 4.0 hero.
        assertEquals(
            "",
            AlertDisplayText.alarmSupportingText(
                parsedValueMessage = "",
                rawMessage = "Low 4.0",
                rawValue = "4.0",
                parsedValueRaw = "4.0",
                alertLabel = "Low",
                unitLabels = units
            )
        )
    }

    @Test
    fun alarmKeepsForecastHorizon() {
        assertEquals(
            "Forecast Low: 4.7 (30 min)",
            AlertDisplayText.alarmSupportingText(
                parsedValueMessage = "",
                rawMessage = "Forecast Low: 4.7 (30 min)",
                rawValue = "5.6",
                parsedValueRaw = "5.6",
                alertLabel = "Forecast Low",
                unitLabels = units
            )
        )
    }

    @Test
    fun alarmKeepsDurationFromRawMessageWhenParsingRemovedItsNumber() {
        assertEquals(
            "Sensor expires in 12 hours",
            AlertDisplayText.alarmSupportingText(
                parsedValueMessage = "Sensor expires in hours",
                rawMessage = "Sensor expires in 12 hours",
                rawValue = "",
                parsedValueRaw = "12",
                alertLabel = "Sensor Expiry",
                unitLabels = units
            )
        )
    }
}
