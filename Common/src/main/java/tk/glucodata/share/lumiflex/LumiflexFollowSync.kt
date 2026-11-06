package tk.glucodata.share.lumiflex

/** Read-only receiver. Every publication is fenced by session, mode and provider identity. */
class LumiflexFollowSync(
    private val client: LumiflexCloudClient,
    private val settings: LumiflexCloudSettings,
    private val publish: (List<LumiflexFollowReading>) -> Unit,
) {
    fun sync(): Boolean {
        val session = settings.session()
        val provider = settings.providerId
        fun current() = settings.enabled && settings.followMode && settings.providerId == provider && settings.isCurrent(session)
        if (!session.signedIn || provider.isBlank() || !current()) return false
        settings.recordSync(session, LumiflexCloudSettings.SyncState.SYNCING, 0)
        val follows = client.followerList(session.token, isShare = false, userId = session.userId)
        if (!current()) return false
        if (follows == null) {
            val check = client.checkSession(session.token, session.userId)
            if (check is LumiflexCloudClient.SyncResult.Failed && check.kind == LumiflexCloudError.Kind.UNAUTHORIZED) {
                settings.invalidateSession(session)
            } else settings.recordSync(session, LumiflexCloudSettings.SyncState.ERROR, 0, retrying = true)
            return false
        }
        if (follows.none { it.dataProviderId == provider }) {
            settings.recordSync(session, LumiflexCloudSettings.SyncState.ERROR, 0)
            return false
        }
        val readings = mutableListOf<LumiflexFollowReading>()
        // The first page is newest first. Fetch up to a day on first start or a long outage;
        // overlapping pages are deduplicated before reaching Room/native storage.
        val since = maxOf(settings.lastSyncMs - 5 * 60_000L, System.currentTimeMillis() - 24 * 60 * 60_000L)
        for (page in 1..3) {
            when (val result = client.followHistory(session, provider, page)) {
                is LumiflexCloudClient.FollowHistory.Failed -> {
                    if (!current()) return false
                    if (result.kind == LumiflexCloudError.Kind.UNAUTHORIZED) settings.invalidateSession(session)
                    else settings.recordSync(session, LumiflexCloudSettings.SyncState.ERROR, 0,
                        retrying = result.kind == LumiflexCloudError.Kind.TRANSIENT)
                    return false
                }
                is LumiflexCloudClient.FollowHistory.Ok -> {
                    if (!current()) return false
                    readings += result.readings
                    if (result.pageCount < 500 || result.readings.firstOrNull()?.let { it.timestampMs <= since } == true) break
                }
            }
        }
        if (!current()) return false
        val usable = readings.distinctBy { it.timestampMs }.sortedBy { it.timestampMs }
            .filter { it.timestampMs <= System.currentTimeMillis() + 60_000L }
        if (usable.isNotEmpty()) {
            publish(usable)
            if (!current()) return false
        }
        settings.recordSync(session, LumiflexCloudSettings.SyncState.SYNCED, 0, uploaded = usable.size)
        return true
    }
}

object LumiflexCloudRole {
    /** Existing upload-only installations with a followed provider and no local sensor migrate. */
    fun discover(settings: LumiflexCloudSettings, providers: List<FollowerInfo>, hasLocalSensor: Boolean) {
        if (settings.modeChosen) return
        val follow = providers.isNotEmpty() && !hasLocalSensor
        settings.selectMode(follow, if (follow) providers.singleOrNull() else null)
    }
}
