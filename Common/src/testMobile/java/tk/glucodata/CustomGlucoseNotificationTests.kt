package tk.glucodata

import android.app.Application
import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.RotateDrawable
import android.graphics.drawable.VectorDrawable
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CustomGlucoseNotificationTests {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private fun values(expanded: Boolean = true, rate: Float = 0f,
        peers: List<NotificationChartDrawer.ValueItem> = listOf(NotificationChartDrawer.ValueItem("5,5", 0xff81a9f6.toInt(), 0f)),
        scale: Float = 1f, enabled: Boolean = true, systemFont: Boolean = true, weight: Int = 400,
        status: CharSequence = "") =
        CustomGlucoseNotification.values(app, expanded, "5,7", 0xffeeeeee.toInt(), 0xffcccccc.toInt(),
            0xffaaaaaa.toInt(), peers, rate, 0xffff0000.toInt(), true, scale, weight, systemFont, false, enabled, 1f, status, true)

    @Test fun supportingLineSurvivesParcelAndFontSwitchWithoutRetainingOldContent() {
        val status = "+0,1 · IOB 1,2"
        val parcel = Parcel.obtain()
        try {
            values(systemFont = false, status = status).writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restricted = object : android.content.ContextWrapper(app) { override fun isRestricted() = true }
            val root = android.widget.RemoteViews.CREATOR.createFromParcel(parcel).apply(restricted, null)
            val image = root.findViewById<ImageView>(R.id.notification_status_image)
            val text = root.findViewById<TextView>(R.id.notification_status)
            assertEquals(View.GONE, text.visibility)
            assertEquals(View.VISIBLE, image.visibility)
            assertEquals(status, image.contentDescription.toString())
            val bitmap = (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap
            assertEquals((bitmap.height + 1) / 2, image.maxHeight)

            values(status = status).reapply(app, root)
            assertEquals(View.GONE, image.visibility)
            assertNull((image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap)
            assertEquals(View.VISIBLE, text.visibility)
            val styled = text.text as android.text.Spanned
            assertEquals(status, styled.toString())
            assertEquals(CustomGlucoseNotification.systemBodyFamily(app),
                styled.getSpans(0, styled.length, android.text.style.TypefaceSpan::class.java).single().family)

            values(status = "", systemFont = false).reapply(app, root)
            assertEquals(View.GONE, image.visibility)
            assertEquals(View.GONE, text.visibility)
        } finally { parcel.recycle() }
    }

    @Test fun bundledSupportingLineBoundsLongStyledContentAndKeepsItsAccessibleText() {
        val status = android.text.SpannableStringBuilder("+0,1 · " + "sensor status ".repeat(80))
        status.setSpan(android.text.style.ForegroundColorSpan(0xffff0000.toInt()), 7, 13,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val bitmap = NotificationValueBitmap.drawStatus(app, status, 0xffaaaaaa.toInt(), 12f, 240)
        assertTrue(bitmap.width <= 480)
        val short = NotificationValueBitmap.drawStatus(app, "+0,1", 0xffaaaaaa.toInt(), 12f, 240)
        assertEquals(short.height, bitmap.height)
        val root = values(systemFont = false, status = status).apply(app, null)
        assertEquals(status.toString(), root.findViewById<ImageView>(R.id.notification_status_image).contentDescription.toString())
    }

    @Test fun nativeReadoutSurvivesParcelAndRestrictedHostWithoutBitmapValues() {
        val parcel = Parcel.obtain()
        try {
            values().writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = android.widget.RemoteViews.CREATOR.createFromParcel(parcel)
            val restricted = object : android.content.ContextWrapper(app) { override fun isRestricted() = true }
            val root = restored.apply(restricted, null)
            assertEquals("5,7", root.findViewById<TextView>(R.id.notification_glucose).text.toString())
            assertEquals("5,5", root.findViewById<TextView>(R.id.notification_peer_value).text.toString())
            val arrow = root.findViewById<ImageView>(R.id.notification_arrow)
            assertTrue(arrow.drawable is RotateDrawable)
            assertTrue((arrow.drawable as RotateDrawable).drawable is VectorDrawable)
            assertEquals("Steady trend", arrow.contentDescription.toString())
            assertEquals(View.GONE, root.findViewById<TextView>(R.id.notification_status).visibility)
        } finally { parcel.recycle() }
    }

    @Test fun unknownPrimaryDoesNotSuppressPeerTrendAndReapplyRemovesOldPeers() {
        val root = values(rate = Float.NaN).apply(app, null)
        assertEquals(View.GONE, root.findViewById<ImageView>(R.id.notification_arrow).visibility)
        assertEquals(View.VISIBLE, root.findViewById<ImageView>(R.id.notification_peer_arrow).visibility)
        values(peers = emptyList()).reapply(app, root)
        assertEquals(0, root.findViewById<ViewGroup>(R.id.notification_peers).childCount)
        assertEquals(View.VISIBLE, root.findViewById<ImageView>(R.id.notification_arrow).visibility)
    }

    @Test fun directionAndDoubleHeadThresholdUseTheSharedRateConvention() {
        for ((rate, level) in listOf(-3f to 10000, -1f to 7500, 0.5f to 5000, 1f to 2500, 3f to 0)) {
            val root = values(rate = rate).apply(app, null)
            val arrow = root.findViewById<ImageView>(R.id.notification_arrow)
            assertEquals(level, arrow.drawable.level)
            val description = arrow.contentDescription.toString()
            assertEquals(kotlin.math.abs(rate) > 2f, description.startsWith("Rapidly"))
        }
        val hidden = values(enabled = false).apply(app, null)
        assertEquals(View.GONE, hidden.findViewById<ImageView>(R.id.notification_arrow).visibility)
        assertEquals(View.GONE, hidden.findViewById<ImageView>(R.id.notification_peer_arrow).visibility)
    }

    @Test fun compactPlotUsesItsHeightInsteadOfFittingAScreenWidthRaster() {
        val views = values(expanded = false)
        CustomGlucoseNotification.chart(views, Bitmap.createBitmap(1344, 144, Bitmap.Config.ARGB_8888))
        val root = views.apply(app, null)
        val density = app.resources.displayMetrics.density
        root.measure(View.MeasureSpec.makeMeasureSpec((300*density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val chart = root.findViewById<ImageView>(R.id.notification_chart)
        assertEquals(ImageView.ScaleType.FIT_XY, chart.scaleType)
        assertEquals((48*density).toInt(), chart.height)
        assertTrue(chart.width > 0)
    }

    @Test fun expandedChartFitsEntireImageAndHidesCleanly() {
        val views = values()
        CustomGlucoseNotification.chart(views, Bitmap.createBitmap(400, 256, Bitmap.Config.ARGB_8888))
        val root = views.apply(app, null)
        assertEquals(ImageView.ScaleType.FIT_CENTER, root.findViewById<ImageView>(R.id.notification_chart).scaleType)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.chart_container).visibility)
        CustomGlucoseNotification.chart(views, null)
        views.reapply(app, root)
        assertEquals(View.GONE, root.findViewById<View>(R.id.chart_container).visibility)
    }

    @Test fun preferenceSizeScalesNativeTextAndCompactFitsHeightBudget() {
        val ordinary = values().apply(app, null).findViewById<TextView>(R.id.notification_glucose)
        val larger = values(scale = 1.5f).apply(app, null).findViewById<TextView>(R.id.notification_glucose)
        assertTrue(larger.textSize > ordinary.textSize)
        val compact = values(expanded = false, scale = 1.5f).apply(app, null)
        val density = app.resources.displayMetrics.density
        compact.measure(View.MeasureSpec.makeMeasureSpec((300*density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue(compact.measuredHeight <= 48*density+1)
    }

    @Test fun ibmPlexHasAccessibleBoundedPrimaryAndPeerImagesAndKeepsVectorArrows() {
        val restricted = object : android.content.ContextWrapper(app) { override fun isRestricted() = true }
        val views = values(expanded = false, scale = 1.5f, systemFont = false)
        val parcel = Parcel.obtain()
        try {
            views.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val root = android.widget.RemoteViews.CREATOR.createFromParcel(parcel).apply(restricted, null)
            assertEquals(View.GONE, root.findViewById<TextView>(R.id.notification_glucose).visibility)
            assertEquals(View.GONE, root.findViewById<TextView>(R.id.notification_peer_value).visibility)
            for ((id, description) in listOf(R.id.notification_glucose_image to "5,7", R.id.notification_peer_image to "5,5")) {
                val image = root.findViewById<ImageView>(id)
                assertEquals(View.VISIBLE, image.visibility)
                assertEquals(description, image.contentDescription.toString())
                val bitmap = (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                assertTrue(bitmap.width > 0)
                assertEquals((bitmap.height + 1) / 2, image.maxHeight)
            }
            assertTrue(root.findViewById<ImageView>(R.id.notification_arrow).drawable is RotateDrawable)
            val density = app.resources.displayMetrics.density
            root.measure(View.MeasureSpec.makeMeasureSpec((300*density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            assertTrue(root.measuredHeight <= 48*density+1)
        } finally { parcel.recycle() }
    }

    @Test fun familySwitchReappliesBothDirectionsAndClearsHiddenBitmap() {
        val root = values().apply(app, null)
        values(systemFont = false).reapply(app, root)
        assertEquals(View.VISIBLE, root.findViewById<ImageView>(R.id.notification_glucose_image).visibility)
        assertEquals(View.GONE, root.findViewById<TextView>(R.id.notification_glucose).visibility)
        values().reapply(app, root)
        assertEquals(View.VISIBLE, root.findViewById<TextView>(R.id.notification_glucose).visibility)
        assertEquals(View.GONE, root.findViewById<ImageView>(R.id.notification_glucose_image).visibility)
        assertNull((root.findViewById<ImageView>(R.id.notification_glucose_image).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap)
        assertEquals(View.VISIBLE, root.findViewById<TextView>(R.id.notification_peer_value).visibility)
        assertNull((root.findViewById<ImageView>(R.id.notification_peer_image).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap)
    }

    @Test fun systemTextUsesTheConfiguredHeadlineFamilyRatherThanGenericSans() {
        for (weight in listOf(400, 500)) {
            val text = values(weight = weight).apply(app, null).findViewById<TextView>(R.id.notification_glucose).text as android.text.Spanned
            val spans = text.getSpans(0, text.length, android.text.style.TypefaceSpan::class.java)
            assertEquals(1, spans.size)
            val resource = if (weight == 500) "config_headlineFontFamilyMedium" else "config_headlineFontFamily"
            val id = app.resources.getIdentifier(resource, "string", "android")
            val expected = if (id != 0) app.resources.getString(id) else if (weight == 500) "sans-serif-medium" else "sans-serif"
            assertEquals(expected, spans[0].family)
        }
        val light = values(weight = 300).apply(app, null)
        assertEquals(View.VISIBLE, light.findViewById<ImageView>(R.id.notification_glucose_image).visibility)
    }

    @Test fun arrowAndSensorGapsScaleWithTheReadoutAndPreserveVectorSize() {
        for (system in listOf(true, false)) {
            var previousGap = 0
            var previousSensorGap = 0
            for (scale in listOf(0.6f, 1f, 1.5f)) {
                val root = values(scale = scale, systemFont = system).apply(app, null)
                val density = app.resources.displayMetrics.density
                root.measure(View.MeasureSpec.makeMeasureSpec((500*density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                val arrow = root.findViewById<ImageView>(R.id.notification_arrow)
                val peerArrow = root.findViewById<ImageView>(R.id.notification_peer_arrow)
                val peer = root.findViewById<View>(R.id.notification_peer_group)
                assertTrue(arrow.paddingLeft > previousGap)
                assertTrue(peer.paddingLeft > previousSensorGap)
                assertEquals(arrow.paddingLeft, peerArrow.paddingLeft)
                // The added optical gap occupies width, not the vector's square.
                assertEquals(arrow.height, arrow.width - arrow.paddingLeft)
                assertEquals(peerArrow.height, peerArrow.width - peerArrow.paddingLeft)
                previousGap = arrow.paddingLeft
                previousSensorGap = peer.paddingLeft
            }
            val compact = values(expanded = false, scale = 0.6f, systemFont = system).apply(app, null)
            val expanded = values(scale = 0.6f, systemFont = system).apply(app, null)
            assertTrue(compact.findViewById<ImageView>(R.id.notification_arrow).paddingLeft <=
                    expanded.findViewById<ImageView>(R.id.notification_arrow).paddingLeft)
        }
    }

    @Test fun notificationKeepsCustomContentAndActualTimestampWithoutListenerText() {
        val builder = Notification.Builder(app, "glucose").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setWhen(123456L).setShowWhen(true).setOnlyAlertOnce(true)
        CustomGlucoseNotification.apply(builder, values(expanded = false), values())
        val notification = builder.build()
        assertNotNull(notification.contentView)
        assertNotNull(notification.bigContentView)
        // Watch companion apps forward any notification with standard text (#645).
        assertNull(notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertNull(notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(123456L, notification.`when`)
    }
}
