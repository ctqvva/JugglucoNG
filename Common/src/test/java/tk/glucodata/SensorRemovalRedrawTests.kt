package tk.glucodata

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorRemovalRedrawTests {
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
    fun firstUpdateIsNeverThrottledRightAfterBoot() {
        // elapsedRealtime starts near zero, so a zero "last update" would read as recent.
        assertTrue(WidgetUpdateThrottle(1_500L).tryAcquire(100L, force = false))
    }

    @Test
    fun removeDeviceRedrawsOnlyAfterTheCallbackLeftTheRoster() {
        val source = source("Common/src/main/java/tk/glucodata/SensorBluetooth.java")
        val body = source.substring(source.indexOf("private synchronized void removeDevice("))
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
