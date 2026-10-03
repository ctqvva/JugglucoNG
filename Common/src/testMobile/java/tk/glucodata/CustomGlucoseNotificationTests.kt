package tk.glucodata

import android.app.Application
import android.app.Notification
import android.graphics.Bitmap
import android.os.Parcel
import android.view.View
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
    private fun values(expanded: Boolean, system: Boolean = false, scale: Float = 1f) =
        CustomGlucoseNotification.values(app, expanded, "5,7", 0xffeeeeee.toInt(), 0xffcccccc.toInt(),
            0xffaaaaaa.toInt(), listOf(NotificationChartDrawer.ValueItem("5,5", 0xff81a9f6.toInt(), 0f)),
            0f, 0xffeeeeee.toInt(), true, scale, 400, system, true, 1f, "", true)

    @Test fun customStripSurvivesRestrictedHostWithUnitlessAccessibleValues() {
        for (system in listOf(false, true)) {
            val views = values(true, system)
            val parcel = Parcel.obtain()
            try {
                views.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                val restored = android.widget.RemoteViews.CREATOR.createFromParcel(parcel)
                val restricted = object : android.content.ContextWrapper(app) {
                    override fun isRestricted() = true
                }
                val root = restored.apply(restricted, null)
                val image = root.findViewById<ImageView>(R.id.notification_glucose_image)
                assertEquals("5,7 · 5,5", image.contentDescription.toString())
                assertTrue(image.drawable.intrinsicWidth > image.drawable.intrinsicHeight)
                assertEquals("peers already carry their app arrows inside the strip", View.GONE,
                    root.findViewById<ImageView>(R.id.notification_arrow).visibility)
                assertEquals(View.GONE, root.findViewById<TextView>(R.id.notification_status).visibility)
            } finally { parcel.recycle() }
        }
    }

    @Test
    @Config(sdk = [34])
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun peerArrowSurvivesUnknownPrimaryAndForecastColorIsIndependent() {
        val peers = listOf(NotificationChartDrawer.ValueItem("5,5", 0xff81a9f6.toInt(), 0f))
        fun strip(rate: Float, enabled: Boolean, arrowColor: Int) =
            NotificationChartDrawer.drawMultiGlucoseText(app, "5,7", 0xffffffff.toInt(),
                0xffcccccc.toInt(), 0xffaaaaaa.toInt(), peers, 1f, 400, true, rate, true,
                1f, enabled, arrowColor)
        val noArrows = strip(Float.NaN, false, 0xffff0000.toInt())
        val peerOnly = strip(Float.NaN, true, 0xffff0000.toInt())
        assertTrue("valid peer trend is independent of unknown primary", peerOnly.width > noArrows.width)
        val forecast = strip(0f, true, 0xffff0000.toInt())
        val pixels = IntArray(forecast.width * forecast.height)
        forecast.getPixels(pixels, 0, forecast.width, 0, 0, forecast.width, forecast.height)
        assertTrue("forecast arrow keeps its independently resolved color", pixels.any {
            android.graphics.Color.red(it) > 200 && android.graphics.Color.green(it) < 80 &&
                android.graphics.Color.blue(it) < 80 && android.graphics.Color.alpha(it) > 100
        })
    }

    @Test
    @Config(sdk = [34])
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun singleSourceArrowScalesWithLongValueInNarrowHost() {
        val views = CustomGlucoseNotification.values(app, true, "123 · 124 · 125",
            0xffeeeeee.toInt(), 0xffcccccc.toInt(), 0xffaaaaaa.toInt(), emptyList(), 0f,
            0xffff0000.toInt(), false, 1.5f, 400, true, true, 1f, "", true)
        val root = views.apply(app, null)
        val density = app.resources.displayMetrics.density
        root.measure(View.MeasureSpec.makeMeasureSpec((180 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val image = root.findViewById<ImageView>(R.id.notification_glucose_image)
        assertTrue(image.width > 0 && image.width <= root.width)
        assertEquals(View.GONE, root.findViewById<ImageView>(R.id.notification_arrow).visibility)
        val bitmap = (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap
        assertTrue("2x raster must not inflate the System UI value", image.height <= (bitmap.height + 1) / 2)
        assertTrue(image.drawable.intrinsicWidth > image.drawable.intrinsicHeight)
    }

    @Test fun compactPlotUsesItsHeightInsteadOfFittingAScreenWidthRaster() {
        val views = values(false)
        CustomGlucoseNotification.chart(views, Bitmap.createBitmap(1344, 144, Bitmap.Config.ARGB_8888))
        val root = views.apply(app, null)
        val density = app.resources.displayMetrics.density
        root.measure(View.MeasureSpec.makeMeasureSpec((300 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val chart = root.findViewById<ImageView>(R.id.notification_chart)
        assertEquals(ImageView.ScaleType.FIT_XY, chart.scaleType)
        assertEquals((48 * density).toInt(), chart.height)
        assertTrue(chart.width > 0)
    }

    @Test fun expandedChartFitsEntireImageAndHidesCleanly() {
        val views = values(true)
        CustomGlucoseNotification.chart(views, Bitmap.createBitmap(400, 256, Bitmap.Config.ARGB_8888))
        val root = views.apply(app, null)
        val chart = root.findViewById<ImageView>(R.id.notification_chart)
        assertEquals(ImageView.ScaleType.FIT_CENTER, chart.scaleType)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.chart_container).visibility)
        CustomGlucoseNotification.chart(views, null)
        views.reapply(app, root)
        assertEquals(View.GONE, root.findViewById<View>(R.id.chart_container).visibility)
    }

    @Test fun preferenceSizeChangesExpandedValuesButCompactFitsHostHeight() {
        val ordinary = values(true, true).apply(app, null).findViewById<ImageView>(R.id.notification_glucose_image)
        val larger = values(true, true, 1.5f).apply(app, null).findViewById<ImageView>(R.id.notification_glucose_image)
        assertTrue(larger.drawable.intrinsicHeight > ordinary.drawable.intrinsicHeight)
        val compact = values(false, true, 1.5f).apply(app, null)
        val density = app.resources.displayMetrics.density
        compact.measure(View.MeasureSpec.makeMeasureSpec((300 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue("compact value strip must fit the 48dp host", compact.measuredHeight <= 48 * density + 1)
    }

    @Test fun notificationKeepsCustomContentAndReadingTimeWithNoVisibleUnitField() {
        val builder = Notification.Builder(app, "glucose").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setWhen(123456L).setShowWhen(true).setOnlyAlertOnce(true)
        CustomGlucoseNotification.apply(builder, "5,7",
            listOf(NotificationChartDrawer.ValueItem("5,5", 0, 0f)), "", values(false), values(true))
        val notification = builder.build()
        assertNotNull(notification.contentView)
        assertNotNull(notification.bigContentView)
        assertEquals("5,7 · 5,5", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(123456L, notification.`when`)
    }
}
