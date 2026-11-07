package tk.glucodata.share.lumiflex

class LumiflexCloudFollowerManager(
    private val client: LumiflexCloudClient,
    private val settings: LumiflexCloudSettings,
) {

    fun list(): List<FollowerInfo>? {
        val session = settings.session()
        if (!session.signedIn) return null
        val rows = client.followerList(session.token, isShare = true, userId = session.userId)
        return rows.takeIf { settings.isCurrent(session) }
    }

    fun follows(): List<FollowerInfo>? =
        client.followerList(settings.token, isShare = false, userId = settings.userId)

    fun invite(account: String, alias: String, expected: LumiflexCloudSettings.Session = settings.session()): Boolean {
        if (account.isBlank()) return false
        return change(expected) { client.addFollower(it.token, account, alias, it.userId) != null }
    }

    /** Send one confirmed edit, rather than racing separate requests for each switch/name. */
    fun save(row: FollowerInfo, expected: LumiflexCloudSettings.Session = settings.session()): Boolean = change(expected) {
        client.updateFollower(it.token, row.userAuthorizationId,
            displayHidden = row.hideState == 1, normalPush = row.normalPushState == 1,
            urgentPush = row.emergePushState == 1, extremeHighPush = row.extremeHighPushState == 1,
            userId = it.userId, alias = row.readerAlias)
    }

    private fun change(session: LumiflexCloudSettings.Session, action: (LumiflexCloudSettings.Session) -> Boolean): Boolean {
        if (!session.signedIn || !settings.isCurrent(session)) return false
        val confirmed = action(session)
        return confirmed && settings.isCurrent(session)
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

    fun remove(id: String, expected: LumiflexCloudSettings.Session = settings.session()): Boolean =
        change(expected) { client.removeFollower(it.token, id, it.userId) }
}
