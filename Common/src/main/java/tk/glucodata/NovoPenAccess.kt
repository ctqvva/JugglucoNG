package tk.glucodata

import android.nfc.Tag

/**
 * The phone's NFC pen import (direction.md §4, category P).
 *
 * `NovoPen.Scan` reads a Libre pen over NFC and is phone-only; shared `MainActivity`
 * handed it every tag the phone's reader saw. The class stays in `src/mobile` and the
 * tag arrives here instead. The watch has no NFC reader, so it registers nothing and
 * the call site skips (P2) — the stub it had was an empty method.
 */
interface NovoPenScan {
    fun onTag(activity: MainActivity, tag: Tag)
}

object NovoPenAccess {
    @Volatile
    private var scan: NovoPenScan? = null

    @JvmStatic
    fun register(scan: NovoPenScan) {
        this.scan = scan
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = scan != null

    /** Null on a flavour without the pen reader (the watch). */
    @JvmStatic
    fun get(): NovoPenScan? = scan
}
