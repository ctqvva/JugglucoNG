package tk.glucodata.ui

import android.view.View
import androidx.appcompat.app.AppCompatActivity

/**
 * The variant's Compose entry point (plan P1/Q1, category R).
 *
 * MainActivity used to look up `tk.glucodata.ui.ComposeHostKt#setComposeContent`
 * by name; R8 renames the file class, so that lookup only ever worked by luck.
 * Each flavour registers its implementation from `Specific.registerBridges`; the
 * legacy `small` flavour registers nothing and keeps the native View UI (P2).
 */
fun interface ComposeHost {
    fun setComposeContent(activity: AppCompatActivity, legacyView: View?)
}
