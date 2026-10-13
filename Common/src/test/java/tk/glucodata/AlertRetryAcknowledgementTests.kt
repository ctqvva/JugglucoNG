package tk.glucodata

import java.io.File
import java.net.URLClassLoader
import javax.tools.ToolProvider
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Test

/** Execute Notify's retry callbacks unchanged with a controllable main-thread queue. */
class AlertRetryAcknowledgementTests {
    @Test fun dismissalAfterTimerButBeforeMainDeliveryDropsTheRetry() {
        val h = harness()
        invoke(h, "timer")
        invoke(h, "cancel")
        invoke(h, "drain")
        assertEquals(0, count(h))
    }

    @Test fun replacingAnEpisodeWithTheSameKindDoesNotReviveItsQueuedRetry() {
        val h = harness()
        invoke(h, "timer")
        invoke(h, "cancel")
        invoke(h, "start")
        invoke(h, "drain")
        assertEquals(0, count(h))
        invoke(h, "timer")
        invoke(h, "drain")
        assertEquals(1, count(h))
    }

    @Test fun anOldTimerCannotClearOrDeliverTheReplacementSchedule() {
        val h = harness()
        invoke(h, "cancel")
        invoke(h, "start")
        invoke(h, "oldTimer")
        invoke(h, "drain")
        assertEquals(0, count(h))
        assertTrue(compiled.getField("scheduled").getBoolean(h))
    }

    @Test fun currentRetryDeliversOnceAndUsesTheLatestReading() {
        val h = harness()
        invoke(h, "refresh")
        invoke(h, "timer")
        invoke(h, "drain")
        assertEquals(1, count(h))
        assertEquals(250f, compiled.getField("value").getFloat(h))
        assertEquals("High 250", compiled.getField("message").get(h))
    }

    @Test fun aBackgroundLowCannotReplaceOrSoundDuringAHighRetryDelivery() {
        val h = harness()
        invoke(h, "interleaveLow")
        invoke(h, "timer")
        invoke(h, "drain")
        assertTrue(compiled.getField("ownerStayedHigh").getBoolean(h))
        assertEquals(listOf("start:1", "end:1", "start:0", "end:0"), compiled.getField("deliveries").get(h))
        assertEquals(60f, compiled.getField("value").getFloat(h))
    }

    @Test fun realLossDeliveryTracksItsKindForBothLocalAndRemoteActions() {
        val h = harness()
        invoke(h, "loss")
        invoke(h, "drain")
        assertEquals(4, compiled.getField("soundingKind").getInt(h))
        assertTrue(compiled.getMethod("lossCanBeAcknowledged").invoke(h) as Boolean)
    }

    private fun harness() = compiled.getConstructor().newInstance().also { invoke(it, "start") }
    private fun invoke(h: Any, method: String) { compiled.getMethod(method).invoke(h) }
    private fun count(h: Any) = compiled.getField("delivered").getInt(h)

    companion object {
        private var scratch: File? = null
        private var loader: URLClassLoader? = null
        @JvmStatic @AfterClass fun cleanup() { loader?.close(); scratch?.deleteRecursively() }

        private val compiled: Class<*> by lazy {
            val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .first { File(it, "Common/src/main/java/tk/glucodata/Notify.java").exists() }
            val source = File(root, "Common/src/main/java/tk/glucodata/Notify.java").readText()
            fun block(signature: String): String {
                val start = source.indexOf(signature)
                check(start >= 0) { signature }
                val open = source.indexOf('{', start)
                var depth = 1
                var end = open + 1
                while (depth > 0) {
                    when (source[end++]) { '{' -> depth++; '}' -> depth-- }
                }
                return source.substring(start, end)
            }
            val code = listOf(
                "private static final class AlertRetrySession",
                "private static void cancelRetryScheduleLocked()",
                "private static void cancelRetrySessionLocked(String reason)",
                "private static void runOnAlertThread(Runnable task)",
                "private static void fireRetrySession(int expectedKind, long expectedGeneration)",
                "private static void deliverRetrySession(int expectedKind, long expectedGeneration)",
                "private void deliverInitialAlert(int kind, float value, String message, notGlucose glucose,",
                "private void syncRetrySession(int kind, float glvalue, String message, notGlucose strglucose, String type,",
                "private void lossofsignalalarm(int kind, int draw, String message, String type, boolean alarm)",
                "private void soundalarm(int kind, int draw, String message, String type, boolean alarm)",
                "public static boolean isPresentedStandardAlert(int kind)",
                "public static boolean isCurrentStandardAlert(int kind)",
                "public static void refreshRetryReading(int kind, float glucoseValue, float rate, String message, long readingTimeMs)"
            ).joinToString("\n", transform = ::block)
            val dir = java.nio.file.Files.createTempDirectory("alert-retry-ack").toFile().also { scratch = it }
            val javaFile = File(dir, "RetryHarness.java").apply { writeText("""
                import java.util.*;
                public class RetryHarness {
                    public int delivered;
                    public float value;
                    public String message;
                    public boolean scheduled;
                    static RetryHarness onenot;
                    static final Object retrySessionLock = new Object();
                    static AlertRetrySession activeRetrySession;
                    static long retryGeneration;
                    static Future retrySessionSchedule;
                    static boolean doLog = false;
                    static String LOG_ID = "test", pureglucoseformat = "test";
                    static Object usedlocale;
                    static java.util.Queue<Runnable> main = new java.util.concurrent.ConcurrentLinkedQueue<>();
                    static Thread mainThread;
                    public java.util.List<String> deliveries = new ArrayList<>();
                    public boolean interleave;
                    public boolean ownerStayedHigh;
                    static int presentedStandardAlertKind = -1;
                    public int soundingKind = -1;
                    boolean alertwatch = false;
                    static String GLUCOSENOTIFICATION = "glucose";
                    static class Future { void cancel(boolean interrupt) {} }
                    static class notGlucose { long time; String value; float rate; }
                    static class Log { static void i(String tag, String text) {} static void d(String tag, String text) {} }
                    static class android { static class os {
                        static class Looper {
                            static final Object MAIN = new Object();
                            static Object getMainLooper() { return MAIN; }
                            static Object myLooper() { return Thread.currentThread() == mainThread ? MAIN : null; }
                        }
                        static class Handler {
                            Handler(Object looper) {}
                            void post(Runnable task) { main.add(task); }
                        }
                    } }
                    static class AlertType {
                        static AlertType Companion = new AlertType();
                        AlertType fromId(int kind) { return this; }
                        boolean isLegacyOnlyId(int kind) { return false; }
                    }
                    static class AlertConfig {
                        boolean getEnabled() { return true; }
                        boolean getRetryEnabled() { return true; }
                        boolean isActiveNow() { return true; }
                        int getRetryCount() { return 0; }
                    }
                    static class WearAlertSync { static boolean isSuppressed(int kind) { return false; } }
                    static class AlertStateTracker {
                        static AlertStateTracker INSTANCE = new AlertStateTracker();
                        boolean isManualTestActive(AlertType type) { return false; }
                        boolean isDismissed(AlertType type) { return false; }
                        boolean isEpisodeActive(AlertType type) { return true; }
                    }
                    static boolean isSameRetryFamily(int a, int b) { return a == b; }
                    static void cancelRetrySession(int kind, String reason) { cancelRetrySessionLocked(reason); }
                    static class AlertRepository {
                        static AlertRepository INSTANCE = new AlertRepository();
                        AlertConfig loadConfig(AlertType type) { return new AlertConfig(); }
                    }
                    static class SnoozeManager {
                        static SnoozeManager INSTANCE = new SnoozeManager();
                        boolean isSnoozed(AlertType type) { return false; }
                    }
                    static class AlertDeliveryPolicy {
                        static String SYSTEM_ALARM = "SYSTEM_ALARM", BOTH = "BOTH";
                    }
                    static class MainActivity {
                        static MainActivity thisone;
                        static String showmessage;
                        void replaceDialogMessage(String text) {}
                    }
                    String getDeliveryMode(int kind) { return "NOTIFICATION_ONLY"; }
                    String normalizeDeliveryMode(String mode) { return mode; }
                    void launchOrQueueAlarmActivityResult(String value, String message, float rate, int kind, String custom, String mode) {}
                    void lossofsensornotification(int draw, String message, String type, boolean alarm) {}
                    void mksound(int kind) { soundingKind = kind; }
                    void placelargenotification(int draw, String message, String type, boolean once) {}
                    public void loss() { lossofsignalalarm(4, 0, "No reading", "LOSS", true); }
                    public boolean lossCanBeAcknowledged() { return isPresentedStandardAlert(4) && isCurrentStandardAlert(4); }
                    static void cancelQueuedAlarmActivityLaunch(int kind, String id, String reason) {}
                    static String format(Object locale, String format, float value) { return "" + value; }
                    static notGlucose copyGlucoseSnapshot(notGlucose original, float value) { return new notGlucose(); }
                    void deliverTriggeredAlert(int kind, float glucose, String text, notGlucose snapshot, String type) {
                        deliveries.add("start:" + kind);
                        if (interleave && kind == 1) {
                            interleave = false;
                            Thread background = new Thread(() -> deliverInitialAlert(0, 60, "Low 60",
                                    new notGlucose(), "LOW", new AlertConfig(), true));
                            background.start();
                            try { background.join(2000); } catch (InterruptedException failure) { throw new AssertionError(failure); }
                            if (background.isAlive()) throw new AssertionError("background runtime blocked");
                            ownerStayedHigh = activeRetrySession.kind == 1;
                        }
                        delivered++; value = glucose; message = text;
                        deliveries.add("end:" + kind);
                    }
                    public void start() {
                        onenot = this;
                        activeRetrySession = new AlertRetrySession(1, 180, new notGlucose(), "High 180", "HIGH", 1);
                        retrySessionSchedule = new Future(); scheduled = true;
                    }
                    public void cancel() { cancelRetrySessionLocked("dismiss"); }
                    public void timer() { fireRetrySession(1, retryGeneration); }
                    public void oldTimer() {
                        Future original = retrySessionSchedule;
                        fireRetrySession(1, retryGeneration - 1);
                        scheduled = retrySessionSchedule == original;
                    }
                    public void refresh() { refreshRetryReading(1, 250, 2, "High 250", 2L); }
                    public void drain() {
                        mainThread = Thread.currentThread();
                        try { Runnable task; while ((task = main.poll()) != null) task.run(); }
                        finally { mainThread = null; }
                    }
                    public void interleaveLow() { interleave = true; }
                    $code
                }
            """.trimIndent()) }
            val output = java.io.ByteArrayOutputStream()
            val result = checkNotNull(ToolProvider.getSystemJavaCompiler()).run(null, output, output,
                "-d", dir.path, javaFile.path)
            check(result == 0) { output.toString() }
            URLClassLoader(arrayOf(dir.toURI().toURL())).also { loader = it }.loadClass("RetryHarness")
        }
    }
}
