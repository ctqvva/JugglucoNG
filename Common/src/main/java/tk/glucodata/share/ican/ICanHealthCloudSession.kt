package tk.glucodata.share.ican

/** Silent re-login shared by the uploader and the follower manager. */
internal object ICanHealthCloudSession {

    fun relogin(client: ICanHealthCloudClient, settings: ICanHealthCloudSettings, oldToken: String): Boolean {
        val start = settings.session()
        // Prefer the refresh token when it is available: it avoids re-hashing the password
        // and re-sending credentials, and keeps the long-lived session alive.
        val refresh = settings.refreshToken
        if (refresh.isNotBlank()) {
            val result = client.refresh(refresh)
            if (result is ICanHealthCloudClient.LoginResult.Ok) {
                return storeIfUnchanged(settings, start, result)
            }
        }
        val account = settings.account
        val password = settings.password
        if (account.isBlank() || password.isBlank()) return false
        val result = client.login(account, password)
        if (result !is ICanHealthCloudClient.LoginResult.Ok) return false
        return storeIfUnchanged(settings, start, result)
    }

    /**
     * A concurrent flow may have replaced the session (another account's login, or a fresher
     * token) while this relogin was in flight. Never override it: treat the concurrent change
     * as the winner and let the caller re-evaluate instead of retrying with a revoked token.
     */
    private fun storeIfUnchanged(
        settings: ICanHealthCloudSettings,
        start: ICanHealthCloudSettings.Session,
        result: ICanHealthCloudClient.LoginResult.Ok,
    ): Boolean = synchronized(ICanHealthCloudSettings.sessionLock) {
        if (settings.session() != start) return false
        settings.token = result.sessionId
        if (result.model.refreshToken.isNotEmpty()) settings.refreshToken = result.model.refreshToken
        if (result.model.userId.isNotEmpty()) settings.userId = result.model.userId
        if (result.model.tenantId.isNotEmpty()) settings.tenantId = result.model.tenantId
        true
    }
}
