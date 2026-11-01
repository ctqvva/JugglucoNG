package tk.glucodata.share.ican

class ICanHealthCloudFollowerManager(
    private val client: ICanHealthCloudClient,
    private val settings: ICanHealthCloudSettings,
) {
    fun list(): List<FollowerFriends> {
        val token = settings.token
        return client.followerList(token)
    }

    fun invite(email: String, phone: String, phoneAreaCode: String, name: String): Any {
        if (email.isEmpty() && phone.isEmpty()) {
            throw IllegalArgumentException("email or phone required")
        }
        val token = settings.token
        return client.followerSave(token, email, phone, phoneAreaCode, name)
    }

    fun remove(id: String): Boolean {
        val token = settings.token
        return client.followerUpdate(token, id, "deleted", "")
    }

    fun rename(id: String, nickname: String): Boolean {
        val token = settings.token
        return client.followerUpdate(token, id, "", nickname)
    }

    fun setShareFollower(enabled: Boolean): Boolean {
        val token = settings.token
        return client.basicConfigFollowerUpdate(token, enabled.toString())
    }
}
