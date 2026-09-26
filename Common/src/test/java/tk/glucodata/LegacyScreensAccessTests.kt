package tk.glucodata

import android.view.View
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The legacy-screen registry seam (plan §4, category P). The phone registers its
 * implementation; the watch registers nothing and [LegacyScreensAccess.get] returns null,
 * which the shared caller treats as "no such screen".
 */
class LegacyScreensAccessTests {
    private object Fake : LegacyScreens {
        override fun openLabels(activity: MainActivity, parent: View) {}
    }

    @Test
    fun aRegisteredFlavourIsHandedBack() {
        LegacyScreensAccess.register(Fake)

        assertTrue(LegacyScreensAccess.isRegistered())
        assertSame(Fake, LegacyScreensAccess.get())
    }
}
