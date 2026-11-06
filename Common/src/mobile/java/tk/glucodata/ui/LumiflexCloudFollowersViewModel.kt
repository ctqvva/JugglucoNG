package tk.glucodata.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.share.lumiflex.*

internal enum class FollowerOperation { INVITE, SAVE, REMOVE }

internal data class LumiflexFollowersUiState(
    val followers: List<FollowerInfo> = emptyList(),
    val loading: Boolean = false,
    val operation: FollowerOperation? = null,
    val removalId: String? = null,
    val loadFailed: Boolean = false,
    val inviteError: Int? = null,
    val editorError: Int? = null,
    val removeError: Int? = null,
    val notice: Int? = null,
    val emailMode: Boolean = false,
    val account: String = "",
    val alias: String = "",
    val editor: FollowerInfo? = null,
) {
    val busy get() = operation != null
    val blocked get() = loading || busy
    val editorDirty get() = editor != null && editor != followers.find {
        it.userAuthorizationId == editor.userAuthorizationId
    }
    val canInvite get() = !blocked && if (emailMode) {
        android.util.Patterns.EMAIL_ADDRESS.matcher(account.trim()).matches()
    } else LumiflexCloudClient.registrationPhone(account) != null
}

/** Keeps form drafts separate from the last server-confirmed follower list. */
class LumiflexCloudFollowersViewModel @JvmOverloads constructor(
    application: Application,
    private val manager: LumiflexCloudFollowerManager = LumiflexCloudFollowerManager(
        LumiflexCloudClient(), LumiflexCloudSettings(application)),
    private val networkDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application) {
    private val settings = LumiflexCloudSettings(application)
    private val mutableState = MutableStateFlow(LumiflexFollowersUiState())
    internal val state = mutableState.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (state.value.blocked) return
        mutableState.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            val rows = withContext(networkDispatcher) { manager.list() }
            mutableState.update {
                if (rows == null) it.copy(loading = false, loadFailed = true)
                else it.copy(loading = false, loadFailed = false, followers = rows,
                    editor = when {
                        it.editorDirty && rows.any { row -> row.userAuthorizationId == it.editor?.userAuthorizationId } -> it.editor
                        rows.size != it.followers.size -> rows.singleOrNull()
                        else -> rows.find { row -> row.userAuthorizationId == it.editor?.userAuthorizationId }
                    })
            }
        }
    }

    fun emailModeChanged(value: Boolean) {
        if (!state.value.busy) mutableState.update { it.copy(emailMode = value, account = "", inviteError = null) }
    }

    fun accountChanged(value: String) { mutableState.update { it.copy(account = value, inviteError = null) } }
    fun aliasChanged(value: String) { mutableState.update { it.copy(alias = value, inviteError = null) } }

    private fun discardStaleResult(expected: LumiflexCloudSettings.Session): Boolean {
        if (settings.isCurrent(expected)) return false
        // The server may have applied the old account's request. Do not offer to retry it as a failure.
        mutableState.update { it.copy(operation = null, removalId = null, followers = emptyList(), editor = null,
            account = "", alias = "", inviteError = null, editorError = null, removeError = null, notice = null) }
        refresh()
        return true
    }

    fun invite() {
        val input = state.value
        if (!input.canInvite) return
        val expected = settings.session()
        mutableState.update { it.copy(operation = FollowerOperation.INVITE, inviteError = null, notice = null) }
        viewModelScope.launch {
            val confirmed = withContext(networkDispatcher) { manager.invite(input.account, input.alias, expected) }
            if (discardStaleResult(expected)) return@launch
            mutableState.update {
                if (confirmed) it.copy(operation = null, account = "", alias = "",
                    notice = R.string.lumiflex_status_invited)
                else it.copy(operation = null, inviteError = R.string.lumiflex_status_invite_failed)
            }
            if (confirmed) refresh()
        }
    }

    fun edit(row: FollowerInfo) {
        if (!state.value.blocked) mutableState.update { it.copy(editor = row, editorError = null, notice = null) }
    }

    internal fun editChanged(row: FollowerInfo) {
        if (!state.value.blocked && row.userAuthorizationId == state.value.editor?.userAuthorizationId) {
            mutableState.update { it.copy(editor = row, editorError = null) }
        }
    }

    fun dismissEditor() {
        if (!state.value.busy) mutableState.update { it.copy(editor = null, editorError = null) }
    }

    fun saveEditor() {
        val input = state.value
        val row = input.editor?.let { it.copy(readerAlias = it.readerAlias.trim()) } ?: return
        if (input.blocked || !input.editorDirty) return
        val expected = settings.session()
        mutableState.update { it.copy(operation = FollowerOperation.SAVE, editorError = null) }
        viewModelScope.launch {
            val confirmed = withContext(networkDispatcher) { manager.save(row, expected) }
            if (discardStaleResult(expected)) return@launch
            mutableState.update {
                if (confirmed) it.copy(operation = null, editor = row,
                    followers = it.followers.map { old -> if (old.userAuthorizationId == row.userAuthorizationId) row else old })
                else it.copy(operation = null, editorError = R.string.savefailed)
            }
        }
    }

    fun remove(id: String) {
        val input = state.value
        val row = input.followers.find { it.userAuthorizationId == id } ?: return
        if (input.blocked) return
        val expected = settings.session()
        mutableState.update { it.copy(operation = FollowerOperation.REMOVE, removalId = id, removeError = null) }
        viewModelScope.launch {
            val confirmed = withContext(networkDispatcher) { manager.remove(row.userAuthorizationId, expected) }
            if (discardStaleResult(expected)) return@launch
            mutableState.update {
                if (confirmed) {
                    val remaining = it.followers.filterNot { old -> old.userAuthorizationId == row.userAuthorizationId }
                    it.copy(operation = null, removalId = null,
                        editor = if (it.editor?.userAuthorizationId == id || it.editor == null) remaining.singleOrNull() else it.editor,
                        followers = remaining,
                        notice = R.string.lumiflex_status_removed)
                }
                else it.copy(operation = null, removalId = null, removeError = R.string.lumiflex_status_remove_failed)
            }
        }
    }
}
