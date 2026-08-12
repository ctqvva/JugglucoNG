package tk.glucodata.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.GlucoseRangeColors.Band
import tk.glucodata.GlucoseRangeColors.Palette

/**
 * Compose-facing bridge over the static [GlucoseRangeColors] palette state.
 *
 * The colour getters in [GlucoseRangeColors] are plain statics, so Compose does
 * not observe them: without help, a palette switch would only show up after an
 * app restart. This object exposes a [revision] snapshot-int that is bumped on
 * every palette/override change. Composables that read [revision] before
 * resolving a band colour (typically as a `remember` key) recompose live when
 * the user changes the palette — no restart needed.
 *
 * Non-Compose consumers ([tk.glucodata.NotificationChartDrawer]) keep reading
 * the static getters directly; they pick up changes the next time they draw.
 */
object GlucosePaletteState {
    private var revisionState by mutableIntStateOf(0)

    init {
        // Any palette/override change funnels through here into Compose.
        GlucoseRangeColors.setChangeListener { revisionState++ }
    }

    /** Read this in a composable (e.g. as a remember key) to observe changes. */
    val revision: Int get() = revisionState

    fun palette(): Palette = GlucoseRangeColors.getPalette()

    fun override(band: Band): Int? = GlucoseRangeColors.getOverride(band)

    fun targetBackgroundOverride(): Int? = GlucoseRangeColors.getTargetBackgroundOverride()

    fun hasAnyOverride(): Boolean = GlucoseRangeColors.hasAnyOverride()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(
            GlucoseRangeColors.PREF_FILE, Context.MODE_PRIVATE
        )

    fun setPalette(context: Context, palette: Palette) {
        prefs(context).edit { putString(GlucoseRangeColors.PREF_PALETTE, palette.name) }
        GlucoseRangeColors.setPalette(palette)
        refreshNotification()
    }

    /** Set (argb) or clear (null) a single band override; persists and applies. */
    fun setOverride(context: Context, band: Band, argb: Int?) {
        val key = GlucoseRangeColors.PREF_OVERRIDE_KEYS[band.ordinal]
        prefs(context).edit {
            if (argb == null) remove(key) else putInt(key, argb)
        }
        GlucoseRangeColors.setOverride(band, argb)
        refreshNotification()
    }

    fun setTargetBackgroundOverride(context: Context, argb: Int?) {
        prefs(context).edit {
            if (argb == null) {
                remove(GlucoseRangeColors.PREF_TARGET_BACKGROUND)
            } else {
                putInt(GlucoseRangeColors.PREF_TARGET_BACKGROUND, argb)
            }
        }
        GlucoseRangeColors.setTargetBackgroundOverride(argb)
        refreshNotification()
    }

    fun clearOverrides(context: Context) {
        prefs(context).edit {
            GlucoseRangeColors.PREF_OVERRIDE_KEYS.forEach { remove(it) }
            remove(GlucoseRangeColors.PREF_TARGET_BACKGROUND)
        }
        GlucoseRangeColors.clearOverrides()
        refreshNotification()
    }

    // Compose recomposes on its own via [revision], but the ongoing notification
    // is a rendered bitmap that only refreshes on a new reading. Repaint it now
    // so a palette change is visible there immediately, not minutes later.
    // The watch mirrors the same scheme, and only learns of a change when told.
    private fun refreshNotification() {
        try {
            tk.glucodata.Notify.showoldglucose()
        } catch (_: Throwable) {
        }
        tk.glucodata.GlucoseColorSync.push()
    }
}
