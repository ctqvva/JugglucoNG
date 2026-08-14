package tk.glucodata.glucosecomplication

import android.app.PendingIntent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ColorRamp
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageType
import tk.glucodata.Applic
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.Natives
import tk.glucodata.Notify
import tk.glucodata.R
import kotlin.math.max
import kotlin.math.min

internal object GlucoseComplicationData {
    data class Reading(
        val value: Float,
        val text: String,
        val isMmol: Boolean,
        val timeMillis: Long,
        val rate: Float,
        val index: Int,
        /** Drawn by the watch face under the value. */
        val sensorId: String? = null,
    )

    private data class Thresholds(
        val low: Float,
        val high: Float,
        val veryLow: Float,
        val veryHigh: Float,
    )

    fun tapAction(): PendingIntent = Notify.mkpending()

    fun currentReading(): Reading? {
        val snapshot = runCatching {
            CurrentDisplaySource.resolveCurrent(Notify.glucosetimeout)
        }.getOrNull() ?: return syncedReading()
        val now = System.currentTimeMillis()
        if (snapshot.timeMillis <= 0L || now - snapshot.timeMillis >= Notify.glucosetimeout) {
            return syncedReading()
        }
        if (!snapshot.primaryValue.isFinite() || snapshot.primaryValue <= 0.0f || snapshot.primaryStr.isBlank()) {
            return syncedReading()
        }
        return Reading(
            value = snapshot.primaryValue,
            text = snapshot.primaryStr,
            isMmol = snapshot.isMmol,
            timeMillis = snapshot.timeMillis,
            rate = snapshot.rate,
            index = snapshot.index,
            sensorId = snapshot.sensorId,
        )
    }

    /**
     * On a companion watch readings arrive over the Data Layer and land in the
     * native store, which the live resolver may not have picked up — the app's own
     * screens read the merged history for the same reason. Without this the watch
     * face and every complication sat blank on a watch that had current data.
     */
    private fun syncedReading(): Reading? {
        val isMmol = runCatching { Applic.unit == 1 }.getOrDefault(false)
        val newest = runCatching {
            val sensor = tk.glucodata.NotificationHistorySource.resolveSensorSerial()
            tk.glucodata.NotificationHistorySource
                .getDisplayHistory(System.currentTimeMillis() - Notify.glucosetimeout, isMmol, sensor)
                .lastOrNull()
        }.getOrNull() ?: return null
        if (!newest.value.isFinite() || newest.value <= 0f) return null
        if (System.currentTimeMillis() - newest.timestamp >= Notify.glucosetimeout) return null
        val rate = runCatching {
            tk.glucodata.TrendAccess.calculateVelocity(
                tk.glucodata.NotificationHistorySource.getDisplayHistory(
                    newest.timestamp - 35 * 60_000L,
                    isMmol,
                    tk.glucodata.NotificationHistorySource.resolveSensorSerial(),
                ),
                false,
                isMmol,
            )
        }.getOrNull()?.takeIf { it.isFinite() } ?: 0f
        return Reading(
            value = newest.value,
            text = tk.glucodata.ui.util.GlucoseFormatter.format(newest.value, isMmol),
            isMmol = isMmol,
            timeMillis = newest.timestamp,
            rate = rate,
            index = 0,
            sensorId = runCatching {
                tk.glucodata.NotificationHistorySource.resolveSensorSerial()
            }.getOrNull(),
        )
    }

    fun previewReading(): Reading {
        currentReading()?.let { return it }
        val isMmol = Applic.unit == 1
        val valueText = if (isMmol) "5.6" else "101"
        return Reading(
            value = if (isMmol) 5.6f else 101.0f,
            text = valueText,
            isMmol = isMmol,
            timeMillis = System.currentTimeMillis(),
            rate = 1.0f,
            index = 0,
        )
    }

    /** The reading's own time, for the forms that show when it was taken. */
    fun readingTimeText(reading: Reading?): String? {
        val at = reading?.timeMillis?.takeIf { it > 0L } ?: return null
        return runCatching {
            android.text.format.DateFormat.getTimeFormat(Applic.app).format(java.util.Date(at))
        }.getOrNull()
    }

    fun shortValueData(
        reading: Reading?,
        contentDescription: String,
        tapAction: PendingIntent = tapAction(),
        icon: Icon? = null,
        /** Shown above the value by faces that render a title. */
        title: String? = null,
    ): ShortTextComplicationData {
        val builder = ShortTextComplicationData.Builder(
            text = text(reading?.text ?: noValueText()),
            contentDescription = text(contentDescription),
        ).setTapAction(tapAction)
        title?.let { builder.setTitle(text(it)) }
        if (reading != null && icon != null) {
            builder.setSmallImage(SmallImage.Builder(icon, SmallImageType.PHOTO).build())
            builder.setMonochromaticImage(MonochromaticImage.Builder(icon).build())
        }
        return builder.build()
    }

    /**
     * The long form every provider offers.
     *
     * A slot lists only the providers that support a type it accepts, so a
     * provider missing LONG_TEXT simply does not appear in a long slot's picker
     * — which is why none of these showed up for the wide slot on the dial.
     */
    fun longTextData(
        reading: Reading?,
        contentDescription: String,
        tapAction: PendingIntent = tapAction(),
        icon: Icon? = null,
        title: String? = null,
    ): LongTextComplicationData {
        val builder = LongTextComplicationData.Builder(
            text = text(reading?.text ?: noValueText()),
            contentDescription = text(contentDescription),
        ).setTapAction(tapAction)
        title?.let { builder.setTitle(text(it)) }
        icon?.let {
            builder.setSmallImage(SmallImage.Builder(it, SmallImageType.PHOTO).build())
            builder.setMonochromaticImage(MonochromaticImage.Builder(it).build())
        }
        return builder.build()
    }

    fun rangedValueData(
        reading: Reading?,
        contentDescription: String,
        tapAction: PendingIntent = tapAction(),
        icon: Icon? = null,
    ): RangedValueComplicationData {
        val builder = if (reading == null || !reading.value.isFinite() || reading.value <= 0.0f) {
            RangedValueComplicationData.Builder(
                value = 0.0f,
                min = 0.0f,
                max = 1.0f,
                contentDescription = text(noValueText()),
            ).setText(text(noValueText()))
        } else {
            val thresholds = thresholds(reading.isMmol)
            RangedValueComplicationData.Builder(
                value = reading.value.coerceIn(thresholds.veryLow, thresholds.veryHigh),
                min = thresholds.veryLow,
                max = thresholds.veryHigh,
                contentDescription = text(contentDescription),
            )
                .setText(text(reading.text))
                .setColorRamp(
                    ColorRamp(
                        intArrayOf(
                            GlucoseRangeColors.veryLow(true),
                            GlucoseRangeColors.low(true),
                            GlucoseRangeColors.inRange(true),
                            GlucoseRangeColors.high(true),
                            GlucoseRangeColors.veryHigh(true),
                        ),
                        true,
                    ),
                )
        }
        if (reading != null && icon != null) {
            builder.setSmallImage(SmallImage.Builder(icon, SmallImageType.PHOTO).build())
            builder.setMonochromaticImage(MonochromaticImage.Builder(icon).build())
        }
        return builder.setTapAction(tapAction).build()
    }

    private fun thresholds(isMmol: Boolean): Thresholds {
        val defaultLow = GlucoseRangeColors.defaultLow(isMmol)
        val defaultHigh = GlucoseRangeColors.defaultHigh(isMmol)
        val defaultVeryLow = GlucoseRangeColors.defaultVeryLow(isMmol)
        val defaultVeryHigh = GlucoseRangeColors.defaultVeryHigh(isMmol)
        var low = defaultLow
        var high = defaultHigh
        var veryLow = defaultVeryLow
        var veryHigh = defaultVeryHigh
        runCatching {
            Natives.targetlow().takeIf { it.isFinite() && it > 0.0f }?.let { low = it }
            Natives.targethigh().takeIf { it.isFinite() && it > 0.0f }?.let { high = it }
            Natives.alarmverylow().takeIf { it.isFinite() && it > 0.0f }?.let { veryLow = it }
            Natives.alarmveryhigh().takeIf { it.isFinite() && it > 0.0f }?.let { veryHigh = it }
        }
        if (high <= low) {
            high = max(defaultHigh, low + 0.1f)
        }
        if (veryLow >= low) {
            veryLow = min(defaultVeryLow, low - 0.1f)
        }
        if (veryHigh <= high) {
            veryHigh = max(defaultVeryHigh, high + 0.1f)
        }
        if (veryHigh <= veryLow) {
            veryHigh = veryLow + 0.1f
        }
        return Thresholds(
            low = low,
            high = high,
            veryLow = veryLow,
            veryHigh = veryHigh,
        )
    }

    private fun text(value: String): ComplicationText =
        PlainComplicationText.Builder(text = value).build()

    private fun noValueText(): String = Applic.app.getString(R.string.novalue)
}
