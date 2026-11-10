package tk.glucodata.ui

import android.view.View
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import tk.glucodata.ui.theme.WearJugglucoTheme

/**
 * Wear Compose entry point (plan P1/Q1, category R). Registered from the wear
 * `Specific.registerBridges`; MainActivity calls it through [ComposeHost]
 * instead of resolving `ComposeHostKt` by name.
 */
object WearComposeHost : ComposeHost {
    override fun setComposeContent(activity: AppCompatActivity, legacyView: View?) {
        legacyView?.visibility = View.GONE

        activity.setContent {
            WearJugglucoTheme {
                WearApp()
            }
        }
    }
}
