package tk.glucodata

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

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

    @Test fun periodicNotificationIsAnOrdinaryReadingStampedNotification() {
        // Phone feature: the Wear build never creates the channel or posts.
        assumeFalse(Applic.isWearable)
        val manager = app.getSystemService(NotificationManager::class.java)
        PeriodicGlucoseNotifier.createChannel(app, manager)
        val channel = manager.getNotificationChannel(PeriodicGlucoseNotifier.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertNull("no sound of its own", channel.sound)
        assertFalse(channel.shouldVibrate())

        val readingAt = 1_760_000_000_000L
        val notification = PeriodicGlucoseNotifier.builder(app, "9,0 mmol/L ↗", "Δ +0,2 · 12:40",
            readingAt, readingAt + 60_000L, 330_000L)
            .setSmallIcon(android.R.drawable.ic_dialog_info).build()
        assertEquals(PeriodicGlucoseNotifier.CHANNEL_ID, notification.channelId)
        assertEquals("9,0 mmol/L ↗", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Δ +0,2 · 12:40", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("stamped with the measurement, not the post", readingAt, notification.`when`)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertEquals("removed when the reading turns stale", 270_000L, notification.timeoutAfter)
        assertEquals(0, notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(0, notification.flags and Notification.FLAG_FOREGROUND_SERVICE)
        assertTrue(notification.category != Notification.CATEGORY_ALARM)
        assertNull("dismissing it must not stop an alarm", notification.deleteIntent)
        assertNull(notification.group)
    }

    @Test fun turningPeriodicOffCancelsTheOutstandingNotificationOnly() {
        assumeFalse(Applic.isWearable)
        val manager = app.getSystemService(NotificationManager::class.java)
        PeriodicGlucoseNotifier.createChannel(app, manager)
        val periodic = PeriodicGlucoseNotifier.builder(app, "9,0 mmol/L", "12:40", 1L, 1L, 330_000L)
            .setSmallIcon(android.R.drawable.ic_dialog_info).build()
        manager.notify(PeriodicGlucoseNotifier.NOTIFICATION_ID, periodic)
        val alarm = Notification.Builder(app, PeriodicGlucoseNotifier.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("LOW 3,1").build()
        manager.notify(81432, alarm)

        PeriodicGlucoseNotifier.onSettingChanged(false)
        val handlerField = PeriodicGlucoseNotifier::class.java.getDeclaredField("handler").also { it.isAccessible = true }
        shadowOf((handlerField.get(null) as android.os.Handler).looper).idle()

        assertNull(shadowOf(manager).getNotification(PeriodicGlucoseNotifier.NOTIFICATION_ID))
        assertNotNull("alarms are left alone", shadowOf(manager).getNotification(81432))
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test fun freshnessTimeoutNeverGoesBelowASecond() {
        assertEquals(1_000L, PeriodicGlucoseNotifier.timeoutAfterMillis(0L, 400_000L, 330_000L))
        assertEquals(330_000L, PeriodicGlucoseNotifier.timeoutAfterMillis(5_000L, 1_000L, 330_000L))
    }

    @Test fun everyLocaleTranslatesTheNewCopyWithTheSamePlaceholders() {
        val resDir = listOf("src/main/res", "Common/src/main/res").map(::File).first { it.isDirectory }
        val keys = listOf(
            "notification_forwarding_section", "notification_standard_text_title", "notification_standard_text_desc",
            "notification_standard_text_blocked_desc", "periodic_glucose_notifications_title",
            "periodic_glucose_notifications_desc", "periodic_glucose_interval_title", "periodic_glucose_interval_note",
            "periodic_glucose_interval_every", "periodic_glucose_interval_minutes", "periodic_glucose_channel_name",
            "periodic_glucose_channel_desc"
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
