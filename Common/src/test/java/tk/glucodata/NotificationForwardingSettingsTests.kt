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

    @Test fun forwardingIsOffAtFiveMinutesByDefault() {
        assertEquals(NotificationForwardingSettings.MODE_OFF, NotificationForwardingSettings.mode(app))
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
        NotificationForwardingSettings.setMode(app, NotificationForwardingSettings.MODE_OFF)
        NotificationForwardingSettings.pinStandardTextDefault(app)
        assertFalse(NotificationForwardingSettings.isStandardTextEnabled(app))

        installedAt(1_000L, 9_000L)
        NotificationForwardingSettings.setMode(app, NotificationForwardingSettings.MODE_STANDARD_TEXT)
        NotificationForwardingSettings.pinStandardTextDefault(app)
        assertTrue(NotificationForwardingSettings.isStandardTextEnabled(app))
    }

    @Test fun eachModeIsOneExclusiveChoice() {
        NotificationForwardingSettings.setMode(app, NotificationForwardingSettings.MODE_STANDARD_TEXT)
        assertEquals(NotificationForwardingSettings.MODE_STANDARD_TEXT, NotificationForwardingSettings.mode(app))
        assertTrue(NotificationForwardingSettings.isStandardTextEnabled(app))
        assertFalse(NotificationForwardingSettings.isPeriodicEnabled(app))

        // Not standard text: only the post announcing a new reading carries it.
        NotificationForwardingSettings.setMode(app, NotificationForwardingSettings.MODE_EVERY_READING)
        assertEquals(NotificationForwardingSettings.MODE_EVERY_READING, NotificationForwardingSettings.mode(app))
        assertFalse(NotificationForwardingSettings.isStandardTextEnabled(app))
        assertTrue(NotificationForwardingSettings.isPeriodicEnabled(app))
        assertEquals(1, NotificationForwardingSettings.periodicIntervalMinutes(app))

        NotificationForwardingSettings.setMode(app, 3)
        assertEquals(3, NotificationForwardingSettings.mode(app))
        assertFalse(NotificationForwardingSettings.isStandardTextEnabled(app))
        assertTrue(NotificationForwardingSettings.isPeriodicEnabled(app))

        NotificationForwardingSettings.setMode(app, NotificationForwardingSettings.MODE_OFF)
        assertEquals(NotificationForwardingSettings.MODE_OFF, NotificationForwardingSettings.mode(app))
        assertFalse(NotificationForwardingSettings.isStandardTextEnabled(app))
        assertFalse(NotificationForwardingSettings.isPeriodicEnabled(app))
        assertEquals("the interval is remembered", 3, NotificationForwardingSettings.periodicIntervalMinutes(app))
    }

    @Test fun theModePickerKeepsEveryNewReadingApartFromTheIntervals() {
        val settings = NotificationForwardingSettings
        assertEquals(listOf(settings.MODE_STANDARD_TEXT, settings.MODE_OFF, settings.MODE_EVERY_READING, settings.MODE_INTERVAL),
            settings.MODE_CHOICES.toList())
        assertEquals(listOf(3, 5, 10), settings.INTERVAL_CHOICES.toList())
        assertEquals(settings.MODE_STANDARD_TEXT, settings.choice(settings.MODE_STANDARD_TEXT))
        assertEquals(settings.MODE_OFF, settings.choice(settings.MODE_OFF))
        assertEquals(settings.MODE_EVERY_READING, settings.choice(settings.MODE_EVERY_READING))
        for (minutes in settings.INTERVAL_CHOICES) assertEquals(settings.MODE_INTERVAL, settings.choice(minutes))
    }

    @Test fun choosingTheIntervalModeBringsBackTheLastInterval() {
        val settings = NotificationForwardingSettings
        assertEquals("nothing chosen yet", 5, settings.modeForChoice(app, settings.MODE_INTERVAL))

        settings.setMode(app, 10)
        settings.setMode(app, settings.MODE_STANDARD_TEXT)
        assertEquals(10, settings.modeForChoice(app, settings.MODE_INTERVAL))
        settings.setMode(app, settings.MODE_OFF)
        assertEquals(10, settings.modeForChoice(app, settings.MODE_INTERVAL))

        // Every new reading is stored as the 1-minute interval, which the interval picker does not offer.
        settings.setMode(app, settings.MODE_EVERY_READING)
        assertEquals(5, settings.modeForChoice(app, settings.MODE_INTERVAL))

        for (choice in intArrayOf(settings.MODE_STANDARD_TEXT, settings.MODE_OFF, settings.MODE_EVERY_READING))
            assertEquals(choice, settings.modeForChoice(app, choice))
    }

    @Test fun aStoredConflictResolvesToPeriodic() {
        // An old backup or a hand-edited file can hold both.
        prefs.edit().putBoolean(NotificationForwardingSettings.KEY_STANDARD_TEXT, true)
            .putBoolean(NotificationForwardingSettings.KEY_PERIODIC_ENABLED, true).commit()
        assertFalse(NotificationForwardingSettings.isStandardTextEnabled(app))
        assertEquals(5, NotificationForwardingSettings.mode(app))
    }

    @Test fun settingsPersistAcrossAReread() {
        NotificationForwardingSettings.setMode(app, 10)
        val reread = app.getSharedPreferences(NotificationForwardingSettings.PREFS, Context.MODE_PRIVATE)
        assertTrue(NotificationForwardingSettings.isPeriodicEnabled(reread))
        assertEquals(10, NotificationForwardingSettings.periodicIntervalMinutes(reread))
    }

    @Test fun unknownIntervalsFallBackToTheDefault() {
        prefs.edit().putInt(NotificationForwardingSettings.KEY_PERIODIC_INTERVAL, 7).commit()
        assertEquals(5, NotificationForwardingSettings.periodicIntervalMinutes(app))
        prefs.edit().putInt(NotificationForwardingSettings.KEY_PERIODIC_INTERVAL, 1).commit()
        assertEquals("every new reading is kept", 1, NotificationForwardingSettings.periodicIntervalMinutes(app))
    }

    private fun resetPeriodicState() {
        app.getSharedPreferences("periodic_glucose_notification_state", Context.MODE_PRIVATE).edit().clear().commit()
        for (name in listOf("baselineReadingMillis", "settlingReadingMillis", "alertPostedElapsedMillis",
                "pendingReadingMillis")) {
            PeriodicGlucoseUpdates::class.java.getDeclaredField(name).also { it.isAccessible = true }.setLong(null, 0L)
        }
        PeriodicGlucoseUpdates.onSettingChanged(false)
    }

    /** As if nothing had been announced yet, so the next reading is due regardless of the interval. */
    private fun forgetLastAnnouncement() {
        app.getSharedPreferences("periodic_glucose_notification_state", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun settle() = ShadowSystemClock.advanceBy(Duration.ofMillis(PeriodicGlucoseUpdates.SETTLE_MS))

    /** A render's claim whose notification is then posted. */
    private fun claimPosted(readingMillis: Long, value: Float): Int =
        PeriodicGlucoseUpdates.claim(readingMillis, value).also {
            if (it == PeriodicGlucoseUpdates.ANNOUNCE) PeriodicGlucoseUpdates.commitAnnouncement(readingMillis)
        }

    /** Posted renders of one reading until it is no longer settling: what the posts would carry. */
    private fun claimSettled(readingMillis: Long, value: Float): Int {
        val first = claimPosted(readingMillis, value)
        if (first != PeriodicGlucoseUpdates.LATER) return first
        assertTrue(PeriodicGlucoseUpdates.settleRemainingMillis() > 0L)
        settle()
        return claimPosted(readingMillis, value)
    }

    @Test fun periodicUpdatesAnnounceEachReadingOnceOnTheOngoingNotification() {
        // Phone feature: the Wear build never announces.
        assumeFalse(Applic.isWearable)
        resetPeriodicState()
        val now = System.currentTimeMillis()
        assertEquals("off by default", PeriodicGlucoseUpdates.NOT_DUE, claimSettled(now - 20_000L, 5.5f))

        NotificationForwardingSettings.setMode(app, 3)
        assertEquals("the first post of a due reading waits for its alerts",
            PeriodicGlucoseUpdates.LATER, PeriodicGlucoseUpdates.claim(now - 20_000L, 5.5f))
        settle()
        assertEquals(PeriodicGlucoseUpdates.ANNOUNCE, claimPosted(now - 20_000L, 5.5f))
        assertEquals("repaints of the same reading carry no text",
            PeriodicGlucoseUpdates.NOT_DUE, claimSettled(now - 20_000L, 5.5f))
        assertEquals(0L, PeriodicGlucoseUpdates.settleRemainingMillis())
        assertEquals("inside the interval", PeriodicGlucoseUpdates.NOT_DUE, claimSettled(now - 10_000L, 5.6f))

        // The interval itself is PeriodicGlucoseNotificationPolicyTests' business.
        forgetLastAnnouncement()
        assertEquals("next interval", PeriodicGlucoseUpdates.ANNOUNCE, claimSettled(now - 5_000L, 5.7f))
        forgetLastAnnouncement()
        assertEquals("never a non-value", PeriodicGlucoseUpdates.NOT_DUE, claimSettled(now, 1.0f))
    }

    @Test fun everyNewReadingAnnouncesEachMeasurementOnce() {
        assumeFalse(Applic.isWearable)
        resetPeriodicState()
        val now = System.currentTimeMillis()
        NotificationForwardingSettings.setMode(app, NotificationForwardingSettings.MODE_EVERY_READING)
        assertEquals(PeriodicGlucoseUpdates.ANNOUNCE, claimSettled(now - 70_000L, 5.5f))
        assertEquals("a refresh of the same reading carries no text",
            PeriodicGlucoseUpdates.NOT_DUE, claimSettled(now - 70_000L, 5.5f))
        assertEquals("the next reading is due right away",
            PeriodicGlucoseUpdates.ANNOUNCE, claimSettled(now - 10_000L, 5.6f))
        assertEquals(PeriodicGlucoseUpdates.NOT_DUE, claimSettled(now - 10_000L, 5.6f))

        // Standard text is on every post instead; nothing is announced.
        NotificationForwardingSettings.setMode(app, NotificationForwardingSettings.MODE_STANDARD_TEXT)
        assertEquals(PeriodicGlucoseUpdates.NOT_DUE, claimSettled(now, 5.7f))
    }

    @Test fun switchingOnSendsTheCurrentReadingOnceWithoutWaiting() {
        assumeFalse(Applic.isWearable)
        resetPeriodicState()
        val now = System.currentTimeMillis()
        NotificationForwardingSettings.setMode(app, 5)
        assertEquals(PeriodicGlucoseUpdates.ANNOUNCE, claimSettled(now - 20_000L, 5.5f))
        PeriodicGlucoseUpdates.onSettingChanged(true)
        assertEquals(PeriodicGlucoseUpdates.ANNOUNCE, claimPosted(now - 20_000L, 5.5f))
        assertEquals(PeriodicGlucoseUpdates.NOT_DUE, claimSettled(now - 20_000L, 5.5f))
        PeriodicGlucoseUpdates.onSettingChanged(true)
        assertEquals("never a stale reading", PeriodicGlucoseUpdates.NOT_DUE,
            PeriodicGlucoseUpdates.claim(now - 400_000L, 5.5f))
    }

    @Test fun anAnnouncementCountsOnlyOnceItIsPosted() {
        assumeFalse(Applic.isWearable)
        resetPeriodicState()
        val reading = System.currentTimeMillis() - 20_000L
        NotificationForwardingSettings.setMode(app, 5)
        assertEquals(PeriodicGlucoseUpdates.LATER, PeriodicGlucoseUpdates.claim(reading, 5.5f))
        settle()
        assertEquals(PeriodicGlucoseUpdates.ANNOUNCE, PeriodicGlucoseUpdates.claim(reading, 5.5f))
        // A concurrent render waits for the one holding the announcement.
        assertEquals(PeriodicGlucoseUpdates.LATER, PeriodicGlucoseUpdates.claim(reading, 5.5f))
        assertEquals(PeriodicGlucoseUpdates.PENDING_RECHECK_MS, PeriodicGlucoseUpdates.settleRemainingMillis())

        // That render was superseded (or failed to post): the next one announces, without settling again.
        PeriodicGlucoseUpdates.releaseAnnouncement(reading)
        assertEquals(PeriodicGlucoseUpdates.ANNOUNCE, PeriodicGlucoseUpdates.claim(reading, 5.5f))
        PeriodicGlucoseUpdates.commitAnnouncement(reading)
        assertEquals("posted: later renders carry no text",
            PeriodicGlucoseUpdates.NOT_DUE, claimSettled(reading, 5.5f))
        PeriodicGlucoseUpdates.releaseAnnouncement(reading)
        assertEquals("a late release changes nothing", PeriodicGlucoseUpdates.NOT_DUE, claimSettled(reading, 5.5f))
    }

    @Test fun anAnnouncementHeldByARenderThatThrewIsGivenUp() {
        assumeFalse(Applic.isWearable)
        resetPeriodicState()
        val reading = System.currentTimeMillis() - 20_000L
        NotificationForwardingSettings.setMode(app, 5)
        assertEquals(PeriodicGlucoseUpdates.LATER, PeriodicGlucoseUpdates.claim(reading, 5.5f))
        settle()
        assertEquals(PeriodicGlucoseUpdates.ANNOUNCE, PeriodicGlucoseUpdates.claim(reading, 5.5f))
        ShadowSystemClock.advanceBy(Duration.ofMillis(PeriodicGlucoseUpdates.PENDING_TIMEOUT_MS))
        assertEquals(PeriodicGlucoseUpdates.ANNOUNCE, claimPosted(reading, 5.5f))
        assertEquals(PeriodicGlucoseUpdates.NOT_DUE, claimSettled(reading, 5.5f))
    }

    @Test fun switchingOnStaysPendingUntilTheAnnouncementIsPosted() {
        assumeFalse(Applic.isWearable)
        resetPeriodicState()
        val reading = System.currentTimeMillis() - 20_000L
        NotificationForwardingSettings.setMode(app, 5)
        assertEquals(PeriodicGlucoseUpdates.ANNOUNCE, claimSettled(reading, 5.5f))
        PeriodicGlucoseUpdates.onSettingChanged(true)
        assertEquals(PeriodicGlucoseUpdates.ANNOUNCE, PeriodicGlucoseUpdates.claim(reading, 5.5f))
        PeriodicGlucoseUpdates.releaseAnnouncement(reading)
        assertEquals("still owed after a discarded render",
            PeriodicGlucoseUpdates.ANNOUNCE, claimPosted(reading, 5.5f))
        assertEquals(PeriodicGlucoseUpdates.NOT_DUE, claimSettled(reading, 5.5f))
    }

    @Test fun readingsFromBeforeProcessStartAreNotAnnounced() {
        assumeFalse(Applic.isWearable)
        resetPeriodicState()
        NotificationForwardingSettings.setMode(app, 5)
        val cached = System.currentTimeMillis() - 30_000L
        PeriodicGlucoseUpdates.onProcessStart()
        assertEquals(PeriodicGlucoseUpdates.NOT_DUE, claimSettled(cached, 5.5f))
    }

    @Test fun anAlarmPostedBeforeTheReadingIsShownCarriesIt() {
        // Native alarms: the alarm notification goes out first, stamped with the reading.
        assumeFalse(Applic.isWearable)
        resetPeriodicState()
        NotificationForwardingSettings.setMode(app, 3)
        val reading = System.currentTimeMillis() - 5_000L
        PeriodicGlucoseUpdates.noteAlertNotificationPosted(reading)
        assertEquals(PeriodicGlucoseUpdates.LATER, PeriodicGlucoseUpdates.claim(reading, 3.1f))
        settle()
        assertEquals(PeriodicGlucoseUpdates.NOT_DUE, PeriodicGlucoseUpdates.claim(reading, 3.1f))
        forgetLastAnnouncement()
        assertEquals("the next reading is not the alarm's",
            PeriodicGlucoseUpdates.ANNOUNCE, claimSettled(reading + 60_000L, 3.0f))
    }

    @Test fun aCustomAlertPostedAfterTheReadingIsShownCarriesIt() {
        // Custom alerts run after the ongoing notification and stamp their own time.
        assumeFalse(Applic.isWearable)
        resetPeriodicState()
        NotificationForwardingSettings.setMode(app, 3)
        val reading = System.currentTimeMillis() - 5_000L
        assertEquals(PeriodicGlucoseUpdates.LATER, PeriodicGlucoseUpdates.claim(reading, 3.1f))
        PeriodicGlucoseUpdates.noteAlertNotificationPosted(System.currentTimeMillis())
        settle()
        assertEquals("one watch message for the reading, the alarm's",
            PeriodicGlucoseUpdates.NOT_DUE, PeriodicGlucoseUpdates.claim(reading, 3.1f))

        // An alarm from before the next reading arrived does not swallow it.
        forgetLastAnnouncement()
        assertEquals(PeriodicGlucoseUpdates.ANNOUNCE, claimSettled(reading + 60_000L, 3.0f))
    }

    @Test fun everyLocaleTranslatesTheNewCopyWithTheSamePlaceholders() {
        val resDir = listOf("src/main/res", "Common/src/main/res").map(::File).first { it.isDirectory }
        val keys = listOf(
            "watch_forwarding_section", "watch_forwarding_title", "watch_forwarding_standard",
            "watch_forwarding_alarms_only", "watch_forwarding_every_reading", "watch_forwarding_interval",
            "watch_forwarding_interval_title", "watch_help_forwarding"
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
            assertTrue("${file.parentFile.name}: title still English",
                value(file, "watch_forwarding_title") != value(english, "watch_forwarding_title"))
            val plural = Regex("""<plurals name="watch_forwarding_minutes">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
                .find(file.readText())?.groupValues?.get(1)
            assertNotNull("${file.parentFile.name}: missing watch_forwarding_minutes", plural)
            assertTrue("${file.parentFile.name}: plural without other or count",
                Regex("""<item quantity="other">[^<]*%d""").containsMatchIn(plural!!))
        }
    }
}
