package tk.glucodata.alerts

import org.junit.Assert.*
import org.junit.Test

class AlertConfigResetTests {
    @Test fun standardResetUsesTypeDefaultsAndPreservesEnabledInBothUnits() {
        for (type in AlertType.settingsEntries) for (mmol in listOf(false, true)) {
            for (enabled in listOf(false, true)) {
                val changed = AlertConfig(type, enabled = enabled, threshold = 222f,
                    customSoundUri = "content://custom", soundEnabled = false,
                    timeRangeEnabled = true, activeStartHour = 22, activeEndHour = 6,
                    retryEnabled = true, retryCount = 17, soundDelayEnabled = true,
                    soundDelaySeconds = 25, deltaCount = 8, forecastMinutes = 60)
                val reset = changed.resetToDefaults(mmol)
                assertEquals(AlertDefaults.defaultConfig(type, mmol).copy(enabled = enabled), reset)
                assertEquals(reset, reset.resetToDefaults(mmol))
            }
        }
    }

    @Test fun customResetRetainsIdentityEnabledAndSnoozeButRestoresCreationSettings() {
        for (type in CustomAlertType.entries) for (mmol in listOf(false, true)) {
            for (enabled in listOf(false, true)) {
                val changed = CustomAlertConfig(id = "night", name = "Night alarm", type = type,
                    enabled = enabled, threshold = 222f, startTimeMinutes = 1320, endTimeMinutes = 360,
                    sound = false, vibrate = false, flash = true, soundUri = "content://custom",
                    style = "notification", overrideDnd = true, retryEnabled = true,
                    retryCount = 17, durationSeconds = 60, snoozedUntil = 123456789L)
                val reset = changed.resetToDefaults(mmol)
                val threshold = if (type == CustomAlertType.HIGH) {
                    if (mmol) 10f else 180f
                } else if (mmol) 3.9f else 70f
                assertEquals(CustomAlertConfig(id = "night", name = "Night alarm", type = type,
                    threshold = threshold, enabled = enabled, snoozedUntil = 123456789L), reset)
            }
        }
    }

    @Test fun standardModifiedTracksAnyEditButNotEnablementOrNativeThresholdRounding() {
        for (type in AlertType.settingsEntries) for (mmol in listOf(false, true)) {
            val default = AlertDefaults.defaultConfig(type, mmol)
            assertFalse("$type mmol=$mmol", default.isModifiedFromDefaults(mmol))
            assertFalse("$type mmol=$mmol", default.copy(enabled = !default.enabled).isModifiedFromDefaults(mmol))
            assertTrue("$type mmol=$mmol", default.copy(soundEnabled = !default.soundEnabled).isModifiedFromDefaults(mmol))
            assertTrue("$type mmol=$mmol", default.copy(retryCount = 17).isModifiedFromDefaults(mmol))
            val threshold = default.threshold ?: continue
            // Legacy alarms store whole mg/dl natively: 3.6 mmol/L saves as 65 and
            // reads back as 3.61. That must not count as an edit; a real step must.
            val roundTripped = if (mmol) Math.round(threshold * 18f) / 18f else threshold
            assertFalse("$type mmol=$mmol", default.copy(threshold = roundTripped).isModifiedFromDefaults(mmol))
            val step = if (mmol) 0.1f else 1f
            assertTrue("$type mmol=$mmol", default.copy(threshold = threshold + step).isModifiedFromDefaults(mmol))
        }
    }

    @Test fun customModifiedIgnoresIdentityEnablementAndSnooze() {
        for (type in CustomAlertType.entries) for (mmol in listOf(false, true)) {
            val fresh = CustomAlertConfig(name = "Night alarm", type = type).resetToDefaults(mmol)
            assertFalse(fresh.isModifiedFromDefaults(mmol))
            assertFalse(fresh.copy(enabled = false, snoozedUntil = 123456789L).isModifiedFromDefaults(mmol))
            assertTrue(fresh.copy(threshold = fresh.threshold + if (mmol) 0.1f else 1f).isModifiedFromDefaults(mmol))
            assertTrue(fresh.copy(startTimeMinutes = 1320).isModifiedFromDefaults(mmol))
            assertTrue(fresh.copy(flash = true).isModifiedFromDefaults(mmol))
        }
    }
}
