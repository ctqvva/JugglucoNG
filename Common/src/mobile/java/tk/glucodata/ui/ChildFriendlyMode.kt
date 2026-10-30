package tk.glucodata.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

internal object ChildFriendlyMode {
    private const val PREFERENCES_NAME = "tk.glucodata_preferences"
    const val PREFERENCE_KEY = "child_friendly_mode"
    var revision by mutableIntStateOf(0)
        private set

    fun isEnabled(context: Context): Boolean =
        preferences(context).getBoolean(PREFERENCE_KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(PREFERENCE_KEY, enabled).apply()
    }

    fun refresh() {
        revision++
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}

@Composable
internal fun rememberChildFriendlyModeEnabled(): Boolean {
    val context = LocalContext.current.applicationContext
    val revision = ChildFriendlyMode.revision
    val enabled by remember(context, revision) {
        mutableStateOf(ChildFriendlyMode.isEnabled(context))
    }

    DisposableEffect(context) {
        val preferences = context.getSharedPreferences(
            "tk.glucodata_preferences",
            Context.MODE_PRIVATE,
        )
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == ChildFriendlyMode.PREFERENCE_KEY) {
                ChildFriendlyMode.refresh()
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    return enabled
}
