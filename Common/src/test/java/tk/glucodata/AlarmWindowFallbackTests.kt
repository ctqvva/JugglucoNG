package tk.glucodata

import java.io.File
import java.net.URLClassLoader
import javax.tools.ToolProvider
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Alarm mode skips the alert notification when the AlarmActivity start "succeeds", but
 * from the background Android 10+ and OEM pop-up rules drop that start without an
 * error: the alarm rang with no Snooze/Stop anywhere (Honor, Android 14, 2026-10-21
 * trace). Compiles Notify's real check against a recorded main handler.
 */
class AlarmWindowFallbackTests {
    private lateinit var posted: MutableList<String>

    @Before fun reset() {
        compiled.getField("alarmUiVisible").setBoolean(null, false)
        @Suppress("UNCHECKED_CAST")
        posted = compiled.getField("posted").get(null) as MutableList<String>
        posted.clear()
        (compiled.getField("delayed").get(null) as MutableList<*>).clear()
    }

    private fun schedule(name: String) = compiled.getMethod("schedule", String::class.java).invoke(null, name)
    private fun runDelayed() = compiled.getMethod("runDelayed").invoke(null)
    private fun call(method: String) =
        compiled.getDeclaredMethod(method).apply { isAccessible = true }.invoke(null)

    @Test fun blockedWindowFallsBackToTheAlarmNotification() {
        schedule("low")
        runDelayed()
        assertEquals(listOf("low"), posted)
    }

    @Test fun shownWindowKeepsTheNotificationOff() {
        schedule("low")
        compiled.getMethod("setAlarmUiVisible", Boolean::class.javaPrimitiveType).invoke(null, true)
        runDelayed()
        assertEquals(emptyList<String>(), posted)
    }

    @Test fun acknowledgementBeforeTheCheckPostsNothing() {
        schedule("low")
        call("cancelAlertNotification")
        runDelayed()
        assertEquals(emptyList<String>(), posted)
    }

    @Test fun newerNotificationOnlyAlertIsNotReplacedByAnOlderFallback() {
        schedule("low")
        // A custom notification-only alert from the same reading posts its own
        // notification; the low's blocked window must not overwrite it.
        call("supersedeAlarmWindowFallback")
        runDelayed()
        assertEquals(emptyList<String>(), posted)
    }

    @Test fun onlyTheNewestAlertFallsBack() {
        schedule("low")
        schedule("very low")
        runDelayed()
        assertEquals(listOf("very low"), posted)
    }

    companion object {
        private var loader: URLClassLoader? = null
        private var scratch: File? = null

        @JvmStatic @AfterClass fun cleanup() { loader?.close(); scratch?.deleteRecursively() }

        private val compiled: Class<*> by lazy {
            val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .first { File(it, "Common/src/main/java/tk/glucodata/Notify.java").exists() }
            val source = File(root, "Common/src/main/java/tk/glucodata/Notify.java").readText()
            fun block(signature: String): String {
                val start = source.indexOf(signature)
                check(start >= 0) { signature }
                var depth = 1
                var end = source.indexOf('{', start) + 1
                while (depth > 0) {
                    when (source[end++]) { '{' -> depth++; '}' -> depth-- }
                }
                return source.substring(start, end)
            }
            fun field(declaration: String): String {
                val start = source.indexOf(declaration)
                check(start >= 0) { declaration }
                return source.substring(start, source.indexOf(';', start) + 1)
            }
            val code = listOf(
                "private static final long ALARM_WINDOW_CONFIRM_MS",
                "private static volatile int alarmWindowCheckGeneration"
            ).joinToString("\n", transform = ::field) + "\n" + listOf(
                "public static void setAlarmUiVisible(boolean visible)",
                "private static void supersedeAlarmWindowFallback()",
                "private static void postAlarmNotificationUnlessWindowShows(Runnable postNotification)",
                "public static void cancelAlertNotification()"
            ).joinToString("\n", transform = ::block)
            val dir = java.nio.file.Files.createTempDirectory("alarm-window").toFile().also { scratch = it }
            val javaFile = File(dir, "AlarmWindowHarness.java").apply { writeText("""
                import java.util.*;
                public class AlarmWindowHarness {
                    public static List<String> posted = new ArrayList<>();
                    public static List<Runnable> delayed = new ArrayList<>();
                    public static boolean alarmUiVisible;
                    static int presentedStandardAlertKind = -1;
                    static int glucosealarmid = 81432;
                    static boolean doLog = false;
                    static String LOG_ID = "test";
                    static AlarmWindowHarness onenot;
                    NotificationManager notificationManager;
                    static class NotificationManager { void cancel(int id) {} }
                    static class Log { static void i(String tag, String message) {} }
                    static class Handler {
                        void postDelayed(Runnable task, long delayMs) { delayed.add(task); }
                    }
                    static final Handler alarmWindowCheckHandler = new Handler();
                    public static void schedule(String name) {
                        postAlarmNotificationUnlessWindowShows(() -> posted.add(name));
                    }
                    public static void runDelayed() {
                        List<Runnable> due = new ArrayList<>(delayed);
                        delayed.clear();
                        for (Runnable task : due) task.run();
                    }
                    $code
                }
            """.trimIndent()) }
            val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
            check(compiler.run(null, null, null, "-d", dir.path, javaFile.path) == 0)
            URLClassLoader(arrayOf(dir.toURI().toURL()), null).also { loader = it }.loadClass("AlarmWindowHarness")
        }
    }
}
