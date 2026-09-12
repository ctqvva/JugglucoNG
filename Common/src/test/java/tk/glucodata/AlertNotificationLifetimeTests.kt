package tk.glucodata

import java.io.File
import java.net.URLClassLoader
import javax.tools.ToolProvider
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tk.glucodata.alerts.AlertStateTracker
import tk.glucodata.alerts.AlertType

/**
 * Compile Notify's actual notification configuration with recording Android surfaces.
 * Loading Notify itself initializes native libraries. Rendering is excluded; timeout,
 * delete intent, buttons and full-screen configuration execute unchanged.
 * The deadline replay models AOSP NotificationManagerService: REASON_TIMEOUT also
 * sends the delete intent. This is a JVM regression, not Android instrumentation.
 */
class AlertNotificationLifetimeTests {
    private var logging = false

    @Before fun setup() {
        logging = Log.doLog
        Log.doLog = false
        AlertStateTracker.resetState(AlertType.LOW)
    }

    @After fun cleanup() {
        AlertStateTracker.resetState(AlertType.LOW)
        Log.doLog = logging
    }

    private fun dismissed(): Boolean {
        val field = AlertStateTracker::class.java.getDeclaredField("dismissedAlerts")
        field.isAccessible = true
        return AlertType.LOW in field.get(AlertStateTracker) as Set<*>
    }

    @Test fun unattendedAlertDoesNotAcknowledgeAtTheOldNotificationDeadline() {
        AlertStateTracker.onAlertTriggered(AlertType.LOW)
        val notification = configure(36, false, "NOTIFICATION_ONLY", AlertType.LOW.id, null)
        for (elapsedMs in listOf(45_000L, 48_000L, 60_000L, 600_000L)) {
            // Android's notification timeout is independent of the sound duration.
            if (timeout(notification) in 1..elapsedMs) {
                assertEquals(4, compiled.getField("deleteRequest").getInt(notification))
                assertEquals("tk.glucodata.ACTION_DISMISS", action(notification, 4))
                AlertStateTracker.onAlertDismissed(AlertType.LOW)
            }
            assertFalse("Unattended alert acknowledged at ${elapsedMs}ms", dismissed())
        }
        assertEquals(4, compiled.getField("deleteRequest").getInt(notification))
        assertEquals("tk.glucodata.ACTION_DISMISS", action(notification, 4))
        AlertStateTracker.onAlertDismissed(AlertType.LOW)
        assertTrue("Explicit acknowledgement must still work", dismissed())
    }

    @Test fun notificationAndAlarmFallbackSurfacesDoNotScheduleAutomaticRemoval() {
        for (sdk in listOf(26, 30, 36)) {
            for (mode in listOf("NOTIFICATION_ONLY", "BOTH", "SYSTEM_ALARM")) {
                for (snooze in listOf(false, true)) {
                    for (customId in listOf(null, "custom-low")) {
                        val notification = configure(sdk, snooze, mode, AlertType.LOW.id, customId)
                        assertEquals("sdk=$sdk mode=$mode snooze=$snooze custom=$customId", 0L, timeout(notification))
                        assertEquals(mode != "NOTIFICATION_ONLY", compiled.getField("fullScreen").getBoolean(notification))
                    }
                }
            }
        }
    }

    @Test fun explicitButtonsAndConfiguredSwipeKeepTheirActionsAndAlertIdentity() {
        for (snooze in listOf(false, true)) {
            for (kind in listOf(AlertType.LOW.id, AlertType.VERY_LOW.id, AlertType.HIGH.id)) {
                for (customId in listOf(null, "custom-low")) {
                    val notification = configure(36, snooze, "BOTH", kind, customId)
                    assertEquals(4, compiled.getField("deleteRequest").getInt(notification))
                    assertEquals("tk.glucodata.ACTION_SNOOZE", action(notification, 1))
                    assertEquals("tk.glucodata.ACTION_DISMISS", action(notification, 2))
                    assertEquals(if (snooze) action(notification, 1) else action(notification, 2), action(notification, 4))
                    for (request in listOf(1, 2, 4)) {
                        assertEquals(kind, compiled.getMethod("extra", Int::class.javaPrimitiveType, String::class.java)
                            .invoke(notification, request, "alert_type_id"))
                        assertEquals(customId, compiled.getMethod("extra", Int::class.javaPrimitiveType, String::class.java)
                            .invoke(notification, request, "custom_alert_id"))
                    }
                }
            }
        }
    }

    private fun configure(sdk: Int, snooze: Boolean, mode: String, kind: Int, customId: String?): Any =
        compiled.getConstructor().newInstance().also {
            compiled.getMethod("configure", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                String::class.java, Int::class.javaPrimitiveType, String::class.java)
                .invoke(it, sdk, snooze, mode, kind, customId)
        }

    private fun timeout(notification: Any) = compiled.getField("timeoutMs").getLong(notification)
    private fun action(notification: Any, request: Int) =
        compiled.getMethod("action", Int::class.javaPrimitiveType).invoke(notification, request)

    companion object {
        private var scratch: File? = null
        private var loader: URLClassLoader? = null

        @JvmStatic @AfterClass fun removeHarness() {
            loader?.close()
            scratch?.deleteRecursively()
        }

        private val compiled: Class<*> by lazy {
            val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .first { File(it, "Common/src/main/java/tk/glucodata/Notify.java").exists() }
            val source = File(root, "Common/src/main/java/tk/glucodata/Notify.java").readText()
            val start = source.indexOf("                var GluNotBuilder = mkbuilderintent(type, intent, false);")
            check(start >= 0)
            val end = source.indexOf("                // --- RICH UI START", start)
            check(end > start)
            val configuration = source.substring(start, end)
            val dir = java.nio.file.Files.createTempDirectory("alert-notification-lifetime").toFile()
            scratch = dir
            val receiver = File(dir, "AlarmActionReceiver.java").apply {
                writeText("""
                    package tk.glucodata.receivers;
                    public class AlarmActionReceiver {
                        public static final String ACTION_SNOOZE = "tk.glucodata.ACTION_SNOOZE";
                        public static final String ACTION_DISMISS = "tk.glucodata.ACTION_DISMISS";
                    }
                """.trimIndent())
            }
            val javaFile = File(dir, "AlertNotificationHarness.java").apply {
                writeText("""
                    package tk.glucodata;
                    import java.util.*;
                    public class AlertNotificationHarness {
                        public long timeoutMs;
                        public boolean fullScreen;
                        public int deleteRequest = -1;
                        Map<Integer, Intent> broadcasts = new HashMap<>();
                        static boolean snooze;
                        boolean doLog = false;
                        String LOG_ID = "test", EXTRA_CUSTOM_ALERT_ID = "custom_alert_id";
                        int penmutable = 0;
                        static class Build {
                            static class VERSION { static int SDK_INT; }
                            static class VERSION_CODES { static final int O = 26, LOLLIPOP = 21; }
                        }
                        static class Applic { static Object app = new Object(); }
                        static class Log { static void i(String a, String b) {} static void e(String a, String b) {} }
                        // Upstream uses the global swipe preference; local integration
                        // uses the per-alert default. Both supply the same two actions.
                        enum AlertNotificationDismissAction { SNOOZE, DISMISS }
                        enum AlertDefaultAction { SNOOZE, DISMISS }
                        static class AlertRepository {
                            static AlertRepository INSTANCE = new AlertRepository();
                            AlertNotificationDismissAction loadNotificationDismissAction() {
                                return snooze ? AlertNotificationDismissAction.SNOOZE : AlertNotificationDismissAction.DISMISS;
                            }
                            AlertDefaultAction resolveDefaultAction(int kind, String customId) {
                                return snooze ? AlertDefaultAction.SNOOZE : AlertDefaultAction.DISMISS;
                            }
                        }
                        static class Intent {
                            String action;
                            Map<String, Object> extras = new HashMap<>();
                            Intent(Object context, Class<?> receiver) {}
                            void setAction(String value) { action = value; }
                            void putExtra(String key, Object value) { extras.put(key, value); }
                        }
                        static class PendingIntent {
                            static final int FLAG_UPDATE_CURRENT = 1;
                            static AlertNotificationHarness owner;
                            int request = -1;
                            static PendingIntent getBroadcast(Object app, int request, Intent intent, int flags) {
                                owner.broadcasts.put(request, intent);
                                PendingIntent pending = new PendingIntent();
                                pending.request = request;
                                return pending;
                            }
                        }
                        class Notification {
                            static final int PRIORITY_HIGH = 1;
                            static final String CATEGORY_ALARM = "alarm";
                            class Builder {
                                void setDeleteIntent(PendingIntent value) { deleteRequest = value.request; }
                                void setTimeoutAfter(long value) { timeoutMs = value; }
                                void setPriority(int value) {}
                                void setCategory(String value) {}
                                void setFullScreenIntent(PendingIntent value, boolean high) { fullScreen = true; }
                            }
                        }
                        static class notGlucose { float rate; int sensorgen2; }
                        Notification.Builder mkbuilderintent(String type, PendingIntent intent, boolean grouped) {
                            return new Notification().new Builder();
                        }
                        void setIcon(Notification.Builder builder, float value, int gen) {}
                        PendingIntent mkAlarmPendingIntent(String value, String message, float rate, int kind,
                                String custom, String mode) { return new PendingIntent(); }
                        public String action(int request) { return broadcasts.get(request).action; }
                        public Object extra(int request, String key) { return broadcasts.get(request).extras.get(key); }
                        public void configure(int sdk, boolean swipeSnoozes, String currentDeliveryMode,
                                int alertTypeId, String customAlertId) {
                            Build.VERSION.SDK_INT = sdk;
                            snooze = swipeSnoozes;
                            PendingIntent.owner = this;
                            String type = "LOW", message = "Low", alarmGlucoseValue = "63";
                            PendingIntent intent = new PendingIntent();
                            float glvalue = 63;
                            notGlucose glucose = new notGlucose();
                            $configuration
                        }
                    }
                """.trimIndent())
            }
            val paths = File(AlertDeliveryPolicy::class.java.protectionDomain.codeSource.location.toURI()).path
            val output = java.io.ByteArrayOutputStream()
            val result = checkNotNull(ToolProvider.getSystemJavaCompiler()).run(null, output, output,
                "-classpath", paths, "-d", dir.path, receiver.path, javaFile.path)
            check(result == 0) { output.toString() }
            URLClassLoader(arrayOf(dir.toURI().toURL()), AlertNotificationLifetimeTests::class.java.classLoader)
                .also { loader = it }.loadClass("tk.glucodata.AlertNotificationHarness")
        }
    }
}
