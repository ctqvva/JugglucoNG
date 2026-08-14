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
 * Offered in every form a slot might ask for, because a slot advertises the
 * types it accepts and a provider that does not offer one simply is not listed
 * for it. The large forms carry the trace; SHORT_TEXT and RANGED_VALUE carry the
 * value with a trace-shaped icon, so a slot that takes only those still gets
 * something rather than leaving the app absent from its picker.
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

    private fun text(value: String): PlainComplicationText =
        PlainComplicationText.Builder(text = value).build()

    /**
     * The square trace, value above it.
     *
     * [preview] falls back to a synthesised curve. The picker asks every
     * provider for preview data and drops the ones that answer null, so a watch
     * with no history yet — which is exactly when someone is setting their
     * complications up — would not see this app listed at all.
     */
    private fun chartIcon(width: Int, height: Int, withValue: Boolean, preview: Boolean): Icon? {
        val isMmol = ComplicationRenderer.isMmol()
        val reading = if (preview) {
            GlucoseComplicationData.previewReading()
        } else {
            GlucoseComplicationData.currentReading()
        }
        val bitmap = ComplicationRenderer.sparklineBitmap(
            width = width,
            height = height,
            isMmol = isMmol,
            valueText = reading?.text.takeIf { withValue },
            value = reading?.value ?: Float.NaN,
            // A background image is not cropped to a slot, so it should fill.
            inset = withValue,
            allowSynthetic = preview,
        ) ?: return null
        return Icon.createWithBitmap(bitmap)
    }

    /** The wide form for a long slot, where a square sparkline is a stamp. */
    private fun wideIcon(preview: Boolean): Icon? {
        val isMmol = ComplicationRenderer.isMmol()
        val reading = if (preview) {
            GlucoseComplicationData.previewReading()
        } else {
            GlucoseComplicationData.currentReading()
        } ?: return null
        val bitmap = ComplicationRenderer.wideChartBitmap(
            WIDE_WIDTH, WIDE_HEIGHT, isMmol, reading.text, reading.value, reading.rate,
            allowSynthetic = preview,
        ) ?: return null
        return Icon.createWithBitmap(bitmap)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, preview = true)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(request.complicationType, preview = false)

    private fun build(type: ComplicationType, preview: Boolean): ComplicationData? {
        val tapAction = GlucoseComplicationData.tapAction()
        val reading = if (preview) {
            GlucoseComplicationData.previewReading()
        } else {
            GlucoseComplicationData.currentReading()
        }
        return when (type) {
            ComplicationType.SMALL_IMAGE -> {
                val icon = chartIcon(SMALL_WIDTH, SMALL_HEIGHT, withValue = true, preview = preview)
                    ?: return null
                SmallImageComplicationData.Builder(
                    smallImage = SmallImage.Builder(icon, SmallImageType.PHOTO).build(),
                    contentDescription = description(),
                ).setTapAction(tapAction).build()
            }
            ComplicationType.LONG_TEXT -> {
                // The trace is the image; the title and text carry the same
                // reading so a face that renders text only still says something.
                val builder = LongTextComplicationData.Builder(
                    text = text(reading?.text ?: Applic.app.getString(R.string.novalue)),
                    contentDescription = description(),
                )
                    .setTitle(description())
                    .setTapAction(tapAction)
                wideIcon(preview)?.let {
                    builder.setSmallImage(SmallImage.Builder(it, SmallImageType.PHOTO).build())
                }
                builder.build()
            }
            ComplicationType.PHOTO_IMAGE -> {
                val icon = chartIcon(LARGE_WIDTH, LARGE_HEIGHT, withValue = false, preview = preview)
                    ?: return null
                PhotoImageComplicationData.Builder(
                    photoImage = icon,
                    contentDescription = description(),
                ).setTapAction(tapAction).build()
            }
            // A slot that takes only a text form still gets the value, with the
            // trace as its icon, rather than this app being absent from it.
            ComplicationType.SHORT_TEXT -> GlucoseComplicationData.shortValueData(
                reading,
                "Glucose chart",
                tapAction,
                chartIcon(ICON_PX, ICON_PX, withValue = false, preview = preview),
            )
            ComplicationType.RANGED_VALUE -> GlucoseComplicationData.rangedValueData(
                reading,
                "Glucose chart",
                tapAction,
                chartIcon(ICON_PX, ICON_PX, withValue = false, preview = preview),
            )
            else -> {
                if (!preview) Log.w(LOG_ID, "Unexpected complication type $type")
                null
            }
        }
    }

    companion object {
        private const val LOG_ID = "ChartDataSourceService"
        private const val SMALL_WIDTH = 256
        private const val SMALL_HEIGHT = 256
        private const val LARGE_WIDTH = 450
        private const val LARGE_HEIGHT = 450
        private const val WIDE_WIDTH = 512
        private const val WIDE_HEIGHT = 180
        private const val ICON_PX = 192

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
