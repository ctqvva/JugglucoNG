package tk.glucodata.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.share.lumiflex.LumiflexCloudClient
import tk.glucodata.share.lumiflex.LumiflexCloudCredentials
import tk.glucodata.share.lumiflex.LumiflexCloudError
import tk.glucodata.share.lumiflex.LumiflexCloudSettings
import tk.glucodata.share.lumiflex.LumiflexCloudSyncWorker
import tk.glucodata.share.lumiflex.LumiflexCloudSensor
import tk.glucodata.share.lumiflex.FollowerInfo
import tk.glucodata.share.lumiflex.LumiflexCloudRole

internal data class LumiflexCloudUiState(
    val account: String = "",
    val password: String = "",
    val session: LumiflexCloudSettings.Session = LumiflexCloudSettings.Session("", "", "", 0),
    val enabled: Boolean = false,
    val sync: LumiflexCloudSettings.SyncStatus = LumiflexCloudSettings.SyncStatus(),
    val busy: Boolean = false,
    val checkingSession: Boolean = false,
    val verificationFailed: Boolean = false,
    val sessionExpired: Boolean = false,
    val registration: Boolean = false,
    val phone: String = "",
    val code: String = "",
    val codeSent: Boolean = false,
    val sendingCode: Boolean = false,
    val resendSeconds: Int = 0,
    val passwordSetupRequired: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val followMode: Boolean = false,
    val providerId: String = "",
    val providers: List<FollowerInfo> = emptyList(),
    val providersLoaded: Boolean = false,
)

/** Keeps entry drafts, vendor operations and persisted session identity apart. */
class LumiflexCloudViewModel @JvmOverloads constructor(
    application: Application,
    private val client: LumiflexCloudClient = LumiflexCloudClient(),
    private val credentials: LumiflexCloudCredentials = LumiflexCloudCredentials(application),
    private val networkDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scheduleSync: (Context) -> Unit = { LumiflexCloudSyncWorker.initialize(it, replace = true) },
) : AndroidViewModel(application) {
    private val settings = LumiflexCloudSettings(application)
    private val draft = credentials.load()
    private val mutableState = MutableStateFlow(LumiflexCloudUiState(
        account = draft.account.ifBlank { settings.account }, password = draft.password,
        session = settings.session(), enabled = settings.enabled, sync = settings.syncStatus(),
        passwordSetupRequired = settings.passwordSetupRequired,
        sessionExpired = settings.sessionExpired,
        followMode = settings.followMode, providerId = settings.providerId,
    ))
    internal val state = mutableState.asStateFlow()
    private var actionGeneration = 0

    init {
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(1_000)
            }
        }
        if (settings.isSignedIn()) checkConnection(sync = false)
    }

    private fun text(id: Int) = getApplication<Application>().getString(id)

    private fun requestError(kind: LumiflexCloudError.Kind, message: String): String =
        if (kind == LumiflexCloudError.Kind.TRANSIENT) text(R.string.lumiflex_connection_error)
        else message.ifBlank { text(R.string.lumiflex_request_failed) }

    private fun refresh() {
        mutableState.update { it.copy(
            session = settings.session(), enabled = settings.enabled, sync = settings.syncStatus(),
            passwordSetupRequired = settings.passwordSetupRequired,
            sessionExpired = settings.sessionExpired,
            followMode = settings.followMode, providerId = settings.providerId,
            providers = if (it.session.userId == settings.userId) it.providers else emptyList(),
            providersLoaded = it.providersLoaded && it.session.userId == settings.userId,
            resendSeconds = ((settings.nextSmsAllowedMs - System.currentTimeMillis() + 999) / 1_000)
                .coerceAtLeast(0).toInt(),
        ) }
    }

    private fun saveDraft() {
        val current = mutableState.value
        if (!credentials.save(current.account, current.password)) {
            mutableState.update { it.copy(error = text(R.string.lumiflex_credential_save_failed)) }
        }
    }

    fun accountChanged(value: String) {
        mutableState.update { it.copy(account = value, error = null, notice = null) }
        saveDraft()
    }

    fun passwordChanged(value: String) {
        mutableState.update { it.copy(password = value, error = null, notice = null) }
        saveDraft()
    }

    fun showRegistration(value: Boolean) {
        mutableState.update { it.copy(registration = value, code = "", codeSent = false, error = null, notice = null) }
    }
    fun phoneChanged(value: String) { mutableState.update { it.copy(phone = value, code = "", codeSent = false, error = null) } }
    fun codeChanged(value: String) { mutableState.update { it.copy(code = value, error = null) } }

    fun signIn() {
        val input = mutableState.value
        if (input.busy || input.account.isBlank() || input.password.isBlank()) return
        authenticate(registration = false) { client.login(input.account, input.password) }
    }

    fun requestCode() {
        val current = mutableState.value
        if (current.busy || settings.nextSmsAllowedMs > System.currentTimeMillis()) return
        if (LumiflexCloudClient.registrationPhone(current.phone) == null) {
            mutableState.update { it.copy(error = text(R.string.lumiflex_invalid_phone)) }
            return
        }
        // A lost network response may still have sent an SMS. Do not immediately send another.
        settings.nextSmsAllowedMs = System.currentTimeMillis() + 60_000
        mutableState.update { it.copy(busy = true, checkingSession = false,
            sendingCode = true, codeSent = true, code = "", error = null, notice = null) }
        val generation = ++actionGeneration
        viewModelScope.launch {
            val result = withContext(networkDispatcher) { client.requestRegistrationCode(current.phone) }
            tk.glucodata.Log.i("LumiflexCloud", "registration SMS result=" +
                if (result is LumiflexCloudClient.SyncResult.Failed) result.code else 200)
            if (generation != actionGeneration) return@launch
            when (result) {
                LumiflexCloudClient.SyncResult.Ok -> mutableState.update {
                    it.copy(busy = false, sendingCode = false, codeSent = true, notice = text(R.string.lumiflex_code_sent))
                }
                is LumiflexCloudClient.SyncResult.Failed -> mutableState.update {
                    it.copy(busy = false, sendingCode = false, error = requestError(result.kind, result.message))
                }
            }
            refresh()
        }
    }

    fun verifyCode() {
        val input = mutableState.value
        if (input.busy || !input.codeSent || input.code.isBlank()) return
        authenticate(registration = true) { client.registerWithCode(input.phone, input.code) }
    }

    private fun authenticate(registration: Boolean, request: () -> LumiflexCloudClient.LoginResult) {
        val expected = settings.session()
        val generation = ++actionGeneration
        mutableState.update { it.copy(busy = true, checkingSession = false,
            verificationFailed = it.verificationFailed || it.checkingSession, error = null, notice = null) }
        viewModelScope.launch {
            val result = withContext(networkDispatcher) { request() }
            tk.glucodata.Log.i("LumiflexCloud", "${if (registration) "verify phone" else "sign in"} result=" +
                if (result is LumiflexCloudClient.LoginResult.Failed) result.code else 200)
            if (generation != actionGeneration) return@launch
            if (!settings.isCurrent(expected)) {
                mutableState.update { it.copy(busy = false) }
                refresh()
                return@launch
            }
            when (result) {
                is LumiflexCloudClient.LoginResult.Ok -> {
                    if (!settings.startSessionIfCurrent(expected, result.account, result.token, result.userId)) {
                        mutableState.update { it.copy(busy = false) }
                        refresh()
                        return@launch
                    }
                    settings.passwordSetupRequired = registration
                    settings.enabled = !registration
                    mutableState.update { it.copy(
                        busy = false, account = result.account,
                        password = if (registration) "" else it.password,
                        registration = false, code = "", verificationFailed = false,
                        notice = if (registration) text(R.string.lumiflex_finish_password) else null,
                    ) }
                    saveDraft()
                    scheduleSync(getApplication())
                }
                is LumiflexCloudClient.LoginResult.Failed -> mutableState.update {
                    it.copy(busy = false, error = requestError(result.kind, result.message))
                }
            }
            refresh()
            if (result is LumiflexCloudClient.LoginResult.Ok && !registration) checkConnection(sync = false)
        }
    }

    fun setPassword() {
        val input = mutableState.value
        if (input.busy || !input.session.signedIn) return
        if (!validRegistrationPassword(input.password)) {
            mutableState.update { it.copy(error = text(R.string.lumiflex_password_rules)) }
            return
        }
        val expected = settings.session()
        val generation = ++actionGeneration
        mutableState.update { it.copy(busy = true, checkingSession = false,
            verificationFailed = it.verificationFailed || it.checkingSession, error = null, notice = null) }
        viewModelScope.launch {
            val result = withContext(networkDispatcher) { client.setPassword(expected.token, expected.userId, input.password) }
            tk.glucodata.Log.i("LumiflexCloud", "set password result=" +
                if (result is LumiflexCloudClient.SyncResult.Failed) result.code else 200)
            if (generation != actionGeneration) return@launch
            if (settings.isCurrent(expected)) {
                when (result) {
                    LumiflexCloudClient.SyncResult.Ok -> {
                        settings.passwordSetupRequired = false
                        settings.enabled = true
                        scheduleSync(getApplication())
                        mutableState.update { it.copy(verificationFailed = false, notice = text(R.string.lumiflex_password_saved)) }
                    }
                    is LumiflexCloudClient.SyncResult.Failed -> {
                        if (result.kind == LumiflexCloudError.Kind.UNAUTHORIZED) settings.invalidateSession(expected)
                        mutableState.update { it.copy(error = requestError(result.kind, result.message)) }
                    }
                }
            }
            mutableState.update { it.copy(busy = false) }
            refresh()
        }
    }

    fun setEnabled(value: Boolean) {
        settings.enabled = value
        scheduleSync(getApplication())
        refresh()
    }

    fun selectMode(follow: Boolean) {
        if (mutableState.value.busy || mutableState.value.checkingSession) return
        settings.selectMode(follow, if (follow) mutableState.value.providers.singleOrNull() else null)
        scheduleSync(getApplication())
        refresh()
    }

    fun selectProvider(row: FollowerInfo) {
        if (mutableState.value.busy || mutableState.value.providers.none { it == row }) return
        settings.selectMode(true, row)
        scheduleSync(getApplication())
        refresh()
    }

    fun bindSensors() {
        if (!settings.isSignedIn() || !settings.enabled || mutableState.value.busy) return
        if (LumiflexCloudSensor.pairedLocally(getApplication()).isEmpty()) {
            mutableState.update { it.copy(error = text(R.string.lumiflex_connect_sensor)) }
            return
        }
        checkConnection(sync = true)
    }

    fun checkConnection(sync: Boolean = true) {
        val expected = settings.session()
        if (!expected.signedIn || mutableState.value.busy || mutableState.value.checkingSession) return
        val generation = actionGeneration
        mutableState.update { it.copy(checkingSession = true, error = null, notice = null) }
        viewModelScope.launch {
            val (result, providers) = withContext(networkDispatcher) {
                val checked = client.checkSession(expected.token, expected.userId)
                checked to if (checked == LumiflexCloudClient.SyncResult.Ok)
                    client.followerList(expected.token, false, expected.userId) else null
            }
            if (result is LumiflexCloudClient.SyncResult.Failed && result.kind == LumiflexCloudError.Kind.UNAUTHORIZED) {
                settings.invalidateSession(expected)
            }
            if (generation != actionGeneration) { refresh(); return@launch }
            if (settings.isCurrent(expected)) {
                when (result) {
                    LumiflexCloudClient.SyncResult.Ok -> {
                        mutableState.update { it.copy(verificationFailed = false, providers = providers ?: it.providers,
                            providersLoaded = providers != null) }
                        if (providers != null && !settings.modeChosen) {
                            LumiflexCloudRole.discover(settings, providers,
                                LumiflexCloudSensor.pairedLocally(getApplication()).isNotEmpty())
                            scheduleSync(getApplication())
                        }
                        if (sync && settings.enabled) {
                            settings.recordSync(expected, LumiflexCloudSettings.SyncState.QUEUED, settings.syncStatus().pending)
                            scheduleSync(getApplication())
                        }
                    }
                    is LumiflexCloudClient.SyncResult.Failed -> {
                        if (result.kind == LumiflexCloudError.Kind.UNAUTHORIZED) settings.invalidateSession(expected)
                        mutableState.update { it.copy(verificationFailed = true, error = requestError(result.kind, result.message)) }
                    }
                }
            }
            mutableState.update { it.copy(checkingSession = false) }
            refresh()
        }
    }

    fun signOut() {
        actionGeneration++
        settings.endSession()
        settings.enabled = false
        scheduleSync(getApplication())
        mutableState.update { it.copy(busy = false, sendingCode = false, checkingSession = false,
            verificationFailed = false, registration = false, notice = null, error = null) }
        refresh()
    }

    companion object {
        internal fun validRegistrationPassword(value: String): Boolean =
            value.length in 8..12 && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
    }
}
