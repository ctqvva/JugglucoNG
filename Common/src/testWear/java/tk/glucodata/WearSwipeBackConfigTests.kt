package tk.glucodata

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On Wear OS 6+ (API 36+), SwipeDismissableNavHost relies on platform predictive
 * back events, which android:windowSwipeToDismiss=false suppresses (#262).
 * Older Wear OS versions keep false so root-screen swipe behavior is unchanged,
 * while the interactive chart exposes horizontal scroll semantics so sideways
 * pans are not intercepted as a dismiss swipe.
 */
class WearSwipeBackConfigTests {

    private val moduleRoot = File("").absoluteFile.let { working ->
        generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "src/wear/res/values/styles.xml").exists() }
            ?: File(working, "Common").takeIf { File(it, "src/wear/res/values/styles.xml").exists() }
            ?: working
    }

    @Test
    fun `watch theme enables window swipe-to-dismiss on API 36+ only`() {
        val styles = File(moduleRoot, "src/wear/res/values/styles.xml").readText()
        val defaultBools = File(moduleRoot, "src/wear/res/values/bools.xml").readText()
        val api36Bools = File(moduleRoot, "src/wear/res/values-v36/bools.xml").readText()

        assertTrue(
            "Theme.WatchFace must bind android:windowSwipeToDismiss to @bool/wear_window_swipe_to_dismiss",
            styles.contains("""<item name="android:windowSwipeToDismiss">@bool/wear_window_swipe_to_dismiss</item>"""),
        )
        assertTrue(
            "Pre-API 36 Wear OS must keep windowSwipeToDismiss=false",
            defaultBools.contains("""<bool name="wear_window_swipe_to_dismiss">false</bool>"""),
        )
        assertTrue(
            "API 36+ Wear OS must enable windowSwipeToDismiss=true for SwipeDismissableNavHost",
            api36Bools.contains("""<bool name="wear_window_swipe_to_dismiss">true</bool>"""),
        )
    }

    @Test
    fun `interactive wear chart reports horizontal scroll semantics`() {
        val chart = File(moduleRoot, "src/wear/java/tk/glucodata/ui/screens/ChartScreen.kt").readText()
        assertTrue(
            "Interactive WearChart must expose horizontalScrollAxisRange so sideways pans are not stolen by window swipe-to-dismiss",
            chart.contains("horizontalScrollAxisRange = ScrollAxisRange("),
        )
    }
}
