package tk.glucodata

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Invalidates account-derived displays after either fetched or manual native ID changes. */
object LibreViewAccountState {
    private val revisionState = MutableStateFlow(0L)
    val revision = revisionState.asStateFlow()

    @JvmStatic
    fun changed() { revisionState.update { it + 1L } }
}
