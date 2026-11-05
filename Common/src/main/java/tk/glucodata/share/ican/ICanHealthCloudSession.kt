package tk.glucodata.share.ican

/** Silent re-login shared by the uploader and the follower manager. */
internal object ICanHealthCloudSession {

    fun relogin(client: ICanHealthCloudClient, settings: ICanHealthCloudSettings, oldToken: String): Boolean {
        val account = settings.account
        val password = settings.password
        if (account.isBlank() || password.isBlank()) return false
        val result = client.login(account, password)
        if (result !is ICanHealthCloudClient.LoginResult.Ok) return false
        if (settings.token != oldToken) return true
        settings.token = result.sessionId
        if (result.model.refreshToken.isNotEmpty()) settings.refreshToken = result.model.refreshToken
        if (result.model.userId.isNotEmpty()) settings.userId = result.model.userId
        if (result.model.tenantId.isNotEmpty()) settings.tenantId = result.model.tenantId
        return true
    }
}
