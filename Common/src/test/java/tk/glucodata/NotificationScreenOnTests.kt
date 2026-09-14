package tk.glucodata

import java.io.File
import java.net.URLClassLoader
import javax.tools.ToolProvider
import org.junit.Assert.*
import org.junit.Test

/** Exercise Notify's actual screen-on receiver without loading its native static initializer. */
class NotificationScreenOnTests {
    private fun harness() = compiled.getConstructor().newInstance()
    private fun Any.set(name: String, value: Boolean) = compiled.getField(name).setBoolean(this, value)
    private fun Any.count(name: String) = compiled.getField(name).getInt(this)
    private fun Any.render(enabled: Boolean = true): Boolean =
        compiled.getMethod("render", Boolean::class.javaPrimitiveType).invoke(this, enabled) as Boolean
    private fun Any.wake() = compiled.getMethod("wake").invoke(this)

    @Test fun screenOffDefersChartUntilWakeWithoutAnotherReading() {
        val h = harness()
        repeat(3) { assertFalse(h.render()) }
        h.set("interactive", true)
        h.wake()
        assertEquals(1, h.count("livePosts"))
        h.wake()
        assertEquals("One refresh must consume the deferred work", 1, h.count("livePosts"))
    }

    @Test fun enabledChartsRenderNormallyWhileInteractive() {
        val h = harness()
        h.set("interactive", true)
        assertTrue(h.render())
        h.wake()
        assertEquals(0, h.count("livePosts"))
    }

    @Test fun missingPreferenceKeepsExistingScreenOffRendering() {
        val h = harness()
        h.set("settingPresent", false)
        assertTrue(h.render())
        h.set("interactive", true)
        h.wake()
        assertEquals(0, h.count("livePosts"))
    }

    @Test fun optOutKeepsScreenOffRenderingAndClearsPendingWake() {
        val h = harness()
        assertFalse(h.render())
        h.set("pauseEnabled", false)
        assertTrue(h.render())
        h.set("interactive", true)
        h.wake()
        assertEquals(0, h.count("livePosts"))
    }

    @Test fun phoneOptimizationDoesNotChangeWearRendering() {
        val h = harness()
        h.set("isWearable", true)
        assertTrue(h.render())
    }

    @Test fun disabledChartsDoNotScheduleScreenOnWork() {
        val h = harness()
        assertFalse(h.render(false))
        h.set("interactive", true)
        h.wake()
        assertEquals(0, h.count("livePosts"))
        assertEquals(0, h.count("startupPosts"))
    }

    @Test fun screenOnAfterScreenHasAlreadyTurnedOffDoesNoWork() {
        val h = harness()
        assertFalse(h.render())
        h.wake()
        assertEquals(0, h.count("livePosts"))
        h.set("interactive", true)
        h.wake()
        assertEquals(1, h.count("livePosts"))
    }

    @Test fun missingCurrentReadingRefreshesStartupChart() {
        val h = harness()
        h.set("hasCurrent", false)
        assertFalse(h.render())
        h.set("interactive", true)
        h.wake()
        assertEquals(0, h.count("livePosts"))
        assertEquals(1, h.count("startupPosts"))
    }

    @Test fun noForegroundNotificationMeansNoWakeRefresh() {
        val h = harness()
        assertFalse(h.render())
        h.set("keepNotification", false)
        h.set("interactive", true)
        h.wake()
        assertEquals(0, h.count("livePosts"))
        assertEquals(0, h.count("startupPosts"))
    }

    companion object {
        private val compiled: Class<*> by lazy {
            val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
                .first { File(it, "Common/src/main/java/tk/glucodata/Notify.java").exists() }
            val source = File(root, "Common/src/main/java/tk/glucodata/Notify.java").readText()
            val methods = source.substring(source.indexOf("    private boolean isScreenOffChartPauseEnabled()"),
                source.indexOf("    private final Runnable glucoseRefreshRunnable"))
            val dir = java.nio.file.Files.createTempDirectory("notify-screen-on-test").toFile()
            val file = File(dir, "NotifyScreenOnHarness.java")
            file.writeText("""
                import static java.lang.String.format;
                public class NotifyScreenOnHarness {
                    public boolean interactive, isWearable, pauseEnabled = true, settingPresent = true,
                        hasCurrent = true, keepNotification = true;
                    public int livePosts, startupPosts;
                    static String LOG_ID = "test", glucoseformat = "%.0f";
                    static java.util.Locale usedlocale = java.util.Locale.ROOT;
                    static int FOREGROUND_GLUCOSE_NOTIFICATION_KIND = -1;
                    static class Context { static int MODE_PRIVATE = 0; }
                    class Preferences {
                        boolean getBoolean(String key, boolean defaultValue) {
                            if (!key.equals("notification_chart_pause_screen_off")) throw new AssertionError(key);
                            return settingPresent ? pauseEnabled : defaultValue;
                        }
                    }
                    class App {
                        Preferences getSharedPreferences(String name, int mode) { return new Preferences(); }
                    }
                    class AppAccess { App app = new App(); }
                    AppAccess Applic = new AppAccess();
                    static class Intent {
                        static String ACTION_SCREEN_ON = "screen-on";
                        String getAction() { return ACTION_SCREEN_ON; }
                    }
                    abstract static class BroadcastReceiver {
                        public abstract void onReceive(Context context, Intent intent);
                    }
                    static class CurrentDisplaySource {
                        static class Snapshot { float getPrimaryValue() { return 100f; } }
                    }
                    static class Log { static void stack(String a, String b, Throwable t) {
                        throw new AssertionError(t);
                    } }
                    boolean isScreenInteractive() { return interactive; }
                    boolean shouldKeepForegroundGlucoseNotification() { return keepNotification; }
                    CurrentDisplaySource.Snapshot resolveNotificationCurrentSnapshot() {
                        return hasCurrent ? new CurrentDisplaySource.Snapshot() : null;
                    }
                    Object toLegacyGlucose(CurrentDisplaySource.Snapshot current) { return current; }
                    void postForegroundGlucoseNotification(int kind, float value, String text, Object glucose) {
                        livePosts++;
                        canRenderNotificationCharts(true);
                    }
                    Object getforgroundnotification() { canRenderNotificationCharts(true); return new Object(); }
                    void fornotify(Object notification) { startupPosts++; }
                    public boolean render(boolean enabled) { return canRenderNotificationCharts(enabled); }
                    public void wake() { screenOnReceiver.onReceive(new Context(), new Intent()); }
                    $methods
                }
            """.trimIndent())
            val compiler = ToolProvider.getSystemJavaCompiler()
            check(compiler != null) { "A JDK is required for the production receiver harness" }
            check(compiler.run(null, null, null, "-d", dir.path, file.path) == 0)
            URLClassLoader(arrayOf(dir.toURI().toURL())).loadClass("NotifyScreenOnHarness")
        }
    }
}
