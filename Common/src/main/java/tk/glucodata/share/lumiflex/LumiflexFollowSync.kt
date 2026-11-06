package tk.glucodata.share.lumiflex

/** Read-only receiver. Every publication is fenced by session, mode and provider identity. */
class LumiflexFollowSync(
    private val client: LumiflexCloudClient,
    private val settings: LumiflexCloudSettings,
    private val publish: (List<LumiflexFollowReading>) -> Boolean,
) {
    fun sync(session: LumiflexCloudSettings.Session = settings.session()): Boolean {
        val provider = settings.providerId
        fun current() = settings.enabled && settings.followMode && settings.providerId == provider && settings.isCurrent(session)
        if (!session.signedIn || provider.isBlank() || !current()) return false
        settings.recordSync(session, LumiflexCloudSettings.SyncState.SYNCING, 0)
        val follows = client.authorizations(session.token, isShare = false, userId = session.userId)
        if (!current()) return false
        if (follows is LumiflexCloudClient.AuthorizationList.Failed) {
            if (follows.kind == LumiflexCloudError.Kind.UNAUTHORIZED) {
                settings.invalidateSession(session)
            } else settings.recordSync(session, LumiflexCloudSettings.SyncState.ERROR, 0,
                retrying = follows.kind == LumiflexCloudError.Kind.TRANSIENT)
            return false
        }
        if ((follows as LumiflexCloudClient.AuthorizationList.Ok).rows.none { it.dataProviderId == provider }) {
            settings.recordSync(session, LumiflexCloudSettings.SyncState.ERROR, 0)
            return false
        }
        val readings = mutableListOf<LumiflexFollowReading>()
        // The first page is newest first. Fetch up to a day on first start or a long outage;
        // overlapping pages are deduplicated before reaching Room/native storage.
        val now = System.currentTimeMillis()
        val since = maxOf(settings.lastReceivedMs - 5 * 60_000L, now - 24 * 60 * 60_000L)
        val pageSize = if (settings.lastReceivedMs >= now - 15 * 60_000L) 20 else 500
        for (page in 1..3) {
            when (val result = client.followHistory(session, provider, page, pageSize)) {
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
                    if (result.pageCount < pageSize || result.readings.firstOrNull()?.let { it.timestampMs <= since } == true) break
                }
            }
        }
        if (!current()) return false
        val usable = readings.distinctBy { it.timestampMs }.sortedBy { it.timestampMs }
            .filter { it.timestampMs in since..(System.currentTimeMillis() + 60_000L) }
        if (usable.isNotEmpty()) {
            if (!publish(usable)) {
                settings.recordSync(session, LumiflexCloudSettings.SyncState.ERROR, 0, retrying = true)
                return false
            }
            if (!current()) return false
            settings.recordReceived(session, usable.last().timestampMs)
        }
        settings.recordSync(session, LumiflexCloudSettings.SyncState.SYNCED, 0, uploaded = usable.size)
        return true
    }
}

object LumiflexCloudRole {
    sealed class Preparation {
        data class Ready(val session: LumiflexCloudSettings.Session) : Preparation()
        object Retry : Preparation()
        object Stop : Preparation()
    }

    fun prepare(settings: LumiflexCloudSettings, client: LumiflexCloudClient, hasLocalSensor: () -> Boolean): Preparation {
        val expected = settings.session()
        if (!settings.enabled || !expected.signedIn) return Preparation.Stop
        if (settings.modeChosen) return Preparation.Ready(expected)
        val result = client.authorizations(expected.token, false, expected.userId)
        if (!settings.isCurrent(expected)) return Preparation.Stop
        if (result is LumiflexCloudClient.AuthorizationList.Failed) {
            if (result.kind == LumiflexCloudError.Kind.UNAUTHORIZED) {
                settings.invalidateSession(expected)
                return Preparation.Stop
            }
            if (result.kind == LumiflexCloudError.Kind.TRANSIENT || settings.followMode) {
                settings.recordSync(expected, LumiflexCloudSettings.SyncState.ERROR, settings.syncStatus().pending,
                    retrying = result.kind == LumiflexCloudError.Kind.TRANSIENT)
                return if (result.kind == LumiflexCloudError.Kind.TRANSIENT) Preparation.Retry else Preparation.Stop
            }
        }
        // Do not hold the session lock while looking up BLE/native pairing metadata.
        val local = if (result is LumiflexCloudClient.AuthorizationList.Ok) hasLocalSensor() else false
        var ready: LumiflexCloudSettings.Session? = null
        if (!settings.withCurrentSession(expected) {
            if (!settings.modeChosen) {
                if (result is LumiflexCloudClient.AuthorizationList.Ok) discover(settings, result.rows, local)
                else settings.selectMode(false) // A permanent sharing error must not disable an existing uploader.
            }
            ready = settings.session()
        }) return Preparation.Stop
        return Preparation.Ready(ready!!)
    }

    /** Existing upload-only installations with a followed provider and no local sensor migrate. */
    fun discover(settings: LumiflexCloudSettings, providers: List<FollowerInfo>, hasLocalSensor: Boolean) {
        if (settings.modeChosen) return
        val follow = settings.followMode || (providers.isNotEmpty() && !hasLocalSensor)
        settings.selectMode(follow, if (follow) providers.singleOrNull() else null)
    }
}
