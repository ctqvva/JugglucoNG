package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the wear unit-test source set is wired and actually runs: before T0.1
 * no wear test ever compiled, so a green wear build meant nothing.
 */
class WearTestSourceSetSmokeTest {

    @Test
    fun theWearFlavourBuildConfigIsOnTheTestClasspath() {
        assertEquals(1, BuildConfig.isWear)
        assertEquals("wear", BuildConfig.FLAVOR)
    }
}
