package tk.glucodata.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import tk.glucodata.Log
import tk.glucodata.R

/**
 * The one place a deletion's "… deleted · Undo" snackbar is shown, whichever screen or sheet
 * did the deleting.
 *
 * The host lives at the app's root ([UndoSnackbarEffect] in MainNavigation), so the message and
 * its Undo outlive what triggered them: a sheet that deletes and closes in the same tap, or a
 * screen the user backs out of while the snackbar is still up. A newer message replaces the one
 * on screen instead of queueing behind it.
 */
object UndoSnackbar {
    class Request(
        val message: String,
        /** Null for a plain notice; otherwise run, in the app's scope, when Undo is tapped. */
        val onUndo: (suspend () -> Unit)?,
    )

    private val requests = MutableSharedFlow<Request>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Shows [message] with an Undo action that runs [onUndo]. */
    fun show(message: String, onUndo: suspend () -> Unit) {
        requests.tryEmit(Request(message, onUndo))
    }

    /** Shows [message] alone, in the same place, replacing any undo still on screen. */
    fun notice(message: String) {
        requests.tryEmit(Request(message, null))
    }

    internal suspend fun collect(hostState: SnackbarHostState, undoLabel: () -> String): Unit = coroutineScope {
        val undoScope = this
        // collectLatest: a new request cancels the one being shown, which dismisses it. Without
        // this, each request waited its full turn behind the last, so one action seen twice
        // played its snackbar twice, one after the other.
        requests.collectLatest { request ->
            hostState.currentSnackbarData?.dismiss()
            val undo = request.onUndo
            val result = hostState.showSnackbar(
                message = request.message,
                actionLabel = if (undo != null) undoLabel() else null,
                duration = SnackbarDuration.Short,
            )
            if (undo != null && result == SnackbarResult.ActionPerformed) {
                // Outside collectLatest: the next snackbar must not cancel a restore under way.
                undoScope.launch {
                    try {
                        undo()
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        Log.stack("UndoSnackbar", "undo", failure)
                    }
                }
            }
        }
    }
}

/** Shows [UndoSnackbar] requests on [hostState] for as long as this stays composed. */
@Composable
fun UndoSnackbarEffect(hostState: SnackbarHostState) {
    val undoLabel by rememberUpdatedState(stringResource(R.string.undo))
    LaunchedEffect(hostState) { UndoSnackbar.collect(hostState) { undoLabel } }
}
