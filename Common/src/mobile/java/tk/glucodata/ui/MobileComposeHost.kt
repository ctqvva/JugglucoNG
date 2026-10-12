package tk.glucodata.ui

import android.content.Context
import android.view.View
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Mobile Compose entry point (plan P1/Q1, category R). Registered from the
 * mobile `Specific.registerBridges`; MainActivity calls it through [ComposeHost]
 * instead of resolving `ComposeHostKt` by name.
 */
object MobileComposeHost : ComposeHost {
    override fun setComposeContent(activity: AppCompatActivity, legacyView: View?) {
        // Hide the native legacy view (histogram/nanovg) to prevent double-rendering,
        // GPU overdraw, and visual glitches (bleeding through navbar).
        legacyView?.visibility = View.GONE
        activity.window.decorView.optOutOfForceDark()

        activity.setContent {
            val prefs = activity.getSharedPreferences(activity.packageName + "_preferences", Context.MODE_PRIVATE)
            val savedTheme = prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM"
            var themeMode by remember { mutableStateOf(ThemeMode.valueOf(savedTheme)) }

            JugglucoTheme(themeMode = themeMode) {
                MainApp(
                    themeMode = themeMode,
                    onThemeChanged = { newMode ->
                        themeMode = newMode
                        prefs.edit().putString("theme_mode", newMode.name).apply()
                    }
                )
            }
        }
    }
}
