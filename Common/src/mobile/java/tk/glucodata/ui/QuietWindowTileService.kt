package tk.glucodata.ui

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.format.DateFormat
import java.util.Date
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.alerts.QuietWindow

/**
 * The quick-settings tile for the quiet window, labelled as the glucose alarms
 * with their state in the status row: one tap starts the default duration, one
 * tap on a running window ends it, a long press opens the alert
 * settings (MainActivity handles QS_TILE_PREFERENCES). The tile never caches: it
 * reads the persisted end time against the clock every time it is shown, so it
 * is right after a process death too. It only ever toggles the window, never the
 * alarms themselves; very-low alarms stay untouched either way.
 */
class QuietWindowTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        try {
            if (QuietWindow.untilMs(System.currentTimeMillis()) > 0L) {
                QuietWindow.end(this)
            } else {
                QuietWindow.startDefault(this)
            }
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "onClick", t)
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val until = QuietWindow.untilMs(System.currentTimeMillis())
        val active = until > 0L
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(
            this,
            if (active) R.drawable.ic_quiet_window_active else R.drawable.ic_quiet_window_inactive
        )
        tile.label = getString(R.string.quiet_window_tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The label names the thing (the glucose alarms); the status row says what
            // state they are in, so the tile reads at a glance in the shade.
            tile.subtitle = if (active) {
                getString(R.string.quiet_window_tile_silent_until, DateFormat.getTimeFormat(this).format(Date(until)))
            } else {
                getString(R.string.quiet_window_tile_active)
            }
        }
        tile.updateTile()
    }

    private companion object {
        const val LOG_ID = "QuietWindowTile"
    }
}
