package tk.glucodata.tile

import android.content.ComponentName
import android.graphics.Bitmap
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.MainActivity
import tk.glucodata.R
import tk.glucodata.glucosecomplication.ComplicationRenderer
import tk.glucodata.glucosecomplication.GlucoseComplicationData

/**
 * The current glucose on a tile: value, trend arrow, reading time and the recent trace, drawn
 * by the same renderer as the chart complications. Tapping it opens the app.
 *
 * Tiles are laid out by the system, which can only show what a layout describes, so the
 * picture is rendered here and handed over as one image resource. Rendering reads history
 * through the native store, so both callbacks do it off the main thread.
 */
class GlucoseTileService : TileService() {

    /** One rendering: the picture's PNG, or null when there is no reading to show. */
    private class Frame(
        val version: String,
        val png: ByteArray?,
        val widthPx: Int,
        val heightPx: Int,
    )

    @Volatile
    private var lastFrame: Frame? = null

    private val blankPng: ByteArray by lazy {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).toPng()
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val device = requestParams.deviceConfiguration
        return background("tile") {
            val frame = render(device.screenWidthDp, device.screenHeightDp, device.screenDensity)
            lastFrame = frame
            TileBuilders.Tile.Builder()
                .setResourcesVersion(frame.version)
                .setFreshnessIntervalMillis(FRESHNESS_MS)
                .setTileTimeline(
                    TimelineBuilders.Timeline.fromLayoutElement(layout(frame, device.screenDensity)),
                )
                .build()
        }
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> {
        val device = requestParams.deviceConfiguration
        return background("resources") {
            // Answer for the version the system asked about, not whichever reading is current:
            // a saved tile is paired with its own resources version, and a service the system
            // recreated has no frame left, so the picture is redrawn from current data and
            // handed back under the requested version.
            val version = requestParams.version
            val frame = lastFrame?.takeIf { it.version == version }
                ?: render(device.screenWidthDp, device.screenHeightDp, device.screenDensity)
            // Always name the image: a layout saved while a reading was shown still points at
            // it, and the reading may have expired since. A blank one stands in then.
            val png = frame.png ?: blankPng
            val widthPx = if (frame.png == null) 1 else frame.widthPx
            val heightPx = if (frame.png == null) 1 else frame.heightPx
            ResourceBuilders.Resources.Builder()
                .setVersion(version)
                .addIdToImageMapping(
                    IMAGE_ID,
                    ResourceBuilders.ImageResource.Builder()
                        .setInlineResource(
                            ResourceBuilders.InlineImageResource.Builder()
                                .setData(png)
                                .setWidthPx(widthPx)
                                .setHeightPx(heightPx)
                                .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                                .build(),
                        )
                        .build(),
                )
                .build()
        }
    }

    private fun render(screenWidthDp: Int, screenHeightDp: Int, density: Float): Frame {
        val now = System.currentTimeMillis()
        val isMmol = ComplicationRenderer.isMmol()
        val size = GlucoseTileFrame.chartSize(screenWidthDp, screenHeightDp, density)
        val reading = runCatching { GlucoseComplicationData.currentReading() }.getOrNull()
        val version = GlucoseTileFrame.version(
            size, reading?.text, reading?.timeMillis ?: 0L, reading?.rate ?: Float.NaN, isMmol, now,
        )
        if (reading == null) return Frame(version, null, 0, 0)
        val bitmap = runCatching {
            ComplicationRenderer.chartBitmap(
                width = size.widthPx,
                height = size.heightPx,
                isMmol = isMmol,
                valueText = reading.text,
                value = reading.value,
                rate = reading.rate,
                timeMillis = reading.timeMillis,
                showArrow = true,
                showTime = true,
                inset = false,
            ) ?: ComplicationRenderer.valueWithArrowBitmap(
                GlucoseTileFrame.valueSide(size), reading.text, reading.value, reading.rate, isMmol,
            )
        }.onFailure { Log.stack(LOG_ID, "render", it) }.getOrNull()
            ?: return Frame(version, null, 0, 0)
        return Frame(version, bitmap.toPng(), bitmap.width, bitmap.height)
    }

    private fun layout(frame: Frame, density: Float): LayoutElementBuilders.LayoutElement {
        val open = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(ActionBuilders.launchAction(ComponentName(this, MainActivity::class.java)))
            .build()
        val content: LayoutElementBuilders.LayoutElement = if (frame.png == null) {
            LayoutElementBuilders.Text.Builder()
                .setText(getString(R.string.novalue))
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(sp(20f))
                        .setColor(argb(TEXT_COLOR))
                        .build(),
                )
                .build()
        } else {
            LayoutElementBuilders.Image.Builder()
                .setResourceId(IMAGE_ID)
                .setWidth(dp(GlucoseTileFrame.dp(frame.widthPx, density)))
                .setHeight(dp(GlucoseTileFrame.dp(frame.heightPx, density)))
                .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_FIT)
                .build()
        }
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
            .addContent(content)
            .build()
    }

    private fun Bitmap.toPng(): ByteArray =
        ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    private fun <T> background(tag: String, work: () -> T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            worker.execute {
                try {
                    completer.set(work())
                } catch (t: Throwable) {
                    Log.stack(LOG_ID, tag, t)
                    completer.setException(t)
                }
            }
            tag
        }

    companion object {
        private const val LOG_ID = "GlucoseTileService"
        private const val IMAGE_ID = "glucose"
        private const val TEXT_COLOR = 0xFFE3E2DE.toInt()

        /** A new reading arrives about every minute; the tile is re-asked at that pace too. */
        private const val FRESHNESS_MS = 60_000L

        private val worker = Executors.newSingleThreadExecutor()

        /** Redraw an added tile after a new reading, like the complications. */
        fun update() {
            runCatching {
                getUpdater(Applic.app).requestUpdate(GlucoseTileService::class.java)
            }
        }
    }
}
