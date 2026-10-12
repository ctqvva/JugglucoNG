package tk.glucodata.alerts

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AlertSoundVolumeTests {
    @Test fun oldConfigsDefaultToFullSoundRegardlessOfVibration() {
        for (type in AlertType.entries) for (profile in HapticProfile.entries) {
            val config = AlertDefaults.defaultConfig(type, false).copy(hapticProfile = profile)
            assertEquals(1f, AlertSoundVolume.gain(config.soundVolumePercent), 0f)
        }
        val saved = CustomAlertConfig(hapticProfile = "soft").toJson().apply {
            remove("soundVolumePercent")
        }
        assertEquals(100, CustomAlertConfig.fromJson(saved).soundVolumePercent)
    }

    @Test fun customVolumeSurvivesSaveReloadAndReset() {
        val config = CustomAlertConfig(soundVolumePercent = 55, hapticProfile = "soft")
        val restored = CustomAlertConfig.fromJson(JSONObject(config.toJson().toString()))
        assertEquals(55, restored.soundVolumePercent)
        assertEquals("soft", restored.hapticProfile)
        assertEquals(100, restored.resetToDefaults(false).soundVolumePercent)
        assertTrue(config.isModifiedFromDefaults(false))
        assertEquals(100, AlertConfig(AlertType.LOW, soundVolumePercent = 55)
            .resetToDefaults(false).soundVolumePercent)
    }

    @Test fun corruptVolumeCannotMuteOrOverdrivePlayback() {
        for (input in listOf(Int.MIN_VALUE, -1, 0, 29, 30, 55, 100, 101, Int.MAX_VALUE)) {
            val gain = AlertSoundVolume.gain(input)
            assertTrue(gain in .3f..1f)
            val json = CustomAlertConfig().toJson().put("soundVolumePercent", input)
            assertEquals(AlertSoundVolume.sanitize(input), CustomAlertConfig.fromJson(json).soundVolumePercent)
        }
    }

    @Test fun fadeNeverExceedsUserVolumeAndReachesItBeforeShortAlarmsStop() {
        for (type in AlertType.entries) for (percent in listOf(30, 40, 55, 70, 100)) {
            val target = AlertSoundVolume.gain(percent)
            var previous = 0f
            for (step in 0..8) {
                val gain = AlertSoundVolume.fadeGain(percent, type.id, step / 8f)
                assertTrue(gain >= previous)
                assertTrue(gain <= target)
                assertTrue(gain >= .3f)
                previous = gain
            }
            assertEquals(target, previous, 0f)
            assertTrue(AlertSoundVolume.fadeMillis(type.id) < 1000L)
        }
        assertEquals(.7f, AlertSoundVolume.fadeGain(100, AlertType.LOW.id, 0f), 0f)
        assertEquals(120L, AlertSoundVolume.fadeMillis(AlertType.VERY_LOW.id))
    }
}
