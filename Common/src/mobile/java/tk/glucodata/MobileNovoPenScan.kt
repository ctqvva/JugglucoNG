package tk.glucodata

import android.nfc.Tag
import tk.glucodata.NovoPen.Scan

/**
 * The phone's NFC pen adapter for [NovoPenAccess] (direction.md §4, category P). The
 * whole `NovoPen` package stays in this source set; only the tag hand-off is shared.
 */
object MobileNovoPenScan : NovoPenScan {
    override fun onTag(activity: MainActivity, tag: Tag) {
        Scan.onTag(activity, tag)
    }
}
