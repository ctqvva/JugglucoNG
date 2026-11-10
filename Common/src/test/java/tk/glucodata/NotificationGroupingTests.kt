package tk.glucodata

import java.io.File
import java.net.URLClassLoader
import javax.tools.ToolProvider
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Test

/** Executes Notify's real builder methods without loading its native initialization. */
class NotificationGroupingTests {
    private fun builder(sdk: Int, wear: Boolean, alarm: Boolean): Any =
        harness.getMethod("create", Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .invoke(harness.getConstructor().newInstance(), sdk, wear, alarm)

    private fun field(builder: Any, name: String): Any? = builder.javaClass.getField(name).get(builder)

    @Test fun phoneStatusDoesNotDeclareAGroupWithoutASummary() {
        for (sdk in listOf(19, 20, 26, 35, 36)) {
            val status = builder(sdk, false, false)
            assertNull("Phone status must not enter app-group reconciliation, sdk=$sdk", field(status, "group"))
            assertEquals(true, field(status, "once"))
            assertNotNull(field(status, "intent"))
            if (sdk >= 26) assertEquals("glucoseNotification", field(status, "channel"))
        }
    }

    @Test fun wearRetainsItsExistingStatusGroup() {
        for (sdk in listOf(19, 20, 26, 36)) {
            assertEquals(if (sdk >= 20) "aa2" else null, field(builder(sdk, true, false), "group"))
        }
    }

    @Test fun alertsStaySeparateOnPhoneAndWear() {
        for (wear in listOf(false, true)) {
            val alert = builder(36, wear, true)
            assertNull(field(alert, "group"))
            assertEquals("glucoseAlarm", field(alert, "channel"))
            assertNotNull(field(alert, "intent"))
        }
    }

    companion object {
        private var scratch: File? = null
        private var loader: URLClassLoader? = null

        @JvmStatic @AfterClass fun cleanUp() {
            loader?.close()
            scratch?.deleteRecursively()
        }

        private val harness: Class<*> by lazy {
            val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .first { File(it, "Common/src/main/java/tk/glucodata/Notify.java").exists() }
            val source = File(root, "Common/src/main/java/tk/glucodata/Notify.java").readText()
            val start = source.indexOf("    private Notification.Builder mkbuilderintent(")
            val end = source.indexOf("    // Helper to format", start)
            check(start >= 0 && end > start) { "Both production builder overloads must be present" }
            val methods = source.substring(start, end)
            val dir = java.nio.file.Files.createTempDirectory("notification-grouping").toFile()
            scratch = dir
            val build = File(dir, "Build.java").apply {
                writeText("""
                package android.os;
                public class Build {
                    public static class VERSION { public static int SDK_INT; }
                    public static class VERSION_CODES {
                        public static final int O = 26, KITKAT_WATCH = 20;
                    }
                }
                """.trimIndent())
            }
            val fixture = File(dir, "GroupingHarness.java").apply {
                writeText("""
                import android.os.Build;
                public class GroupingHarness {
                    boolean isWearable;
                    static class Applic { static Object app = new Object(); }
                    static class PendingIntent {}
                    public static class Notification {
                        public static class Builder {
                            public String group, channel;
                            public boolean once;
                            public Object intent;
                            Builder(Object app) {}
                            Builder(Object app, String channel) { this.channel = channel; }
                            Builder setChannelId(String channel) { this.channel = channel; return this; }
                            Builder setContentIntent(PendingIntent intent) { this.intent = intent; return this; }
                            Builder setOnlyAlertOnce(boolean once) { this.once = once; return this; }
                            Builder setLocalOnly(boolean local) { return this; }
                            Builder setGroup(String group) { this.group = group; return this; }
                        }
                    }
                    public Object create(int sdk, boolean wear, boolean alarm) {
                        Build.VERSION.SDK_INT = sdk;
                        isWearable = wear;
                        PendingIntent intent = new PendingIntent();
                        return alarm ? mkbuilderintent("glucoseAlarm", intent, false)
                                : mkbuilderintent("glucoseNotification", intent);
                    }
                """.trimIndent() + methods + "\n}\n")
            }
            assertEquals("Compile production builder methods", 0,
                ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-d", dir.absolutePath, build.absolutePath, fixture.absolutePath))
            // Isolate the recording Build stub from Gradle's Android mock jar.
            URLClassLoader(arrayOf(dir.toURI().toURL()), null).also { loader = it }
                .loadClass("GroupingHarness")
        }
    }
}
