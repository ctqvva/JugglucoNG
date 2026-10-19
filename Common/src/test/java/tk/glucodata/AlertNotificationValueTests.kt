package tk.glucodata

import java.io.File
import java.net.URLClassLoader
import javax.tools.ToolProvider
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.alerts.AlertDisplayText

/**
 * Execute the production banner's value assignment and alarm formatter without loading
 * Notify's native-backed singleton. The old history lookup uses the real display resolver;
 * only Android text styling is omitted. This catches re-resolving a frozen alert value.
 */
class AlertNotificationValueTests {
    @Test fun lowBannerKeepsTriggeringValueWhenHistoryIsHigher() {
        assertEquals("69", render(69f, "69", 75f, false))
    }

    @Test fun highBannerKeepsTriggeringValueWhenHistoryIsLower() {
        assertEquals("181", render(181f, "181", 160f, false))
    }

    @Test fun mmolBannerKeepsTriggeringValue() {
        assertEquals("3.8", render(3.8f, "3.8", 4.2f, true))
    }

    @Test fun messageOnlyBannerDoesNotBorrowAReadingFromHistory() {
        assertEquals("", render(Float.NaN, "", 75f, false))
    }

    private fun render(value: Float, snapshot: String, history: Float, mmol: Boolean): String =
        compiled.getMethod("render", Float::class.javaPrimitiveType, String::class.java,
            Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .invoke(null, value, snapshot, history, mmol) as String

    companion object {
        private var scratch: File? = null
        private var loader: URLClassLoader? = null

        @JvmStatic @AfterClass fun cleanup() {
            loader?.close()
            scratch?.deleteRecursively()
        }

        private val compiled: Class<*> by lazy {
            val root = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
                .first { File(it, "Common/src/main/java/tk/glucodata/Notify.java").exists() }
            val source = File(root, "Common/src/main/java/tk/glucodata/Notify.java").readText()
            val bannerStart = source.indexOf("    private void makeseparatenotification(")
            val valueStart = source.indexOf("                CharSequence valueText =", bannerStart)
            check(bannerStart >= 0 && valueStart > bannerStart)
            val valueAssignment = source.substring(valueStart, source.indexOf(';', valueStart) + 1)
            val helperStart = source.indexOf("    private static String alarmDisplayGlucoseValue(")
            check(helperStart >= 0)
            val helper = source.substring(helperStart, source.indexOf("\n    }", helperStart) + 6)
            val dir = java.nio.file.Files.createTempDirectory("alert-notification-value").toFile()
            scratch = dir
            val sourceFile = File(dir, "AlertValueHarness.java").apply {
                writeText("""
                    package tk.glucodata;
                    import java.util.*;
                    import tk.glucodata.alerts.AlertDisplayText;
                    public class AlertValueHarness {
                        static Locale usedlocale = Locale.US;
                        static String pureglucoseformat;
                        static boolean isMmol;
                        static class notGlucose {
                            String value; long time;
                            notGlucose(String value, long time) { this.value = value; this.time = time; }
                        }
                        static String format(Locale locale, String pattern, Object... values) {
                            return String.format(locale, pattern, values);
                        }
                        $helper
                        static CharSequence formatGlucoseText(String value, float glvalue, List<GlucosePoint> points,
                                int viewMode, long targetTime, String sensorId) {
                            CurrentDisplaySource.Snapshot resolved = CurrentDisplaySource.resolveFromLive(
                                value, glvalue, Float.NaN, targetTime, sensorId, 0, 0, "notification",
                                points, viewMode, isMmol);
                            return resolved != null ? resolved.getPrimaryStr() : "";
                        }
                        public static String render(float glvalue, String snapshotValue, float historyValue, boolean mmol) {
                            isMmol = mmol;
                            pureglucoseformat = mmol ? "%.1f" : "%.0f";
                            notGlucose glucose = new notGlucose(snapshotValue, 1700000000000L);
                            String activeSensorSerial = "alert-value-test";
                            int viewMode = 0;
                            List<GlucosePoint> nativePoints = List.of(new GlucosePoint(glucose.time, historyValue, historyValue));
                            String alarmGlucoseValue = alarmDisplayGlucoseValue(glvalue, glucose);
                            $valueAssignment
                            return valueText.toString();
                        }
                    }
                """.trimIndent())
            }
            val paths = listOf(CurrentDisplaySource::class.java, GlucosePoint::class.java,
                AlertDisplayText::class.java, kotlin.Unit::class.java).map {
                File(checkNotNull(it.protectionDomain).codeSource.location.toURI()).path
            }.distinct().joinToString(File.pathSeparator)
            val output = java.io.ByteArrayOutputStream()
            val result = checkNotNull(ToolProvider.getSystemJavaCompiler()).run(null, output, output,
                "-classpath", paths, "-d", dir.path, sourceFile.path)
            check(result == 0) { output.toString() }
            URLClassLoader(arrayOf(dir.toURI().toURL()), AlertNotificationValueTests::class.java.classLoader)
                .also { loader = it }.loadClass("tk.glucodata.AlertValueHarness")
        }
    }
}
