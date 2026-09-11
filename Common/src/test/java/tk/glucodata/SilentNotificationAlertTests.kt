package tk.glucodata

import java.io.File
import java.net.URLClassLoader
import javax.tools.ToolProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tk.glucodata.alerts.AlertConfig
import tk.glucodata.alerts.AlertStateTracker
import tk.glucodata.alerts.AlertType

/**
 * Compile the actual Notify entry method with inert Android rendering surfaces.
 * Loading Notify itself requires native sensor libraries during static init.
 * The production tracker is real; only UI, preferences and retry scheduling are
 * replaced. Calls that clear a retry remain observable in the harness.
 */
class SilentNotificationAlertTests {
    private var logging = false
    private val types = listOf(AlertType.LOW, AlertType.VERY_LOW, AlertType.HIGH,
        AlertType.VERY_HIGH, AlertType.PRE_LOW, AlertType.PRE_HIGH)

    @Before fun setup() {
        logging = Log.doLog
        Log.doLog = false
        types.forEach(AlertStateTracker::resetState)
    }
    @After fun cleanup() {
        types.forEach(AlertStateTracker::resetState)
        Log.doLog = logging
    }

    private fun triggered(type: AlertType): Boolean {
        val field = AlertStateTracker::class.java.getDeclaredField("lastTriggerTime")
        field.isAccessible = true
        return (field.get(AlertStateTracker) as Map<*, *>).containsKey(type)
    }

    @Test fun silentRefreshPreservesUnacknowledgedEpisodesAndRetrySessions() {
        types.forEach { type ->
            AlertStateTracker.onAlertTriggered(type)
            val harness = compiled.getConstructor().newInstance()
            repeat(3) { compiled.getMethod("refresh", Int::class.javaPrimitiveType).invoke(harness, type.id) }
            assertTrue("Silent refresh cleared ${type.name}", triggered(type))
            assertEquals("Silent refresh cancelled retry for ${type.name}", 0,
                compiled.getField("cancelledRetries").getInt(harness))
            assertEquals("The visible reading must still refresh", 3,
                compiled.getField("refreshes").getInt(harness))
        }
    }

    @Test fun silentRefreshDoesNotAcknowledgeAnEpisode() {
        AlertStateTracker.onAlertTriggered(AlertType.LOW)
        val harness = compiled.getConstructor().newInstance()
        compiled.getMethod("refresh", Int::class.javaPrimitiveType).invoke(harness, AlertType.LOW.id)
        val dismissed = AlertStateTracker::class.java.getDeclaredField("dismissedAlerts").apply { isAccessible = true }
        assertFalse((dismissed.get(AlertStateTracker) as Set<*>).contains(AlertType.LOW))
        assertTrue(AlertStateTracker.onAlertDismissed(AlertType.LOW))
        assertTrue((dismissed.get(AlertStateTracker) as Set<*>).contains(AlertType.LOW))
        AlertStateTracker.resetState(AlertType.LOW)
        assertFalse(triggered(AlertType.LOW))
    }

    @Test fun silentRefreshCannotInventAnActiveEpisode() {
        val harness = compiled.getConstructor().newInstance()
        compiled.getMethod("refresh", Int::class.javaPrimitiveType).invoke(harness, AlertType.LOW.id)
        assertFalse(triggered(AlertType.LOW))
        assertEquals(0, compiled.getField("cancelledRetries").getInt(harness))
        assertEquals(1, compiled.getField("refreshes").getInt(harness))
    }

    companion object {
        private val compiled: Class<*> by lazy {
            val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .first { File(it, "Common/src/main/java/tk/glucodata/Notify.java").exists() }
            val source = File(root, "Common/src/main/java/tk/glucodata/Notify.java").readText()
            val method = source.substring(source.indexOf("    private boolean arrowglucosealarm("),
                source.indexOf("    private void canceller()"))
            val dir = java.nio.file.Files.createTempDirectory("notify-refresh-regression").toFile()
            val sourceFile = File(dir, "NotifyRefreshHarness.java")
            sourceFile.writeText("""
                package tk.glucodata;
                import tk.glucodata.alerts.AlertType;
                import tk.glucodata.alerts.AlertConfig;
                import tk.glucodata.alerts.AlertStateTracker;
                public class NotifyRefreshHarness {
                    public int refreshes, cancelledRetries;
                    boolean doLog = false, alertwatch = false;
                    String LOG_ID = "test", GLUCOSENOTIFICATION = "glucose";
                    int FOREGROUND_GLUCOSE_NOTIFICATION_KIND = -1;
                    static class notGlucose {}
                    static class Log { static void i(String a, String b) {} static void e(String a, String b) {} }
                    static class MainActivity {
                        static MainActivity thisone = null; static String showmessage = null;
                        void replaceDialogMessage(String message) {}
                    }
                    static class AlertRepository {
                        static AlertRepository INSTANCE = new AlertRepository();
                        AlertConfig loadConfig(AlertType type) { throw new AssertionError("Silent refresh loaded alarm preferences"); }
                    }
                    static class SnoozeManager {
                        static SnoozeManager INSTANCE = new SnoozeManager(); void clearSnooze(AlertType type) {}
                    }
                    void cancelRetrySession(int kind, String reason) { cancelledRetries++; }
                    void syncRetrySession(int kind, float value, String message, notGlucose glucose, String type, AlertConfig config, boolean first) { throw new AssertionError("Silent refresh rescheduled retry"); }
                    void deliverTriggeredAlert(int kind, float value, String message, notGlucose glucose, String type) { throw new AssertionError("Silent refresh fired an alert"); }
                    void updateForegroundGlucoseNotification(int kind, float value, notGlucose glucose) { refreshes++; }
                    void arrowglucosenotification(int kind, float value, String message, notGlucose glucose, String type, boolean alarm) { refreshes++; }
                    public void refresh(int kind) { arrowglucosealarm(kind, 63f, "63", null, GLUCOSENOTIFICATION, false); }
                    $method
                }
            """.trimIndent())
            val paths = listOf(AlertStateTracker::class.java, AlertConfig::class.java,
                AlertType::class.java, kotlin.Unit::class.java).map {
                File(it.protectionDomain.codeSource.location.toURI()).path
            }.distinct().joinToString(File.pathSeparator)
            val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
            val output = java.io.ByteArrayOutputStream()
            val result = compiler.run(null, output, output, "-classpath", paths, "-d", dir.path, sourceFile.path)
            check(result == 0) { output.toString() }
            URLClassLoader(arrayOf(dir.toURI().toURL()), SilentNotificationAlertTests::class.java.classLoader)
                .loadClass("tk.glucodata.NotifyRefreshHarness")
        }
    }
}
