package tk.glucodata

import android.app.Application
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 33], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MainActivitySystemBarsTests {
    @Test
    fun visibilityChangesPreserveThemeIconAppearance() {
        // Attach the window without starting the native glucose stack in onCreate.
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)

        for (lightBackground in listOf(true, false)) {
            controller.isAppearanceLightStatusBars = lightBackground
            controller.isAppearanceLightNavigationBars = lightBackground

            // onStart also requests visible bars when they are already visible.
            activity.showSystemUI()
            assertEquals(lightBackground, controller.isAppearanceLightStatusBars)
            assertEquals(lightBackground, controller.isAppearanceLightNavigationBars)

            activity.setSystemUI(false)
            activity.showSystemUI()
            assertEquals(lightBackground, controller.isAppearanceLightStatusBars)
            assertEquals(lightBackground, controller.isAppearanceLightNavigationBars)
        }
    }
}
