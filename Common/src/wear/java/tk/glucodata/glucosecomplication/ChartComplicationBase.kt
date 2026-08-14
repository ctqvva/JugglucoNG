package tk.glucodata.glucosecomplication

import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.R

/**
 * The glucose trace as a complication, in the four combinations that are
 * actually useful on a dial: the trace alone, and the trace with a value, with
 * a value and arrow, or with all three plus the reading's time.
 *
 * They are separate providers rather than one with options because the picker
 * is where a user chooses: a single entry would make the choice a settings
 * screen nobody opens, and a slot only shows what it can render anyway.
 *
 * Image types come first in every manifest entry. A slot picks the first type
 * it supports from the provider's list, and putting a text type earlier meant a
 * round slot rendered the trace as a thumbnail icon beside a number instead of
 * drawing the trace.
 */
abstract class ChartComplicationBase : SuspendingComplicationDataSourceService() {

    protected abstract val showValue: Boolean
    protected abstract val showArrow: Boolean
    protected abstract val showTime: Boolean
    protected abstract val logId: String

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        Log.d(logId, "onComplicationActivated(): $complicationInstanceId")
    }

    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        Log.d(logId, "onComplicationDeactivated(): $complicationInstanceId")
    }

    private fun description(): PlainComplicationText =
        PlainComplicationText.Builder(
            text = runCatching { Applic.app.getString(R.string.wear_chart_complication) }
                .getOrDefault("Glucose chart"),
        ).build()

    /**
     * [preview] lets the trace fall back to a synthesised curve: the picker asks
     * for preview data before a watch necessarily has history, and a provider
     * that answers null there is dropped from the list entirely.
     */
    private fun chartIcon(width: Int, height: Int, fill: Boolean, preview: Boolean): Icon? {
        val reading = if (preview) {
            GlucoseComplicationData.previewReading()
        } else {
            GlucoseComplicationData.currentReading()
        }
        val bitmap = ComplicationRenderer.chartBitmap(
            width = width,
            height = height,
            isMmol = ComplicationRenderer.isMmol(),
            valueText = reading?.text.takeIf { showValue },
            value = reading?.value ?: Float.NaN,
            rate = reading?.rate ?: Float.NaN,
            timeMillis = reading?.timeMillis ?: 0L,
            showArrow = showArrow && reading != null,
            showTime = showTime && reading != null,
            // A background image is not cropped to a slot, so it fills.
            inset = !fill,
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
            ComplicationType.PHOTO_IMAGE -> {
                val icon = chartIcon(LARGE_PX, LARGE_PX, fill = true, preview = preview) ?: return null
                PhotoImageComplicationData.Builder(
                    photoImage = icon,
                    contentDescription = description(),
                ).setTapAction(tapAction).build()
            }
            ComplicationType.SMALL_IMAGE -> {
                val icon = chartIcon(SMALL_PX, SMALL_PX, fill = false, preview = preview) ?: return null
                SmallImageComplicationData.Builder(
                    smallImage = SmallImage.Builder(icon, SmallImageType.PHOTO).build(),
                    contentDescription = description(),
                ).setTapAction(tapAction).build()
            }
            ComplicationType.LONG_TEXT -> GlucoseComplicationData.longTextData(
                reading,
                "Glucose chart",
                tapAction,
                chartIcon(WIDE_W, WIDE_H, fill = false, preview = preview),
                title = GlucoseComplicationData.readingTimeText(reading).takeIf { showTime },
            )
            else -> {
                if (!preview) Log.w(logId, "Unexpected complication type $type")
                null
            }
        }
    }

    companion object {
        private const val LARGE_PX = 450
        private const val SMALL_PX = 256
        private const val WIDE_W = 512
        private const val WIDE_H = 180
    }
}

/** Trace only. */
class ChartDataSourceService : ChartComplicationBase() {
    override val showValue = false
    override val showArrow = false
    override val showTime = false
    override val logId = "ChartComplication"

    companion object {
        private val updateRequester by lazy {
            androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester.create(
                context = Applic.app,
                complicationDataSourceComponent = android.content.ComponentName(
                    Applic.app, ChartDataSourceService::class.java,
                ),
            )
        }

        @JvmStatic
        fun update() {
            runCatching { updateRequester.requestUpdateAll() }
            ChartValueDataSourceService.update()
            ChartValueArrowDataSourceService.update()
            ChartFullDataSourceService.update()
        }
    }
}

/** Trace with the value over it. */
class ChartValueDataSourceService : ChartComplicationBase() {
    override val showValue = true
    override val showArrow = false
    override val showTime = false
    override val logId = "ChartValueComplication"

    companion object {
        private val updateRequester by lazy {
            androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester.create(
                context = Applic.app,
                complicationDataSourceComponent = android.content.ComponentName(
                    Applic.app, ChartValueDataSourceService::class.java,
                ),
            )
        }

        @JvmStatic
        fun update() {
            runCatching { updateRequester.requestUpdateAll() }
        }
    }
}

/** Trace with the value and the trend arrow. */
class ChartValueArrowDataSourceService : ChartComplicationBase() {
    override val showValue = true
    override val showArrow = true
    override val showTime = false
    override val logId = "ChartValueArrowComplication"

    companion object {
        private val updateRequester by lazy {
            androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester.create(
                context = Applic.app,
                complicationDataSourceComponent = android.content.ComponentName(
                    Applic.app, ChartValueArrowDataSourceService::class.java,
                ),
            )
        }

        @JvmStatic
        fun update() {
            runCatching { updateRequester.requestUpdateAll() }
        }
    }
}

/** Trace with the value, the arrow and the reading's time. */
class ChartFullDataSourceService : ChartComplicationBase() {
    override val showValue = true
    override val showArrow = true
    override val showTime = true
    override val logId = "ChartFullComplication"

    companion object {
        private val updateRequester by lazy {
            androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester.create(
                context = Applic.app,
                complicationDataSourceComponent = android.content.ComponentName(
                    Applic.app, ChartFullDataSourceService::class.java,
                ),
            )
        }

        @JvmStatic
        fun update() {
            runCatching { updateRequester.requestUpdateAll() }
        }
    }
}
