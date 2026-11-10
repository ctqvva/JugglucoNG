package tk.glucodata.alerts

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AlertSoundVolumeTests {
    @Test fun defaultLeavesManualHeadroomAndRetainsOverallIntensityRatios() {
        val levels = mapOf(HapticProfile.SOFT to .4f, HapticProfile.STEADY to .7f,
            HapticProfile.STRONG to 1f, HapticProfile.ESCALATING to 1f)
        for (type in AlertType.entries) for ((profile, originalGain) in levels) {
            val config = AlertDefaults.defaultConfig(type, false).copy(hapticProfile = profile)
            assertEquals(80, config.soundVolumePercent)
            assertEquals(originalGain * .8f, AlertSoundVolume.gain(config.soundVolumePercent, profile.name), 0f)
            assertTrue(AlertSoundVolume.gain(100, profile.name) > AlertSoundVolume.gain(config.soundVolumePercent, profile.name))
        }
        val saved = CustomAlertConfig(hapticProfile = "soft").toJson().apply {
            remove("soundVolumePercent")
        }
        val restored = CustomAlertConfig.fromJson(saved)
        assertEquals(80, restored.soundVolumePercent)
        assertEquals(.4f * .8f, AlertSoundVolume.gain(restored.soundVolumePercent, restored.hapticProfile), 0f)
        assertEquals(.4f, AlertSoundVolume.gain(100, "low"), 0f)
        assertEquals(.7f, AlertSoundVolume.gain(100, "medium"), 0f)
    }

    @Test fun sliderScalesEveryOverallIntensityWithoutMutingOrOverdriving() {
        for (profile in HapticProfile.entries) {
            val originalGain = AlertSoundVolume.gain(100, profile.name)
            assertEquals(originalGain * .55f, AlertSoundVolume.gain(55, profile.name), 0f)
            assertEquals(originalGain * .3f, AlertSoundVolume.gain(-999, profile.name), 0f)
            assertEquals(originalGain, AlertSoundVolume.gain(999, profile.name), 0f)
        }
    }

    @Test fun customVolumeSurvivesSaveReloadAndReset() {
        val config = CustomAlertConfig(soundVolumePercent = 55, hapticProfile = "soft")
        val restored = CustomAlertConfig.fromJson(JSONObject(config.toJson().toString()))
        assertEquals(55, restored.soundVolumePercent)
        assertEquals("soft", restored.hapticProfile)
        assertEquals(80, restored.resetToDefaults(false).soundVolumePercent)
        assertTrue(config.isModifiedFromDefaults(false))
        assertEquals(80, AlertConfig(AlertType.LOW, soundVolumePercent = 55)
            .resetToDefaults(false).soundVolumePercent)
    }

    @Test fun manuallyChosenMaximumIsPreservedInsteadOfBecomingTheNewDefault() {
        val maximum = CustomAlertConfig(soundVolumePercent = 100)
        val restored = CustomAlertConfig.fromJson(JSONObject(maximum.toJson().toString()))
        assertEquals(100, restored.soundVolumePercent)
        assertEquals(80, restored.resetToDefaults(false).soundVolumePercent)
        assertTrue(restored.isModifiedFromDefaults(false))
    }

    @Test fun corruptVolumeCannotMuteOrOverdrivePlayback() {
        for (input in listOf(Int.MIN_VALUE, -1, 0, 29, 30, 55, 100, 101, Int.MAX_VALUE)) {
            val gain = AlertSoundVolume.gain(input)
            assertTrue(gain in .3f..1f)
            val json = CustomAlertConfig().toJson().put("soundVolumePercent", input)
            assertEquals(AlertSoundVolume.sanitize(input), CustomAlertConfig.fromJson(json).soundVolumePercent)
        }
    }

    @Test fun originalFadeTimingAndStartAreRetainedForAllProfiles() {
        assertEquals(1200L, AlertSoundVolume.fadeMillis())
        for (profile in HapticProfile.entries) {
            val percent = AlertSoundVolume.DEFAULT_PERCENT
            val target = AlertSoundVolume.gain(percent, profile.name)
            assertEquals(.12f, AlertSoundVolume.fadeGain(percent, profile.name, 0f), 0f)
            assertEquals(.12f + (target - .12f) * .5f,
                AlertSoundVolume.fadeGain(percent, profile.name, .5f), 0f)
            assertEquals(target, AlertSoundVolume.fadeGain(percent, profile.name, 1f), .000001f)
        }
    }

    @Test fun fadeNeverExceedsTheAdjustedProfileGain() {
        for (profile in HapticProfile.entries) for (percent in listOf(30, 40, 55, 70, 100)) {
            val target = AlertSoundVolume.gain(percent, profile.name)
            var previous = 0f
            for (step in 0..8) {
                val gain = AlertSoundVolume.fadeGain(percent, profile.name, step / 8f)
                assertTrue(gain >= previous)
                assertTrue(gain <= target + .000001f)
                assertTrue(gain >= .12f)
                previous = gain
            }
            assertEquals(target, previous, .000001f)
        }
    }
}
