package tk.glucodata.alerts

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AlertVolumeTestApplication::class)
class AlertSoundBoostTests {
    private class Effect : AlertSoundBoost.Effect {
        var target = -1
        var enabled = false
        var releases = 0
        var failEnable = false
        var failTarget = false
        override fun targetGain(millibels: Int) {
            if (failTarget) throw UnsupportedOperationException("No gain control")
            target = millibels
        }
        override fun enable() {
            if (failEnable) throw IllegalStateException("No effect engine")
            enabled = true
        }
        override fun release() { releases++ }
    }

    @Test fun normalLevelsDoNotCreateAnEffectOrProcessTheAudio() {
        for (percent in listOf(Int.MIN_VALUE, 30, 55, 75, 80)) {
            assertEquals(0, AlertSoundVolume.boostMillibels(percent))
            assertNull(AlertSoundBoost.attach(42, percent) { fail("Normal playback created an effect"); Effect() })
        }
    }

    @Test fun boostedEndAddsSixDbOnTopOfTheExistingPlayerGain() {
        assertEquals(150, AlertSoundVolume.boostMillibels(85))
        assertEquals(300, AlertSoundVolume.boostMillibels(90))
        assertEquals(600, AlertSoundVolume.boostMillibels(100))
        assertEquals(600, AlertSoundVolume.boostMillibels(Int.MAX_VALUE))
        assertEquals(.8f, AlertSoundVolume.gain(80), 0f)
        assertEquals(1f, AlertSoundVolume.gain(100), 0f)
    }

    @Test fun globalMixAndInvalidSessionsAreNeverTouched() {
        for (session in listOf(0, -1, Int.MIN_VALUE)) {
            assertNull(AlertSoundBoost.attach(session, 100) { fail("Shared/invalid audio session used"); Effect() })
        }
    }

    @Test fun eachAlertOwnsOnlyItsRequestedSessionAndReleaseIsIdempotent() {
        val created = mutableListOf<Int>()
        val effects = mutableListOf<Effect>()
        val factory = AlertSoundBoost.Factory { id -> created.add(id); Effect().also(effects::add) }
        val first = AlertSoundBoost.attach(42, 100, factory)!!
        val second = AlertSoundBoost.attach(43, 90, factory)!!
        assertEquals(listOf(42, 43), created)
        assertEquals(600, effects[0].target)
        assertEquals(300, effects[1].target)
        assertTrue(effects.all { it.enabled })
        first.close()
        first.close()
        assertEquals(1, effects[0].releases)
        assertEquals(0, effects[1].releases)
        second.close()
        assertEquals(1, effects[1].releases)
    }

    @Test fun unsupportedEffectFallsBackWithoutLeakingOrBlockingNormalPlayback() {
        assertNull(AlertSoundBoost.attach(42, 100) { throw UnsupportedOperationException("Unavailable") })
        for (failTarget in listOf(false, true)) {
            val effect = Effect().apply { this.failTarget = failTarget; failEnable = !failTarget }
            assertNull(AlertSoundBoost.attach(42, 100) { effect })
            assertEquals(1, effect.releases)
        }
    }
}
