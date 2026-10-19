package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The hypo-safety cap policy for the delayed alarm sound. This is the single
 * source of truth used by both persistence (AlertRepository) and playback
 * (Notify.getSoundDelaySeconds), so it is worth pinning down.
 */
class SoundDelayCapTests {

    @Test
    fun capsAreTighterForHypoTypes() {
        assertEquals(60, maxSoundDelaySecondsFor(AlertType.LOW))
        assertEquals(30, maxSoundDelaySecondsFor(AlertType.VERY_LOW))
        assertEquals(300, maxSoundDelaySecondsFor(AlertType.HIGH))
        assertEquals(300, maxSoundDelaySecondsFor(AlertType.VERY_HIGH))
        assertEquals(300, maxSoundDelaySecondsFor(AlertType.PRE_LOW))
        assertEquals(300, maxSoundDelaySecondsFor(AlertType.MISSED_READING))
    }

    @Test
    fun sanitizeClampsToTypeCap() {
        assertEquals(60, sanitizeSoundDelaySeconds(AlertType.LOW, 120))
        assertEquals(30, sanitizeSoundDelaySeconds(AlertType.VERY_LOW, 120))
        assertEquals(120, sanitizeSoundDelaySeconds(AlertType.HIGH, 120))
        assertEquals(300, sanitizeSoundDelaySeconds(AlertType.HIGH, 999))
    }

    @Test
    fun sanitizeClampsNegativesToZero() {
        assertEquals(0, sanitizeSoundDelaySeconds(AlertType.HIGH, -5))
        assertEquals(0, sanitizeSoundDelaySeconds(AlertType.LOW, 0))
        assertEquals(45, sanitizeSoundDelaySeconds(AlertType.LOW, 45))
    }

    @Test
    fun defaultConfigHasNoDelayForEveryType() {
        for (type in AlertType.entries) {
            val cfg = AlertConfig(type = type)
            assertEquals(false, cfg.soundDelayEnabled)
            assertEquals(0, cfg.soundDelaySeconds)
        }
    }
}
