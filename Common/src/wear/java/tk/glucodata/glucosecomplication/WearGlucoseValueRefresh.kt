package tk.glucodata.glucosecomplication

import tk.glucodata.GlucoseValueRefresh

/**
 * The seam shared code reaches the watch's complication value views through
 * (direction.md §4, category S).
 *
 * The redraw itself is the static [WearComplicationValue.updateall], which wear code also
 * calls directly (GlucoseAlarms, WearColorConfig), so it stays put. This adapter exists
 * because the class cannot implement the interface itself: its instances are sized views
 * (`WearComplicationValue(w, h)`) and shared code never has one, only the redraw.
 */
object WearGlucoseValueRefresh : GlucoseValueRefresh {
    override fun updateAll() {
        WearComplicationValue.updateall()
    }
}
