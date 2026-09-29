package tk.glucodata

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source/resource contract for the phone native value rows.
 *
 * Plain JVM file scans (no Android runtime): the IBM Plex weight preference must be
 * carried by inflation-time font/layout resources because
 * TextView.setFontVariationSettings is not remotely callable below API 35, and the
 * legacy raster row must be hidden on the phone while Wear keeps it.
 */
class NotificationValueViewsContractTests {
    private fun repoRoot(): File =
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "Common/src/main/java/tk/glucodata/Notify.java").exists() }

    private fun mainFile(relative: String): File {
        val file = File(repoRoot(), "Common/src/main/$relative")
        check(file.exists()) { "missing production file: $relative" }
        return file
    }

    @Test fun weightFontFamiliesCoverSettingsRange() {
        val expectations = mapOf(
            "res/font/notif_font_light.xml" to "300",
            "res/font/notif_font_regular.xml" to "400",
            "res/font/notif_font_medium.xml" to "500"
        )
        for ((path, weight) in expectations) {
            val text = mainFile(path).readText()
            assertTrue("$path must declare fontWeight $weight",
                text.contains("android:fontWeight=\"$weight\""))
            assertTrue("$path must use the matching IBM Plex font",
                text.contains("@font/ibm_plex_sans_" + when (weight) { "300" -> "light"; "500" -> "medium"; else -> "var" }))
        }
    }

    @Test fun valueRowLayoutsUseInflationTimeFonts() {
        val expectations = mapOf(
            "res/layout/notification_value_row_light.xml" to "@font/notif_font_light",
            "res/layout/notification_value_row_regular.xml" to "@font/notif_font_regular",
            "res/layout/notification_value_row_medium.xml" to "@font/notif_font_medium"
        )
        for ((path, family) in expectations) {
            val text = mainFile(path).readText()
            assertTrue("$path must carry the native value TextView",
                text.contains("android:id=\"@+id/notification_value_text\""))
            assertTrue("$path must select its IBM weight at inflation time via $family",
                text.contains("android:fontFamily=\"$family\""))
            assertTrue("$path must keep tabular figures for glucose values",
                text.contains("tnum"))
            assertTrue("$path must carry the per-row arrow slot",
                text.contains("android:id=\"@+id/notification_value_arrow\""))
        }
    }

    @Test fun ongoingLayoutsExposeNativeContainerAndLegacyRow() {
        for (path in listOf(
            "res/layout/notification_material.xml",
            "res/layout/notification_material_regular_expanded.xml"
        )) {
            val text = mainFile(path).readText()
            assertTrue("$path must expose the native value container",
                text.contains("android:id=\"@+id/notification_value_container\""))
            assertTrue("$path must keep the legacy raster row addressable for phone hiding",
                text.contains("android:id=\"@+id/notification_legacy_value_row\""))
        }
    }

    @Test fun noUnsupportedRemoteFontSetters() {
        for (path in listOf(
            "java/tk/glucodata/Notify.java",
            "java/tk/glucodata/NotificationValueViews.java"
        )) {
            val lines = mainFile(path).readText().lines()
            // setTypeface / setTextAppearance are not remotely callable at any API.
            assertTrue("$path must not call RemoteViews.setTypeface",
                lines.none { it.contains("\"setTypeface\"") })
            assertTrue("$path must not call RemoteViews.setTextAppearance",
                lines.none { it.contains("\"setTextAppearance\"") })
            // setFontVariationSettings is remotely callable only from API 35: every
            // remote use (the quoted setter name passed to RemoteViews.setString) must
            // sit behind an SDK_INT >= 35 guard. Direct TextPaint calls for local bitmap
            // rendering are unaffected and not matched here.
            lines.forEachIndexed { index, line ->
                if (line.contains("\"setFontVariationSettings\"")) {
                    val window = lines.subList(maxOf(0, index - 8), index + 1).joinToString("\n")
                    assertTrue("$path:${index + 1} uses setFontVariationSettings without an API-35 guard",
                        window.contains("SDK_INT >= 35"))
                }
            }
        }
    }

    @Test fun phoneHidesLegacyRasterRow() {
        val notify = mainFile("java/tk/glucodata/Notify.java").readText()
        val helper = mainFile("java/tk/glucodata/NotificationValueViews.java").readText()
        assertTrue("phone must hide the legacy raster row",
            helper.contains("R.id.notification_legacy_value_row"))
        assertTrue("phone must show the native value container",
            helper.contains("R.id.notification_value_container"))
        // The Wear bitmap composition path must remain intact.
        assertTrue("Wear bitmap value path must be preserved",
            notify.contains("drawMultiGlucoseText"))
    }

    @Test fun staleStatusIsQuietStandardTextWithActualTimestamp() {
        val notify = mainFile("java/tk/glucodata/Notify.java").readText()
        val start = notify.indexOf("private Notification makeStaleReadingNotification(")
        check(start >= 0) { "makeStaleReadingNotification missing" }
        val end = notify.indexOf("\n    }", start)
        val body = notify.substring(start, end)
        assertTrue("stale status must reuse the translated nonewvalue text",
            body.contains("staleMessage("))
        assertTrue("stale status must carry the actual reading header timestamp",
            body.contains("applyReadingHeaderTimestamp"))
        assertTrue("stale status must stay quiet", body.contains("PRIORITY_DEFAULT"))
        assertFalse("stale status must not render value bitmaps or arrows",
            body.contains("drawMultiGlucoseText") || body.contains("drawGlucoseText")
                || body.contains("drawArrow"))
    }

    @Test fun helperPreservesSpansAndPeerText() {
        val helper = mainFile("java/tk/glucodata/NotificationValueViews.java").readText()
        assertTrue("system weight must travel as a parcelable TypefaceSpan",
            helper.contains("TypefaceSpan"))
        assertTrue("only RemoteViews methods available since API 26 may be used",
            helper.contains("removeAllViews") && helper.contains("addView"))
        assertTrue("peer text must pass through without reformatting",
            helper.contains("peerText"))
    }
}
