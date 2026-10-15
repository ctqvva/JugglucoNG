package tk.glucodata

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.chart.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationUncertaintyRibbonTests {
    @Test fun composeAndBitmapRibbonsPaintTheSameIntervalsAndGaps() {
        val bitmap = Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888)
        val points = (1..8).map { minute -> ChartPointModel(minute * 60_000L, 6f, "SIBI:A",
            uncertainty = if (minute == 4 || minute == 5) null else GlucoseUncertainty(4f, 8f)) }
        androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
            androidx.compose.ui.unit.Density(1f), androidx.compose.ui.unit.LayoutDirection.Ltr,
            androidx.compose.ui.graphics.Canvas(bitmap.asImageBitmap()),
            androidx.compose.ui.geometry.Size(320f, 160f),
        ) {
            with(tk.glucodata.ui.GlucoseUncertaintyRibbon) {
                drawUncertaintyRibbon(points, 0, points.size, 0L, 320f / 600_000f,
                    160f, 0f, 16f, GlucoseChartGap.THRESHOLD_MS, androidx.compose.ui.graphics.Color.White)
            }
        }
        assertTrue(Color.alpha(bitmap.getPixel(64, 80)) > 0)
        assertEquals(0, Color.alpha(bitmap.getPixel(144, 80)))
        assertTrue(Color.alpha(bitmap.getPixel(224, 80)) > 0)
    }

    @Test fun renderedForecastKeepsBothEdgesOfTheSensorRibbonAtTheJoin() {
        val bitmap = Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val points = (1..5).map { minute -> ChartPointModel(minute * 60_000L, 6f, "X-A",
            uncertainty = GlucoseUncertainty(4f, 8f)) }
        NotificationChartDrawer.drawUncertaintyRibbon(canvas, points,
            0L, 10 * 60_000L, 0f, 240f, 480f, 240f, 0f, 10f, Color.WHITE)
        val forecast = (5..10).map { minute -> NotificationPredictionPoint(minute * 60_000L,
            6f + (minute - 5) * 0.4f, if (minute == 5) 1f else 0.7f) }
        val band = PredictionBand(6f, 1f, GlucoseUncertainty(4f, 8f), 5 * 60_000L)
        NotificationChartDrawer.drawPredictionSeries(canvas, Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f },
            RuntimeEnvironment.getApplication().resources.displayMetrics,
            NotificationPredictionSeries(NotificationPredictionSeries.KIND_AUTO, forecast, band),
            true, 0, false, Color.WHITE, Color.GRAY, Color.GRAY,
            0L, 10 * 60_000L, 0f, 240f, 480f, 240f, 0f, 10f)
        // Near the lower edge, far outside the old narrow forecast band.
        // Tied to the band's own opacity, not to one particular tuning of it.
        val visible = (UncertaintyBands.FILL_ALPHA * 255f * 0.8f).toInt()
        assertTrue("Historical edge remains visible", Color.alpha(bitmap.getPixel(238, 140)) >= visible)
        assertTrue("Forecast inherits the edge's opacity", Color.alpha(bitmap.getPixel(242, 140)) >= visible)
        val output = System.getProperty("juggluco.chartQaDir") ?: System.getenv("JUGGLUCO_CHART_QA_DIR")
        if (output != null) File(output).apply { mkdirs() }.resolve("forecast-join.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun bitmapRibbonFillsBothBoundsButLeavesMissingMinutesEmpty() {
        val bitmap = Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888)
        val points = (1..8).map { minute -> ChartPointModel(minute * 60_000L, 6f, "X-A",
            uncertainty = if (minute == 4 || minute == 5) null else GlucoseUncertainty(4f, 8f)) }
        NotificationChartDrawer.drawUncertaintyRibbon(Canvas(bitmap), points,
            0L, 10 * 60_000L, 0f, 160f, 320f, 160f, 0f, 10f, Color.WHITE)
        assertTrue(Color.alpha(bitmap.getPixel(64, 80)) > 0)
        assertEquals(0, Color.alpha(bitmap.getPixel(144, 80)))
        assertTrue(Color.alpha(bitmap.getPixel(224, 80)) > 0)
        assertEquals(0, Color.alpha(bitmap.getPixel(64, 150)))
    }

    @Test fun notificationAndAodBitmapSizesShowEitherAlgorithmRibbonAndHonorTheToggle() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", 0)
        prefs.edit().putBoolean("dashboard_predictive_simulation_enabled", false).commit()
        val now = System.currentTimeMillis()
        for (sensor in listOf("X-AIDEX", "SIBI:SENSOR")) {
            val points = (0..60).map { minute -> GlucosePoint(now - (60 - minute) * 60_000L, 6f, 5.8f).also {
                it.sensorSerial = sensor; it.uncertainty = GlucoseUncertainty(4.8f, 7.4f)
            } }
            val model = HistoryChartModelBuilder.build(
                listOf(HistoryChartModelBuilder.SeriesInput(sensor, true, 0, Color.WHITE, points)),
                MainSensorOwnership.NONE, HistoryChartModelBuilder.Calibration { _, _, _, _ -> null })
            for ((width, height) in listOf(480 to 96, 480 to 256, 360 to 160)) {
                fun draw(enabled: Boolean): Bitmap {
                    prefs.edit().putBoolean(UncertaintyBands.PREFERENCE_KEY, enabled).commit()
                    return NotificationChartDrawer.drawChartWithPrediction(context, points, width, height,
                        true, 0, true, false, height == 96, sensor, emptyList(), model)
                }
                val off = draw(false)
                val on = draw(true)
                assertFalse("$sensor $width x $height ribbon must change pixels", on.sameAs(off))
                val output = System.getProperty("juggluco.chartQaDir") ?: System.getenv("JUGGLUCO_CHART_QA_DIR")
                if (output != null) File(output).apply { mkdirs() }.resolve("${sensor.replace(':', '-')}-${width}x$height.png")
                    .outputStream().use { on.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
