package tk.glucodata.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A navigation asked for from outside Compose (a notification tap, a quick-settings
 * long press): MainActivity records the route here and the NavHost navigates when it
 * is composed, so the request survives a cold start.
 */
object PendingNavigation {
    const val EXTRA_ROUTE = "tk.glucodata.OPEN_ROUTE"

    private val _route = MutableStateFlow<String?>(null)
    val route: StateFlow<String?> = _route.asStateFlow()

    @JvmStatic
    fun request(route: String) {
        _route.value = route
    }

    fun consume() {
        _route.value = null
    }
}
