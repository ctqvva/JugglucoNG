package tk.glucodata.share.ican

class ICanHealthCloudFollowerManager(
    private val client: ICanHealthCloudClient,
    private val settings: ICanHealthCloudSettings,
) {
    fun list(): List<FollowerFriends> = withSessionRetry { token ->
        client.followerList(token)
    }

    fun invite(email: String, phone: String, phoneAreaCode: String, name: String): FollowerFriends? {
        if (email.isEmpty() && phone.isEmpty()) {
            throw IllegalArgumentException("email or phone required")
        }
        return withSessionRetry { token ->
            client.followerSave(token, email, phone, phoneAreaCode, name)
        }
    }

    fun remove(id: String): Boolean = withSessionRetry { token ->
        client.followerUpdate(token, id, "deleted", "")
    }

    fun rename(id: String, nickname: String): Boolean = withSessionRetry { token ->
        client.followerUpdate(token, id, "", nickname)
    }

    fun setShareFollower(enabled: Boolean): Boolean = withSessionRetry { token ->
        client.basicConfigFollowerUpdate(token, enabled.toString())
    }

    /**
     * The server can retire the stored access token at any time (single active
     * session policy), which surfaces as HTTP 401 from authorized calls.
     * Mirror the vendor behaviour (hard logout) as a silent re-login and retry
     * the request once with the fresh token.
     */
    private fun <T> withSessionRetry(block: (String) -> T): T {
        val token = settings.token
        try {
            return block(token)
        } catch (expired: ICanHealthCloudClient.SessionExpired) {
            if (!ICanHealthCloudSession.relogin(client, settings, token)) throw expired
            return block(settings.token)
        }
    }
}
