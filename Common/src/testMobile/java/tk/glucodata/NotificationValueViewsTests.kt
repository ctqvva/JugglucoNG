package tk.glucodata

import android.app.Application
import android.os.Parcel
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Host-safe notification typography, parceling, and single-row value layout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NotificationValueViewsTests {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun rowFor(weight: Int) =
        android.widget.RemoteViews(app.packageName, NotificationValueViews.rowLayoutForWeight(weight))

    private fun appliedTextViews(
        rows: List<android.widget.RemoteViews>,
        containerId: Int = R.id.notification_value_container
    ): List<TextView> {
        val parent = android.widget.RemoteViews(app.packageName, R.layout.notification_material)
        NotificationValueViews.clearValueRows(parent, containerId)
        for (row in rows) NotificationValueViews.addValueRow(parent, containerId, row)
        val view = parent.apply(app, null)
        val container = view.findViewById<android.view.ViewGroup>(containerId)
        check(container != null) { "native value container missing after apply" }
        check(container.childCount == rows.size) {
            "expected ${rows.size} native rows, got ${container.childCount}"
        }
        return (0 until container.childCount).map { index ->
            container.getChildAt(index).findViewById(R.id.notification_value_text)
        }
    }

    @Test fun weightSelectsDistinctInflationTimeRowLayouts() {
        val light = NotificationValueViews.rowLayoutForWeight(300)
        val regular = NotificationValueViews.rowLayoutForWeight(400)
        val medium = NotificationValueViews.rowLayoutForWeight(500)
        assertNotSame(light, regular)
        assertNotSame(regular, medium)
        assertNotSame(light, medium)
        assertEquals(R.layout.notification_value_row_light, light)
        assertEquals(R.layout.notification_value_row_regular, regular)
        assertEquals(R.layout.notification_value_row_medium, medium)
        assertEquals("unknown weights fall back to regular", regular,
            NotificationValueViews.rowLayoutForWeight(700))
        assertEquals(regular, NotificationValueViews.rowLayoutForWeight(0))
    }

    @Test fun namedOemFontAndWeightSurviveWithoutShippingATypefaceObject() {
        val text = NotificationValueViews.styledSystemText("5.5", "google-sans", 300) as Spanned
        assertEquals("google-sans", text.getSpans(0, text.length, TypefaceSpan::class.java).single().family)
        assertEquals(-100, text.getSpans(0, text.length, android.text.style.StyleSpan::class.java).single().fontWeightAdjustment)
    }

    @Test fun fontScaleIsSanitizedToSliderRange() {
        assertEquals(24f, NotificationValueViews.primaryTextSizeSp(false, 1f), 0.001f)
        assertEquals(28f, NotificationValueViews.primaryTextSizeSp(true, 1f), 0.001f)
        assertEquals(24f * 0.72f, NotificationValueViews.peerTextSizeSp(false, 1f), 0.001f)
        assertEquals(28f * 0.72f, NotificationValueViews.peerTextSizeSp(true, 1f), 0.001f)
        assertEquals("larger font scales stay readable", 36f,
            NotificationValueViews.primaryTextSizeSp(false, 1.5f), 0.001f)
        assertEquals("out-of-range scales fall back to the base size", 24f,
            NotificationValueViews.primaryTextSizeSp(false, 2f), 0.001f)
        assertEquals(24f, NotificationValueViews.primaryTextSizeSp(false, Float.NaN), 0.001f)
    }

    @Test fun primaryTextKeepsResolvedSpansForBothFontPaths() {
        val resolved = android.text.SpannableStringBuilder("7.8")
            .append(" · 1.1").append(" · raw")
        resolved.setSpan(ForegroundColorSpan(0xFF888888.toInt()), 3, 9, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        resolved.setSpan(RelativeSizeSpan(0.85f), 3, 9, Spanned.SPAN_INCLUSIVE_INCLUSIVE)

        val ibm = NotificationValueViews.styledValueText(app, resolved, false, 500)
        assertEquals("IBM path keeps the resolved text untouched", "7.8 · 1.1 · raw",
            ibm.toString())
        val ibmSpanned = ibm as Spanned
        assertTrue(ibmSpanned.getSpans(0, ibm.length, ForegroundColorSpan::class.java).isNotEmpty())
        assertTrue(ibmSpanned.getSpans(0, ibm.length, RelativeSizeSpan::class.java).isNotEmpty())

        val system = NotificationValueViews.styledValueText(app, resolved, true, 500) as Spanned
        val families = system.getSpans(0, system.length, TypefaceSpan::class.java)
        val family = NotificationValueViews.systemFontFamily(app)
        if (!family.isNullOrEmpty()) assertEquals(family, families.single().family)
        assertTrue("secondary spans survive under the system family span",
            system.getSpans(0, system.length, ForegroundColorSpan::class.java).isNotEmpty())
    }

    @Test fun numericTextPassesThroughWithoutReformatting() {
        assertEquals("5,5", NotificationValueViews.peerText("5,5"))
        assertEquals("102", NotificationValueViews.peerText("102"))
        assertEquals("", NotificationValueViews.peerText(null))
    }

    @Test fun rowsApplyAndParcelWithSpansAndSizes() {
        val primary = rowFor(500)
        val resolved: CharSequence = android.text.SpannableStringBuilder("5.5 · 0.4").also {
            it.setSpan(ForegroundColorSpan(0xFF888888.toInt()), 3, 9, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            it.setSpan(RelativeSizeSpan(0.85f), 3, 9, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        NotificationValueViews.bindValueRow(primary,
            NotificationValueViews.styledValueText(app, resolved, false, 500),
            0xFF112233.toInt(), NotificationValueViews.primaryTextSizeSp(false, 1f))
        NotificationValueViews.bindRowArrow(primary, null)

        val peer = rowFor(500)
        NotificationValueViews.bindValueRow(peer, NotificationValueViews.peerText("102"),
            0xFF445566.toInt(), NotificationValueViews.peerTextSizeSp(false, 1f))
        NotificationValueViews.bindRowArrow(peer, null)

        val parcel = Parcel.obtain()
        try {
            primary.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored =
                android.widget.RemoteViews.CREATOR.createFromParcel(parcel)
            val views = appliedTextViews(listOf(restored, peer))
            assertEquals("5.5 · 0.4", views[0].text.toString())
            assertEquals("mg/dL and mmol text are never reformatted", "102",
                views[1].text.toString())
            assertEquals(0xFF112233.toInt(), views[0].currentTextColor)
            val primaryPx = views[0].textSize
            val peerPx = views[1].textSize
            assertTrue("peer values stay subdued, was $peerPx vs $primaryPx", peerPx < primaryPx)
            val expectedSp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f,
                app.resources.displayMetrics)
            assertEquals(expectedSp, primaryPx, expectedSp * 0.02f)
            val spanned = views[0].text as Spanned
            assertTrue(spanned.getSpans(0, spanned.length, ForegroundColorSpan::class.java)
                .isNotEmpty())
        } finally {
            parcel.recycle()
        }
    }

    @Test fun multiSourcePeersEachKeepTheirOwnArrowSlot() {
        val parent = android.widget.RemoteViews(app.packageName, R.layout.notification_material)
        NotificationValueViews.clearValueRows(parent, R.id.notification_value_container)
        val primary = rowFor(400)
        NotificationValueViews.bindValueRow(primary, "7.8", 0xFF000000.toInt(), 24f)
        NotificationValueViews.bindRowArrow(primary, null)
        NotificationValueViews.addValueRow(parent, R.id.notification_value_container, primary)
        val peer = rowFor(400)
        NotificationValueViews.bindValueRow(peer, "102", 0xFF000000.toInt(), 24f * 0.72f)
        NotificationValueViews.bindRowArrow(peer, null)
        NotificationValueViews.addValueRow(parent, R.id.notification_value_container, peer)

        val root = parent.apply(app, null)
        val container = root.findViewById<android.view.ViewGroup>(R.id.notification_value_container)
        check(container != null)
        assertEquals(2, container.childCount)
        for (index in 0 until container.childCount) {
            val row = container.getChildAt(index)
            check(row.findViewById<TextView>(R.id.notification_value_text) != null) {
                "peer row $index lost its native value text"
            }
            assertEquals("row $index arrow slot must exist and stay hidden without a bitmap",
                View.GONE, row.findViewById<ImageView>(R.id.notification_value_arrow).visibility)
        }
    }

    @Test fun compactAndExpandedKeepAllValuesInOneRow() {
        val peers = listOf(
            NotificationChartDrawer.ValueItem("102", 0xff335577.toInt(), Float.NaN),
            NotificationChartDrawer.ValueItem("98", 0xff775533.toInt(), Float.NaN))
        for (expanded in listOf(false, true)) {
            val layout = if (expanded) R.layout.notification_material_regular_expanded else R.layout.notification_material
            val parent = android.widget.RemoteViews(app.packageName, layout)
            NotificationValueViews.bindNativeValueRows(app, parent, expanded, "5.5 · 5.8",
                0xff112233.toInt(), peers, 0f, 0xff112233.toInt(), true,
                1f, 1f, 400, true, false, false)
            NotificationValueViews.bindStatus(app, parent, expanded, false, "Connected", 0xff222222.toInt(), 1f)
            val root = parent.apply(app, null)
            val container = root.findViewById<android.widget.LinearLayout>(R.id.notification_value_container)
            assertEquals(3, container.childCount)
            assertEquals(View.GONE, root.findViewById<View>(R.id.notification_legacy_value_row).visibility)
            var statusView: View? = root.findViewById<TextView>(R.id.notification_native_status)
            while (statusView != null) {
                assertEquals("status and its ancestors must be visible", View.VISIBLE, statusView.visibility)
                statusView = statusView.parent as? View
            }
            assertEquals(null, container.contentDescription)
            assertEquals(android.widget.LinearLayout.HORIZONTAL, container.orientation)
            assertEquals("5.5 · 5.8", container.getChildAt(0).findViewById<TextView>(R.id.notification_value_text).text.toString())
        }
    }

    @Test fun compactFitsHostHeightAtLargeFontScaleAndExpandedRetainsScale() {
        val configuration = android.content.res.Configuration(app.resources.configuration).also { it.fontScale = 2f }
        val context = app.createConfigurationContext(configuration)
        val parent = android.widget.RemoteViews(app.packageName, R.layout.notification_material)
        NotificationValueViews.bindNativeValueRows(context, parent, false, "10.9 · 11.2",
            0xff112233.toInt(), emptyList(), 0f, 0xff112233.toInt(), true,
            1f, 1.5f, 500, true, false, false)
        NotificationValueViews.bindStatus(context, parent, false, false, "Connected", 0xff222222.toInt(), 1.5f)
        val root = parent.apply(context, null)
        val density = context.resources.displayMetrics.density
        root.measure(View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue("compact content must fit the 48dp host, got ${root.measuredHeight / density}",
            root.measuredHeight <= 48 * density + 1)
        assertEquals(42f, NotificationValueViews.renderedTextSizeSp(context, true, 1.5f), 0.01f)
        assertEquals("10.9 · 11.2", root.findViewById<TextView>(R.id.notification_value_text).text.toString())
    }

    @Test fun secondaryLanesUseResolvedPaletteWithoutChangingValuesOrSizes() {
        val input = android.text.SpannableStringBuilder("7.8 · 8.1 · 8.4")
        input.setSpan(ForegroundColorSpan(0xff888888.toInt()), 3, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        input.setSpan(ForegroundColorSpan(0xffaaaaaa.toInt()), 9, 15, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        input.setSpan(RelativeSizeSpan(0.7f), 9, 15, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val result = NotificationValueViews.withLaneColors(input, 0xff223344.toInt(), 0xff445566.toInt()) as Spanned
        assertEquals(input.toString(), result.toString())
        val colors = result.getSpans(0, result.length, ForegroundColorSpan::class.java)
        assertEquals(listOf(0xff223344.toInt(), 0xff445566.toInt()), colors.map { it.foregroundColor })
        assertEquals(0.7f, result.getSpans(0, result.length, RelativeSizeSpan::class.java).single().sizeChange, 0.001f)
    }

    @Test fun ibmValueSurvivesRestrictedNotificationHostAsAccessibleGlyphBitmap() {
        val parent = android.widget.RemoteViews(app.packageName, R.layout.notification_material_regular_expanded)
        NotificationValueViews.bindNativeValueRows(app, parent, true, "7.0",
            0xff112233.toInt(), emptyList(), 0f, 0xff112233.toInt(), true,
            1f, 1f, 400, false, false, false)
        val restrictedHost = object : android.content.ContextWrapper(app) {
            override fun isRestricted() = true
        }
        val parcel = Parcel.obtain()
        try {
            parent.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = android.widget.RemoteViews.CREATOR.createFromParcel(parcel)
            val root = restored.apply(restrictedHost, null)
            assertEquals(View.GONE, root.findViewById<TextView>(R.id.notification_value_text).visibility)
            val glyphs = root.findViewById<ImageView>(R.id.notification_value_bitmap)
            assertEquals(View.VISIBLE, glyphs.visibility)
            assertEquals("7.0", glyphs.contentDescription.toString())
            assertTrue(glyphs.drawable.intrinsicWidth > 0)
        } finally { parcel.recycle() }
    }
}
