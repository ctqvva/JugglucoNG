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
                "private static void fireRetrySession(int expectedKind, long expectedGeneration)",
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
                    static List<Runnable> main = new ArrayList<>();
                    static class Future { void cancel(boolean interrupt) {} }
                    static class notGlucose { long time; String value; float rate; }
                    static class Log { static void i(String tag, String text) {} }
                    static class android { static class os {
                        static class Looper { static Object getMainLooper() { return null; } }
                        static class Handler {
                            Handler(Object looper) {}
                            void post(Runnable task) { main.add(task); }
                        }
                    } }
                    static class AlertType {
                        static AlertType Companion = new AlertType();
                        AlertType fromId(int kind) { return this; }
                    }
                    static class AlertConfig {
                        boolean getEnabled() { return true; }
                        boolean getRetryEnabled() { return true; }
                        boolean isActiveNow() { return true; }
                        int getRetryCount() { return 0; }
                    }
                    static class AlertRepository {
                        static AlertRepository INSTANCE = new AlertRepository();
                        AlertConfig loadConfig(AlertType type) { return new AlertConfig(); }
                    }
                    static class SnoozeManager {
                        static SnoozeManager INSTANCE = new SnoozeManager();
                        boolean isSnoozed(AlertType type) { return false; }
                    }
                    static void cancelQueuedAlarmActivityLaunch(int kind, String id, String reason) {}
                    static String format(Object locale, String format, float value) { return "" + value; }
                    static notGlucose copyGlucoseSnapshot(notGlucose original, float value) { return new notGlucose(); }
                    void deliverTriggeredAlert(int kind, float glucose, String text, notGlucose snapshot, String type) {
                        delivered++; value = glucose; message = text;
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
                    public void drain() { for (Runnable task : List.copyOf(main)) task.run(); main.clear(); }
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
