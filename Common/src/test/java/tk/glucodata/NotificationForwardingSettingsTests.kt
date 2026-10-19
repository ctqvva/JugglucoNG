package tk.glucodata

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NotificationForwardingSettingsTests.TestApplication::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class NotificationForwardingSettingsTests {
    class TestApplication : Applic() {
        override fun onCreate() {}
    }

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs get() = app.getSharedPreferences(NotificationForwardingSettings.PREFS, Context.MODE_PRIVATE)

    @Before fun clear() {
        prefs.edit().clear().commit()
    }

    private fun installedAt(first: Long, last: Long) {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        info.firstInstallTime = first
        info.lastUpdateTime = last
        shadowOf(app.packageManager).installPackage(info)
    }

    @Test fun periodicNotificationsAreOffAtFiveMinutesByDefault() {
        assertFalse(NotificationForwardingSettings.isPeriodicEnabled(app))
        assertEquals(5, NotificationForwardingSettings.periodicIntervalMinutes(app))
    }

    @Test fun freshInstallGetsStandardTextAndAnUpdateKeepsItOff() {
        installedAt(1_000L, 1_000L)
        NotificationForwardingSettings.pinStandardTextDefault(app)
        assertTrue(NotificationForwardingSettings.isStandardTextEnabled(app))

        prefs.edit().clear().commit()
        installedAt(1_000L, 9_000L)
        NotificationForwardingSettings.pinStandardTextDefault(app)
        assertFalse("1.2.5 users keep their watch quiet", NotificationForwardingSettings.isStandardTextEnabled(app))
        assertTrue("the default is pinned, not recomputed", prefs.contains(NotificationForwardingSettings.KEY_STANDARD_TEXT))
    }

    @Test fun anExplicitChoiceIsNeverOverwritten() {
        installedAt(1_000L, 1_000L)
        NotificationForwardingSettings.setStandardText(app, false)
        NotificationForwardingSettings.pinStandardTextDefault(app)
        assertFalse(NotificationForwardingSettings.isStandardTextEnabled(app))

        installedAt(1_000L, 9_000L)
        NotificationForwardingSettings.setStandardText(app, true)
        NotificationForwardingSettings.pinStandardTextDefault(app)
        assertTrue(NotificationForwardingSettings.isStandardTextEnabled(app))
    }

    @Test fun enablingPeriodicTurnsStandardTextOffAndDisablingLeavesItOff() {
        NotificationForwardingSettings.setStandardText(app, true)
        NotificationForwardingSettings.setPeriodicEnabled(app, true)
        assertFalse(NotificationForwardingSettings.isStandardTextEnabled(app))
        assertFalse(NotificationForwardingSettings.isStandardTextPreferred(prefs))

        NotificationForwardingSettings.setPeriodicEnabled(app, false)
        assertFalse(NotificationForwardingSettings.isPeriodicEnabled(app))
        assertFalse("not silently restored", NotificationForwardingSettings.isStandardTextEnabled(app))
    }

    @Test fun bothCanNeverBeOnTogether() {
        NotificationForwardingSettings.setPeriodicEnabled(app, true)
        NotificationForwardingSettings.setStandardText(app, true)
        assertFalse(NotificationForwardingSettings.isStandardTextEnabled(app))
        // Even a stored conflict (an old backup, a hand-edited file) resolves to periodic.
        prefs.edit().putBoolean(NotificationForwardingSettings.KEY_STANDARD_TEXT, true).commit()
        assertFalse(NotificationForwardingSettings.isStandardTextEnabled(app))
    }

    @Test fun settingsPersistAcrossAReread() {
        NotificationForwardingSettings.setPeriodicEnabled(app, true)
        NotificationForwardingSettings.setPeriodicIntervalMinutes(app, 10)
        val reread = app.getSharedPreferences(NotificationForwardingSettings.PREFS, Context.MODE_PRIVATE)
        assertTrue(NotificationForwardingSettings.isPeriodicEnabled(reread))
        assertEquals(10, NotificationForwardingSettings.periodicIntervalMinutes(reread))
    }

    @Test fun unknownIntervalsFallBackToTheDefault() {
        prefs.edit().putInt(NotificationForwardingSettings.KEY_PERIODIC_INTERVAL, 7).commit()
        assertEquals(5, NotificationForwardingSettings.periodicIntervalMinutes(app))
        NotificationForwardingSettings.setPeriodicIntervalMinutes(app, 1)
        assertEquals(1, NotificationForwardingSettings.periodicIntervalMinutes(app))
    }

    private fun resetPeriodicState() {
        app.getSharedPreferences("periodic_glucose_notification_state", Context.MODE_PRIVATE).edit().clear().commit()
        for (name in listOf("baselineReadingMillis", "settlingReadingMillis", "alertPostedElapsedMillis")) {
            PeriodicGlucoseUpdates::class.java.getDeclaredField(name).also { it.isAccessible = true }.setLong(null, 0L)
        }
        PeriodicGlucoseUpdates.onSettingChanged(false)
    }

    private fun settle() = ShadowSystemClock.advanceBy(Duration.ofMillis(PeriodicGlucoseUpdates.SETTLE_MS))

    /** Renders of one reading until it is no longer settling: what the posts would carry. */
    private fun claimSettled(readingMillis: Long, value: Float): Int {
        val first = PeriodicGlucoseUpdates.claim(readingMillis, value)
        if (first != PeriodicGlucoseUpdates.LATER) return first
        assertTrue(PeriodicGlucoseUpdates.settleRemainingMillis() > 0L)
        settle()
        return PeriodicGlucoseUpdates.claim(readingMillis, value)
    }

    @Test fun periodicUpdatesAnnounceEachReadingOnceOnTheOngoingNotification() {
        // Phone feature: the Wear build never announces.
        assumeFalse(Applic.isWearable)
        resetPeriodicState()
        val now = System.currentTimeMillis()
        assertEquals("off by default", PeriodicGlucoseUpdates.NOT_DUE, claimSettled(now - 20_000L, 5.5f))

        NotificationForwardingSettings.setPeriodicEnabled(app, true)
        NotificationForwardingSettings.setPeriodicIntervalMinutes(app, 1)
        assertEquals("the first post of a due reading waits for its alerts",
            PeriodicGlucoseUpdates.LATER, PeriodicGlucoseUpdates.claim(now - 20_000L, 5.5f))
        settle()
        assertEquals(PeriodicGlucoseUpdates.ANNOUNCE, PeriodicGlucoseUpdates.claim(now - 20_000L, 5.5f))
        assertEquals("repaints of the same reading carry no text",
            PeriodicGlucoseUpdates.NOT_DUE, claimSettled(now - 20_000L, 5.5f))
        assertEquals(0L, PeriodicGlucoseUpdates.settleRemainingMillis())
        assertEquals("next reading", PeriodicGlucoseUpdates.ANNOUNCE, claimSettled(now - 10_000L, 5.6f))

        NotificationForwardingSettings.setPeriodicIntervalMinutes(app, 5)
        assertEquals("inside the interval", PeriodicGlucoseUpdates.NOT_DUE, claimSettled(now, 5.7f))
        assertEquals("never a non-value", PeriodicGlucoseUpdates.NOT_DUE, claimSettled(now + 1_000L, 1.0f))
    }

    @Test fun switchingOnSendsTheCurrentReadingOnceWithoutWaiting() {
        assumeFalse(Applic.isWearable)
        resetPeriodicState()
        val now = System.currentTimeMillis()
        NotificationForwardingSettings.setPeriodicEnabled(app, true)
        assertEquals(PeriodicGlucoseUpdates.ANNOUNCE, claimSettled(now - 20_000L, 5.5f))
        PeriodicGlucoseUpdates.onSettingChanged(true)
        assertEquals(PeriodicGlucoseUpdates.ANNOUNCE, PeriodicGlucoseUpdates.claim(now - 20_000L, 5.5f))
        assertEquals(PeriodicGlucoseUpdates.NOT_DUE, claimSettled(now - 20_000L, 5.5f))
        PeriodicGlucoseUpdates.onSettingChanged(true)
        assertEquals("never a stale reading", PeriodicGlucoseUpdates.NOT_DUE,
            PeriodicGlucoseUpdates.claim(now - 400_000L, 5.5f))
    }

    @Test fun readingsFromBeforeProcessStartAreNotAnnounced() {
        assumeFalse(Applic.isWearable)
        resetPeriodicState()
        NotificationForwardingSettings.setPeriodicEnabled(app, true)
        val cached = System.currentTimeMillis() - 30_000L
        PeriodicGlucoseUpdates.onProcessStart()
        assertEquals(PeriodicGlucoseUpdates.NOT_DUE, claimSettled(cached, 5.5f))
    }

    @Test fun anAlarmPostedBeforeTheReadingIsShownCarriesIt() {
        // Native alarms: the alarm notification goes out first, stamped with the reading.
        assumeFalse(Applic.isWearable)
        resetPeriodicState()
        NotificationForwardingSettings.setPeriodicEnabled(app, true)
        NotificationForwardingSettings.setPeriodicIntervalMinutes(app, 1)
        val reading = System.currentTimeMillis() - 5_000L
        PeriodicGlucoseUpdates.noteAlertNotificationPosted(reading)
        assertEquals(PeriodicGlucoseUpdates.LATER, PeriodicGlucoseUpdates.claim(reading, 3.1f))
        settle()
        assertEquals(PeriodicGlucoseUpdates.NOT_DUE, PeriodicGlucoseUpdates.claim(reading, 3.1f))
        assertEquals("the next reading is not the alarm's",
            PeriodicGlucoseUpdates.ANNOUNCE, claimSettled(reading + 60_000L, 3.0f))
    }

    @Test fun aCustomAlertPostedAfterTheReadingIsShownCarriesIt() {
        // Custom alerts run after the ongoing notification and stamp their own time.
        assumeFalse(Applic.isWearable)
        resetPeriodicState()
        NotificationForwardingSettings.setPeriodicEnabled(app, true)
        NotificationForwardingSettings.setPeriodicIntervalMinutes(app, 1)
        val reading = System.currentTimeMillis() - 5_000L
        assertEquals(PeriodicGlucoseUpdates.LATER, PeriodicGlucoseUpdates.claim(reading, 3.1f))
        PeriodicGlucoseUpdates.noteAlertNotificationPosted(System.currentTimeMillis())
        settle()
        assertEquals("one watch message for the reading, the alarm's",
            PeriodicGlucoseUpdates.NOT_DUE, PeriodicGlucoseUpdates.claim(reading, 3.1f))

        // An alarm from before the next reading arrived does not swallow it.
        assertEquals(PeriodicGlucoseUpdates.ANNOUNCE, claimSettled(reading + 60_000L, 3.0f))
    }

    @Test fun everyLocaleTranslatesTheNewCopyWithTheSamePlaceholders() {
        val resDir = listOf("src/main/res", "Common/src/main/res").map(::File).first { it.isDirectory }
        val keys = listOf(
            "notification_forwarding_section", "notification_standard_text_title", "notification_standard_text_desc",
            "periodic_glucose_notifications_title", "periodic_glucose_notifications_desc",
            "periodic_glucose_interval_title", "periodic_glucose_interval_every", "periodic_glucose_interval_minutes"
        )
        fun value(file: File, key: String): String? =
            Regex("""<string name="$key"[^>]*>(.*?)</string>""").find(file.readText())?.groupValues?.get(1)
        val english = File(resDir, "values/strings.xml")
        val placeholders = Regex("%[0-9]+\\\$[ds]")
        val locales = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }!!
            .map { File(it, "strings.xml") }.filter { it.isFile }
        assertEquals(15, locales.size)
        for (file in locales) {
            for (key in keys) {
                val translated = value(file, key)
                assertNotNull("${file.parentFile.name}: missing $key", translated)
                assertEquals("${file.parentFile.name}: $key placeholders",
                    placeholders.findAll(value(english, key)!!).map { it.value }.toList(),
                    placeholders.findAll(translated!!).map { it.value }.toList())
            }
            assertTrue("${file.parentFile.name}: description still English",
                value(file, "periodic_glucose_notifications_desc") != value(english, "periodic_glucose_notifications_desc"))
        }
    }
}
