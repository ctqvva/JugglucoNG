package tk.glucodata.alerts

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AlertSoundVolumeTests {
    @Test fun defaultLeavesStreamHeadroomWithoutChangingOriginalIntensity() {
        val levels = mapOf(HapticProfile.SOFT to .4f, HapticProfile.STEADY to .7f,
            HapticProfile.STRONG to 1f, HapticProfile.ESCALATING to 1f)
        for (type in AlertType.entries) for ((profile, originalGain) in levels) {
            val config = AlertDefaults.defaultConfig(type, false).copy(hapticProfile = profile)
            assertEquals(60, config.soundVolumePercent)
            assertEquals(originalGain, AlertSoundVolume.profileGain(profile.name), 0f)
        }
        val saved = CustomAlertConfig(hapticProfile = "soft").toJson().apply { remove("soundVolumePercent") }
        val restored = CustomAlertConfig.fromJson(saved)
        assertEquals(60, restored.soundVolumePercent)
        assertEquals(.4f, AlertSoundVolume.profileGain(restored.hapticProfile), 0f)
        assertEquals(.4f, AlertSoundVolume.profileGain("low"), 0f)
        assertEquals(.7f, AlertSoundVolume.profileGain("medium"), 0f)
    }

    @Test fun manualMaximumSelectsTheActualDeviceMaximumInsteadOfAPlayerScalar() {
        for (maximum in listOf(7, 15, 25, 100)) {
            assertEquals(maximum, AlertSoundVolume.streamIndex(100, 0, maximum))
            assertTrue(AlertSoundVolume.streamIndex(60, 0, maximum) < maximum)
            assertTrue(AlertSoundVolume.streamIndex(30, 0, maximum) < AlertSoundVolume.streamIndex(60, 0, maximum))
        }
        assertEquals(3, AlertSoundVolume.streamIndex(30, 3, 7))
    }

    @Test fun customVolumeSurvivesSaveReloadAndReset() {
        val config = CustomAlertConfig(soundVolumePercent = 55, hapticProfile = "soft")
        val restored = CustomAlertConfig.fromJson(JSONObject(config.toJson().toString()))
        assertEquals(55, restored.soundVolumePercent)
        assertEquals("soft", restored.hapticProfile)
        assertEquals(60, restored.resetToDefaults(false).soundVolumePercent)
        assertTrue(config.isModifiedFromDefaults(false))
        assertEquals(60, AlertConfig(AlertType.LOW, soundVolumePercent = 55).resetToDefaults(false).soundVolumePercent)
    }

    @Test fun manuallyChosenMaximumIsPreservedInsteadOfBecomingTheNewDefault() {
        val restored = CustomAlertConfig.fromJson(CustomAlertConfig(soundVolumePercent = 100).toJson())
        assertEquals(100, restored.soundVolumePercent)
        assertEquals(60, restored.resetToDefaults(false).soundVolumePercent)
        assertTrue(restored.isModifiedFromDefaults(false))
    }

    @Test fun corruptVolumeStaysWithinTheDeviceRange() {
        for (input in listOf(Int.MIN_VALUE, -1, 0, 29, 30, 55, 100, 101, Int.MAX_VALUE)) {
            assertTrue(AlertSoundVolume.streamIndex(input, 0, 15) in 5..15)
            val json = CustomAlertConfig().toJson().put("soundVolumePercent", input)
            assertEquals(AlertSoundVolume.sanitize(input), CustomAlertConfig.fromJson(json).soundVolumePercent)
        }
    }

    @Test fun originalFadeIsPreservedForEveryProfile() {
        assertEquals(1200L, AlertSoundVolume.fadeMillis())
        for (profile in HapticProfile.entries) {
            val target = AlertSoundVolume.profileGain(profile.name)
            assertEquals(.12f, AlertSoundVolume.fadeGain(profile.name, 0f), 0f)
            assertEquals(.12f + (target - .12f) * .5f, AlertSoundVolume.fadeGain(profile.name, .5f), 0f)
            assertEquals(target, AlertSoundVolume.fadeGain(profile.name, 1f), .000001f)
            var previous = 0f
            for (step in 0..8) {
                val gain = AlertSoundVolume.fadeGain(profile.name, step / 8f)
                assertTrue(gain >= previous && gain <= target + .000001f)
                previous = gain
            }
        }
    }
}
