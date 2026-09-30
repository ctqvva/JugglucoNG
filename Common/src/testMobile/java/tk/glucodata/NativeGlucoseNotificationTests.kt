package tk.glucodata

import android.app.Application
import android.app.Notification
import android.graphics.Bitmap
import android.os.Parcel
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
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
class NativeGlucoseNotificationTests {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private fun builder() = Notification.Builder(app, "glucose").setSmallIcon(android.R.drawable.ic_dialog_info)

    @Test fun readingsKeepLocaleAndEachTrendOnTheSameTitle() {
        val peers = listOf(NotificationChartDrawer.ValueItem("7,2", 0, 0f))
        assertEquals("7,0 ↘ · 7,2 →", NativeGlucoseNotification.title("7,0", -1f, peers, true))
        assertEquals("126 · 130", NativeGlucoseNotification.title("126", -1f,
            listOf(NotificationChartDrawer.ValueItem("130", 0, 0f)), false))
    }

    @Test fun trendsUseCanonicalFlatThresholdAndUnknownIsNotFlat() {
        assertEquals("", NativeGlucoseNotification.trendSymbol(Float.NaN))
        assertEquals("", NativeGlucoseNotification.trendSymbol(Float.POSITIVE_INFINITY))
        assertEquals("→", NativeGlucoseNotification.trendSymbol(0.5f))
        assertEquals("→", NativeGlucoseNotification.trendSymbol(-0.5f))
        assertEquals("↗", NativeGlucoseNotification.trendSymbol(0.75f))
        assertEquals("↘", NativeGlucoseNotification.trendSymbol(-0.75f))
        assertEquals("↑", NativeGlucoseNotification.trendSymbol(1.5f))
        assertEquals("↓", NativeGlucoseNotification.trendSymbol(-1.5f))
        assertEquals("↗", NativeGlucoseNotification.trendSymbol(1f))
        assertEquals("↑", NativeGlucoseNotification.trendSymbol(2f))
        assertEquals("⇈", NativeGlucoseNotification.trendSymbol(2.01f))
        assertEquals("⇊", NativeGlucoseNotification.trendSymbol(-2.01f))
    }

    @Test fun nativePictureSurvivesParcelWithoutCustomViewsOrFontOverrides() {
        val input = SpannableString("7,0").also {
            it.setSpan(TypefaceSpan("sans-serif"), 0, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val builder = builder().setWhen(123456L).setShowWhen(true).setOnlyAlertOnce(true)
        NativeGlucoseNotification.apply(builder, input, -1f, emptyList(), true,
            "mmol/L", "Connected", NativeGlucoseNotification.chartImage(
                Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888), 16), "Glucose history")
        val parcel = Parcel.obtain()
        try {
            builder.build().writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val notification = Notification.CREATOR.createFromParcel(parcel)
            assertNull(notification.contentView)
            assertNull(notification.bigContentView)
            assertNull(notification.headsUpContentView)
            assertEquals(Notification.BigPictureStyle::class.java.name,
                notification.extras.getString(Notification.EXTRA_TEMPLATE))
            assertEquals("7,0 ↘", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertFalse(notification.extras.getCharSequence(Notification.EXTRA_TITLE) is Spanned)
            assertEquals("mmol/L · Connected", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
            val picture = notification.extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)!!
            assertTrue("chart must remain rectangular after publication; square padding wastes the native slot",
                picture.width.toFloat() / picture.height > 1.7f)
            assertFalse(notification.extras.getBoolean("android.showBigPictureWhenCollapsed"))
            assertEquals(123456L, notification.`when`)
            assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        } finally { parcel.recycle() }
    }

    @Test fun chartDisabledAndHiddenStatusKeepUnitsAndNativeText() {
        val builder = builder()
        NativeGlucoseNotification.apply(builder, "126", Float.NaN, emptyList(), true,
            "mg/dL", "", null, "Glucose history")
        val notification = builder.build()
        assertEquals("126", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("mg/dL", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(Notification.BigTextStyle::class.java.name,
            notification.extras.getString(Notification.EXTRA_TEMPLATE))
        assertNull(notification.extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE))
        assertNull(notification.contentView)
    }

}
