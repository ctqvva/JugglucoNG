package tk.glucodata.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tk.glucodata.data.settings.UncertaintySettingsRepository

/** Observe display state without giving the composable access to storage or app globals. */
@Composable
internal fun rememberUncertaintyRibbonEnabled(): Boolean {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { UncertaintySettingsRepository(context) }
    val enabled by repository.ribbonEnabled.collectAsStateWithLifecycle(initialValue = repository.isRibbonEnabled)
    return enabled
}
