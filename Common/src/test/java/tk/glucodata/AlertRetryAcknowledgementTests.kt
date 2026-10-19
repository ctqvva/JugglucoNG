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

    @Test fun fallingReadingCancelsAnInitialHighBeforeAnyRetrySessionExists() {
        val h = harness()
        invoke(h, "startEmpty")
        invoke(h, "queueHigh")
        invoke(h, "highFalling")
        invoke(h, "drain")
        assertEquals(0, count(h))
        assertEquals(0, compiled.getMethod("firings").invoke(h))
        // The fall ends without clearing the threshold episode: a fresh request
        // can still become the first genuine firing.
        invoke(h, "queueHigh")
        invoke(h, "drain")
        assertEquals(1, count(h))
        assertEquals(1, compiled.getMethod("firings").invoke(h))
    }

    @Test fun cancellingAnotherKindDoesNotDiscardTheQueuedHigh() {
        val h = harness()
        invoke(h, "startEmpty")
        invoke(h, "queueHigh")
        invoke(h, "cancelLow")
        invoke(h, "drain")
        assertEquals(1, count(h))
    }

    @Test fun cancellingAllAlsoDropsInitialDeliveriesWithoutSessions() {
        val h = harness()
        invoke(h, "startEmpty")
        invoke(h, "queueHigh")
        invoke(h, "cancelAll")
        invoke(h, "drain")
        assertEquals(0, count(h))
    }

    @Test fun realLossTestTriggerActivatesBeforeSnoozeAndSharedQuietGates() {
        val h = harness()
        invoke(h, "startEmpty")
        invoke(h, "quietLoss")
        invoke(h, "loss")
        invoke(h, "drain")
        assertEquals(-1, compiled.getField("soundingKind").getInt(h))
        invoke(h, "testLoss")
        invoke(h, "drain")
        assertEquals(4, compiled.getField("soundingKind").getInt(h))
        assertEquals(0, compiled.getMethod("firings").invoke(h))
    }

    @Test fun bothVariantsKeepANewOutagePendingThroughALossSnooze() {
        for (variant in listOf("mobile", "wear")) {
            val h = harness()
            invoke(h, "startEmpty")
            invoke(h, "${variant}Tick")
            invoke(h, "drain")
            assertTrue(compiled.getField("saidloss").getBoolean(h))
            assertEquals(1, compiled.getField("lossSounds").getInt(h))
            invoke(h, "quietLoss")
            invoke(h, "newReading")
            repeat(2) {
                invoke(h, "${variant}Tick")
                invoke(h, "drain")
                assertFalse("$variant consumed a suppressed outage", compiled.getField("saidloss").getBoolean(h))
                assertEquals(1, compiled.getField("lossSounds").getInt(h))
            }
            invoke(h, "allowLoss") // Expiry without another reading or reset.
            invoke(h, "${variant}Tick")
            invoke(h, "drain")
            assertTrue(compiled.getField("saidloss").getBoolean(h))
            assertEquals(2, compiled.getField("lossSounds").getInt(h))
            invoke(h, "${variant}Tick")
            invoke(h, "drain")
            assertEquals(2, compiled.getField("lossSounds").getInt(h))
        }
    }

    @Test fun aRealOutageCannotBorrowTheActiveLossTestsQuietBypass() {
        val h = harness()
        invoke(h, "startEmpty")
        invoke(h, "quietLoss")
        invoke(h, "testLoss")
        invoke(h, "drain")
        assertEquals(1, compiled.getField("lossSounds").getInt(h))
        invoke(h, "newReading")
        invoke(h, "loss")
        invoke(h, "drain")
        assertEquals(1, compiled.getField("lossSounds").getInt(h))
        assertEquals(-1, compiled.getField("soundingKind").getInt(h))
        invoke(h, "allowLoss")
        invoke(h, "loss")
        invoke(h, "drain")
        assertEquals(2, compiled.getField("lossSounds").getInt(h))
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
            fun blockFrom(source: String, signature: String): String {
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
            fun block(signature: String) = blockFrom(source, signature)
            val handlers = listOf("mobile" to "Mobile", "wear" to "Wear").joinToString("\n") { (variant, name) ->
                blockFrom(File(root, "Common/src/$variant/java/tk/glucodata/${name}GlucoseAlarms.java").readText(),
                    "public    void handlealarm()")
                    .replace("handlealarm()", "${variant}Handle()")
                    .replace("tk.glucodata.glucosecomplication.WearComplicationValue", "WearComplicationValue")
            } + "\n" + blockFrom(File(root, "Common/src/main/java/tk/glucodata/SuperGlucoseAlarms.java").readText(),
                "public void setagealarm(final long numsec,long showtime)")
            val code = listOf(
                "private static final class AlertRetrySession",
                "private static void cancelRetryScheduleLocked()",
                "private static void cancelRetrySessionLocked(String reason)",
                "public static void cancelRetrySession(int kind, String reason)",
                "public static void cancelCurrentRetrySession(String reason)",
                "public static void testTrigger(int kind)",
                "private static void runOnAlertThread(Runnable task)",
                "private static void fireRetrySession(int expectedKind, long expectedGeneration)",
                "private static void deliverRetrySession(int expectedKind, long expectedGeneration)",
                "private void deliverInitialAlert(int kind, float value, String message, notGlucose glucose,",
                "private void syncRetrySession(int kind, float glvalue, String message, notGlucose strglucose, String type,",
                "private boolean lossofsignalalarm(int kind, int draw, String message, String type, boolean alarm, boolean manualTest)",
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
                    static final Map<Integer, Long> initialDeliveryGenerations = new HashMap<>();
                    static long initialDeliveryEpoch;
                    static long lastTestTime;
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
                    public int lossSounds;
                    public boolean saidloss;
                    public int legacyLossCalls;
                    boolean alertwatch = false;
                    static String GLUCOSENOTIFICATION = "glucose";
                    static class Future { void cancel(boolean interrupt) {} }
                    static class notGlucose {
                        long time; String value; float rate;
                        notGlucose() {}
                        notGlucose(long time, String value, float rate, int generation) {}
                    }
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
                        static AlertType LOSS = new AlertType();
                        AlertType fromId(int kind) { return kind == 4 ? LOSS : this; }
                        int getNameResId() { return 0; }
                        boolean isLegacyOnlyId(int kind) { return false; }
                    }
                    static class AlertConfig {
                        boolean getEnabled() { return true; }
                        boolean getRetryEnabled() { return true; }
                        boolean isActiveNow() { return true; }
                        int getRetryCount() { return 0; }
                    }
                    static class WearAlertSync {
                        static boolean suppressed;
                        static boolean isSuppressed(int kind) { return suppressed; }
                    }
                    static class AlertStateTracker {
                        static AlertStateTracker INSTANCE = new AlertStateTracker();
                        boolean pending, manual;
                        int firings;
                        void allowNextTriggerForTest(AlertType type) { pending = true; }
                        boolean shouldTrigger(AlertType type, AlertConfig config) {
                            if (pending) { pending = false; manual = true; return true; }
                            return !SnoozeManager.snoozed && !WearAlertSync.suppressed;
                        }
                        boolean onAlertTriggered(AlertType type, AlertConfig config) { firings++; return !manual; }
                        boolean isManualTestActive(AlertType type) { return manual; }
                        void supersedeManualTest(AlertType type) { manual = false; }
                        boolean isDismissed(AlertType type) { return false; }
                        boolean isEpisodeActive(AlertType type) { return true; }
                    }
                    static boolean isSameRetryFamily(int a, int b) { return a == b; }
                    static class AlertRepository {
                        static AlertRepository INSTANCE = new AlertRepository();
                        AlertConfig loadConfig(AlertType type) { return new AlertConfig(); }
                    }
                    static class SnoozeManager {
                        static SnoozeManager INSTANCE = new SnoozeManager();
                        static boolean snoozed;
                        boolean isSnoozed(AlertType type) { return snoozed; }
                    }
                    static class tk { static class glucodata { static class Applic { static int unit; } } }
                    static class Notify {
                        static RetryHarness onenot;
                        static long glucosetimeout = 60_000;
                        static void stoplossalarm() { onenot.soundingKind = -1; }
                    }
                    static class SensorBluetooth { static void reconnectall() {} static void ensureCurrentSensorSelection() {} }
                    static class View { void postInvalidate() {} }
                    static class Floating { static View floatview; }
                    static class SuperGattCallback {
                        static long lastfoundL = 1;
                        static Object previousglucose;
                        static boolean doWearInt = true;
                    }
                    static class Natives {
                        static long lastglucosetime() { return 1; }
                        static boolean shouldwakesender() { return false; }
                        static void wakestreamsender() {}
                    }
                    static boolean hasalarmloss() { return true; }
                    static long waitmmsec() { return 0; }
                    static class LossOfSensorAlarm { static void setalarm(Object app, long at) {} }
                    static class GlucoseWidget { static void oldvalue(long at) {} }
                    static class WearInt { static void missingalarm(long at) { onenot.legacyLossCalls++; } }
                    static class MessageSender { static void sendwakestream() {} }
                    static class WearSync2 { static void requestSync() {} }
                    static class WearComplicationValue { static void updateall() {} }
                    void oldnotification(long at) {}
                    public boolean lossalarm(long at) { return lossofsignalalarm(4, 0, "No reading", "LOSS", true, false); }
                    public void mobileTick() { runOnAlertThread(() -> mobileHandle()); }
                    public void wearTick() { runOnAlertThread(() -> wearHandle()); }
                    public void newReading() { setagealarm(1L, Notify.glucosetimeout); }
                    public void allowLoss() { SnoozeManager.snoozed = false; WearAlertSync.suppressed = false; }
                    static class Applic {
                        static int unit;
                        static Applic app = new Applic();
                        String getString(int id) { return "Test LOSS"; }
                    }
                    static class R { static class drawable { static int loss; } }
                    static class CurrentDisplaySource { static class Snapshot { float getPrimaryValue() { return 180; } } }
                    static class AlertTestValuePolicy {
                        static float resolve(AlertType type, AlertConfig config, boolean mmol, float value) { return 180; }
                    }
                    static CurrentDisplaySource.Snapshot resolveNotificationCurrentSnapshot() { return null; }
                    static String glucosestr(float value) { return "" + value; }
                    static String alertChannelForKind(int kind) { return "test"; }
                    static void allowNextAlertEffectsForTest() {}
                    void arrowglucosealarm(int kind, float value, String message, notGlucose glucose, String type, boolean alarm) {}
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
                    void mksound(int kind) { soundingKind = kind; if (kind == 4) lossSounds++; }
                    void placelargenotification(int draw, String message, String type, boolean once) {}
                    public void loss() { lossofsignalalarm(4, 0, "No reading", "LOSS", true, false); }
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
                        Notify.onenot = this;
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
                    public void startEmpty() {
                        main.clear();
                        mainThread = null;
                        onenot = this;
                        Notify.onenot = this;
                        activeRetrySession = null;
                        initialDeliveryGenerations.clear();
                        initialDeliveryEpoch = 0;
                        lastTestTime = 0;
                        SnoozeManager.snoozed = false;
                        WearAlertSync.suppressed = false;
                        AlertStateTracker.INSTANCE.pending = false;
                        AlertStateTracker.INSTANCE.manual = false;
                        AlertStateTracker.INSTANCE.firings = 0;
                    }
                    public void queueHigh() { deliverInitialAlert(1, 180, "High", new notGlucose(), "HIGH", new AlertConfig(), true); }
                    public void highFalling() { cancelRetrySession(1, "high-falling"); }
                    public void cancelLow() { cancelRetrySession(0, "resolved"); }
                    public void cancelAll() { cancelCurrentRetrySession("user-action"); }
                    public int firings() { return AlertStateTracker.INSTANCE.firings; }
                    public void quietLoss() { SnoozeManager.snoozed = true; WearAlertSync.suppressed = true; }
                    public void testLoss() { testTrigger(4); }
                    $code
                    $handlers
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
