package tk.glucodata

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SensorRemovalRedrawTests.TestApplication::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SensorRemovalRedrawTests {
    class TestApplication : Applic() {
        override fun onCreate() {}
    }

    @Test
    fun ordinaryUpdatesInsideTheIntervalAreDropped() {
        val throttle = WidgetUpdateThrottle(1_500L)
        assertTrue(throttle.tryAcquire(10_000L, force = false))
        assertFalse(throttle.tryAcquire(11_499L, force = false))
        assertTrue(throttle.tryAcquire(11_500L, force = false))
    }

    @Test
    fun removalRedrawRunsRightAfterAReadingDrewTheWidget() {
        val throttle = WidgetUpdateThrottle(1_500L)
        assertTrue(throttle.tryAcquire(10_000L, force = false))
        assertTrue(throttle.tryAcquire(10_001L, force = true))
        assertTrue(throttle.tryAcquire(10_002L, force = true))
    }

    @Test
    fun readingRightAfterARemovalRedrawStillDraws() {
        // A takeover sensor's first reading must replace the removal-time snapshot.
        val throttle = WidgetUpdateThrottle(1_500L)
        assertTrue(throttle.tryAcquire(10_000L, force = true))
        assertTrue(throttle.tryAcquire(10_001L, force = false))
    }

    @Test
    fun firstUpdateIsNeverThrottledRightAfterBoot() {
        // elapsedRealtime starts near zero, so a zero "last update" would read as recent.
        assertTrue(WidgetUpdateThrottle(1_500L).tryAcquire(100L, force = false))
    }

    @Test
    fun sendNowAsksWidgetsToSkipTheirThrottleEveryTime() {
        val app: Application = ApplicationProvider.getApplicationContext()
        GlucoseUpdateBroadcaster.sendNow(app)
        GlucoseUpdateBroadcaster.sendNow(app)

        val sent = shadowOf(app).broadcastIntents
            .filter { it.action == GlucoseUpdateBroadcaster.ACTION_GLUCOSE_UPDATE }
        assertEquals(2, sent.size)
        assertTrue(sent.all { it.getBooleanExtra(GlucoseUpdateBroadcaster.EXTRA_REDRAW_NOW, false) })
    }

    @Test
    fun ordinarySendLeavesWidgetsThrottled() {
        val app: Application = ApplicationProvider.getApplicationContext()
        // The broadcast throttle is static: step past any send from an earlier test.
        ShadowSystemClock.advanceBy(Duration.ofHours(1))
        GlucoseUpdateBroadcaster.send(app)

        val sent = shadowOf(app).broadcastIntents
            .filter { it.action == GlucoseUpdateBroadcaster.ACTION_GLUCOSE_UPDATE }
        assertEquals(1, sent.size)
        assertFalse(sent.single().getBooleanExtra(GlucoseUpdateBroadcaster.EXTRA_REDRAW_NOW, true))
    }

    /** removeDevice needs real JNI and live callbacks, so its order is checked in the source. */
    @Test
    fun removeDeviceRedrawsOnlyAfterTheCallbackLeftTheRoster() {
        val source = source("Common/src/main/java/tk/glucodata/SensorBluetooth.java")
        val body = source.substring(source.indexOf("private synchronized void removeDevice("))
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        val removed = body.indexOf("gattcallbacks.remove(i)")
        val redraw = body.indexOf("GlucoseUpdateBroadcaster.sendNow()")
        assertTrue(removed >= 0)
        assertTrue("the redraw must follow the roster change", redraw > removed)
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val file = File(dir, relative)
            if (file.isFile) return file.readText()
            dir = dir.parentFile
        }
        error("$relative not found")
    }
}
