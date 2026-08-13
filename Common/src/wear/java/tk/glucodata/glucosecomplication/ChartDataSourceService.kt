package tk.glucodata.glucosecomplication

import android.content.ComponentName
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.R

/**
 * A sparkline of the last few hours, which the app had no complication for at
 * all — the watch face could show a number and an arrow but never the shape,
 * which is most of what a glance at a CGM is for.
 *
 * Offered as both SMALL_IMAGE, for a slot beside other complications, and
 * PHOTO_IMAGE, which watch faces use as a background.
 */
class ChartDataSourceService : SuspendingComplicationDataSourceService() {

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        Log.d(LOG_ID, "onComplicationActivated(): $complicationInstanceId")
    }

    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        Log.d(LOG_ID, "onComplicationDeactivated(): $complicationInstanceId")
    }

    private fun description(): PlainComplicationText =
        PlainComplicationText.Builder(
            text = runCatching { Applic.app.getString(R.string.wear_chart_complication) }
                .getOrDefault("Glucose chart"),
        ).build()

    /**
     * The value sits above the trace, not on it: overlaid text at complication
     * size buries the shape it is there to accompany.
     */
    private fun chartIcon(width: Int, height: Int, withValue: Boolean): Icon? {
        val isMmol = ComplicationRenderer.isMmol()
        val reading = GlucoseComplicationData.currentReading()
        val bitmap = ComplicationRenderer.sparklineBitmap(
            width = width,
            height = height,
            isMmol = isMmol,
            valueText = reading?.text.takeIf { withValue },
            value = reading?.value ?: Float.NaN,
            // A background image is not cropped to a slot, so it should fill.
            inset = withValue,
        ) ?: return null
        return Icon.createWithBitmap(bitmap)
    }

    /** The wide form for a long slot, where a square sparkline is a stamp. */
    private fun wideIcon(): Icon? {
        val isMmol = ComplicationRenderer.isMmol()
        val reading = GlucoseComplicationData.currentReading() ?: return null
        val bitmap = ComplicationRenderer.wideChartBitmap(
            WIDE_WIDTH, WIDE_HEIGHT, isMmol, reading.text, reading.value, reading.rate,
        ) ?: return null
        return Icon.createWithBitmap(bitmap)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, preview = true)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(request.complicationType, preview = false)

    private fun build(type: ComplicationType, preview: Boolean): ComplicationData? {
        val tapAction = GlucoseComplicationData.tapAction()
        return when (type) {
            ComplicationType.SMALL_IMAGE -> {
                val icon = chartIcon(SMALL_WIDTH, SMALL_HEIGHT, withValue = true) ?: return null
                SmallImageComplicationData.Builder(
                    smallImage = SmallImage.Builder(icon, SmallImageType.PHOTO).build(),
                    contentDescription = description(),
                ).setTapAction(tapAction).build()
            }
            ComplicationType.LONG_TEXT -> {
                // The value and arrow are the image; the text fields carry the
                // same reading so a face that hides images still says something.
                val reading = GlucoseComplicationData.currentReading()
                val icon = wideIcon()
                val builder = LongTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(
                        text = reading?.text ?: Applic.app.getString(R.string.novalue),
                    ).build(),
                    contentDescription = description(),
                ).setTapAction(tapAction)
                icon?.let {
                    builder.setSmallImage(SmallImage.Builder(it, SmallImageType.PHOTO).build())
                }
                builder.build()
            }
            ComplicationType.PHOTO_IMAGE -> {
                val icon = chartIcon(LARGE_WIDTH, LARGE_HEIGHT, withValue = false) ?: return null
                PhotoImageComplicationData.Builder(
                    photoImage = icon,
                    contentDescription = description(),
                ).setTapAction(tapAction).build()
            }
            else -> {
                if (!preview) Log.w(LOG_ID, "Unexpected complication type $type")
                null
            }
        }
    }

    companion object {
        private const val LOG_ID = "ChartDataSourceService"
        private const val SMALL_WIDTH = 200
        private const val SMALL_HEIGHT = 200
        private const val LARGE_WIDTH = 450
        private const val LARGE_HEIGHT = 450
        private const val WIDE_WIDTH = 480
        private const val WIDE_HEIGHT = 160

        private val updateRequester by lazy {
            ComplicationDataSourceUpdateRequester.create(
                context = Applic.app,
                complicationDataSourceComponent = ComponentName(Applic.app, ChartDataSourceService::class.java),
            )
        }

        @JvmStatic
        fun update() {
            runCatching { updateRequester.requestUpdateAll() }
        }
    }
}
