package tk.glucodata.share.lumiflex

class LumiflexCloudFollowerManager(
    private val client: LumiflexCloudClient,
    private val settings: LumiflexCloudSettings,
) {

    fun list(): List<FollowerInfo> = client.followerList(settings.token, isShare = true)

    fun follows(): List<FollowerInfo> = client.followerList(settings.token, isShare = false)

    fun invite(account: String, alias: String): Boolean {
        if (account.isBlank()) return false
        return client.addFollower(settings.token, account, alias, settings.userId) != null
    }

    fun setDisplayHidden(id: String, hidden: Boolean): Boolean =
        client.updateFollower(settings.token, id, displayHidden = hidden, userId = settings.userId)

    fun setAlerts(id: String, normal: Boolean, urgent: Boolean, extremeHigh: Boolean): Boolean =
        client.updateFollower(
            token = settings.token,
            id = id,
            normalPush = normal,
            urgentPush = urgent,
            extremeHighPush = extremeHigh,
            userId = settings.userId,
        )

    fun rename(id: String, alias: String): Boolean =
        client.renameFollower(settings.token, id, alias, settings.userId)

    fun remove(id: String): Boolean = client.removeFollower(settings.token, id, settings.userId)
}
