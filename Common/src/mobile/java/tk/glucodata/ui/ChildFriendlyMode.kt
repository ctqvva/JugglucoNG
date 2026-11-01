package tk.glucodata.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun rememberChildFriendlyModeSettings(): ChildFriendlyModeSettings {
    val context = LocalContext.current.applicationContext
    val revision = ChildFriendlyMode.revision
    val settings by remember(context, revision) {
        mutableStateOf(ChildFriendlyMode.settings(context))
    }

    DisposableEffect(context) {
        val observer = ChildFriendlyMode.observe(context)
        onDispose { observer.close() }
    }

    return settings
}
