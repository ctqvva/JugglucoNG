package tk.glucodata.share.lumiflex

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import tk.glucodata.*
import tk.glucodata.drivers.*

/** Cloud-only sensor; the same Room/native/live bridge as the existing followers. */
class LumiflexFollowerManager(private val context: Context, private val provider: String) :
    SuperGattCallback(LumiflexFollowerRegistry.sensorId(context, provider), 0L, 0), ManagedBluetoothSensorDriver {
    private val settings = LumiflexCloudSettings(context)
    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    @Volatile private var latest: LumiflexFollowReading? = null
    @Volatile private var rateMgdl = 0f
    @Volatile private var lastPoll = 0L
    private val timer = Runnable { refresh() }
    override var viewMode: Int = 0

    override fun canConnectWithoutDataptr() = true
    override fun hasNativeSensorBacking() = false
    override fun shouldUseNativeHistorySync() = false
    override fun managesLiveRoomStorage() = true
    override fun shouldUseSharedCurrentSensorHandoffOnTerminate() = false
    override fun shouldShowSearchingStatusWhenIdle() = false
    override fun matchesManagedSensorId(sensorId: String?) = SerialNumber.equals(sensorId, true)
    override fun mygetDeviceName() = context.getString(R.string.lumiflex_cloud_title)
    override fun getDetailedBleStatus() = context.getString(when (settings.syncStatus().state) {
        LumiflexCloudSettings.SyncState.SYNCING -> R.string.lumiflex_follow_refreshing
        LumiflexCloudSettings.SyncState.ERROR -> R.string.lumiflex_follow_failed
        LumiflexCloudSettings.SyncState.SIGN_IN_REQUIRED -> R.string.lumiflex_sign_in
        else -> if (latest != null) R.string.lumiflex_follow_current else R.string.lumiflex_sync_waiting_readings
    })
    override fun getManagedCurrentSnapshot(maxAgeMillis: Long): ManagedSensorCurrentSnapshot? {
        val row = latest ?: return null
        if (kotlin.math.abs(System.currentTimeMillis() - row.timestampMs) > maxAgeMillis) return null
        return ManagedSensorCurrentSnapshot(row.timestampMs, if (Applic.unit == 1) row.mgdl / 18.0182f else row.mgdl,
            rate = if (Applic.unit == 1) rateMgdl / 18.0182f else rateMgdl)
    }
    override fun getManagedUiSnapshot(activeSensorId: String?) = ManagedSensorUiSnapshot(
        serial = SerialNumber, displayName = mygetDeviceName(), deviceAddress = "cloud.lumiflex.ru",
        connectionStatus = getDetailedBleStatus(), detailedStatus = getDetailedBleStatus(),
        subtitleStatus = settings.providerName, isActive = SerialNumber.equals(activeSensorId, true),
        vendorModel = "Lumiflex", vendorBrand = "LUMI", isVendorConnected = latest != null,
    )
    private fun usable() = !stop && settings.enabled && settings.followMode && settings.isSignedIn() &&
        settings.providerId == provider && SerialNumber == LumiflexFollowerRegistry.sensorId(context, provider) &&
        LumiflexFollowerRegistry.isRegistered(this)

    override fun connectDevice(delayMillis: Long): Boolean {
        stop = false
        if (delayMillis > 0) schedule(delayMillis) else refresh()
        return true
    }
    override fun reconnect(now: Long): Boolean {
        if (usable() && SystemClock.elapsedRealtime() - lastPoll >= 60_000L) refresh()
        return true
    }
    override fun softDisconnect() { stop = true; cancel() }
    override fun softReconnect() { stop = false; refresh() }
    override fun close() {
        cancel()
        super.close()
    }
    override fun terminateManagedSensor(wipeData: Boolean) {
        stop = true; cancel()
        if (wipeData) settings.enabled = false
    }
    override fun removeManagedPersistence(context: Context) { settings.enabled = false }

    /** Foreground/network/timer wakes use the same durable worker as alarm wakes. */
    fun refresh() { if (usable()) LumiflexCloudSyncWorker.enqueue(context) }

    /** Runs on WorkManager's thread, whose lifetime and wakelock cover the whole poll. */
    fun refreshBlocking(session: LumiflexCloudSettings.Session): Boolean {
        if (!usable() || !settings.isCurrent(session)) return false
        if (!running.compareAndSet(false, true)) return true
        lastPoll = SystemClock.elapsedRealtime()
        schedule(3 * 60_000L)
        try {
            return LumiflexFollowSync(LumiflexCloudClient(), settings, publish@{ rows ->
                if (!usable() || !settings.isCurrent(session)) return@publish false
                val history = rows.map { VirtualGlucoseSensorBridge.Reading(it.timestampMs, it.mgdl) }
                // backfill=true writes the entire fetched overlap. Zero from a nonempty,
                // validated batch is a failed write, not an already-imported watermark.
                val stored = VirtualGlucoseSensorBridge.importHistory(SerialNumber, history, "Lumiflex",
                    backfill = true, mirrorToNative = true, source = GlucoseReadingSource.LUMIFLEX_FOLLOWER)
                if (stored != history.size || !usable() || !settings.isCurrent(session)) return@publish false
                val newest = rows.last()
                if (newest.timestampMs > (latest?.timestampMs ?: 0L)) {
                    val previous = rows.dropLast(1).lastOrNull()
                    val elapsed = newest.timestampMs - (previous?.timestampMs ?: 0L)
                    rateMgdl = if (previous != null && elapsed in 1..300_000L)
                        (newest.mgdl - previous.mgdl) / (elapsed / 60_000f) else 0f
                    latest = newest
                    // History older than ten minutes belongs in the chart, not live alerts.
                    if (System.currentTimeMillis() - newest.timestampMs <= 10 * 60_000L) {
                        VirtualGlucoseSensorBridge.publishCurrent(SerialNumber,
                            VirtualGlucoseSensorBridge.Reading(newest.timestampMs, newest.mgdl, rate = rateMgdl), 0, "Lumiflex",
                            mirrorToNative = true, source = GlucoseReadingSource.LUMIFLEX_FOLLOWER)
                    }
                }
                true
            }).sync(session)
        } catch (error: Exception) {
            Log.stack("LumiflexFollower", "refresh", error)
            settings.recordSync(session, LumiflexCloudSettings.SyncState.ERROR, 0, retrying = true)
            return false
        } finally {
            running.set(false)
            if (usable()) schedule(60_000L)
            UiRefreshBus.requestStatusRefresh()
        }
    }
    private fun pollIntent(): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, LumiflexFollowerPollReceiver::class.java).setAction(LumiflexFollowerPollReceiver.ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun cancel() {
        handler.removeCallbacks(timer)
        if (LumiflexFollowerRegistry.isRegistered(this))
            (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(pollIntent())
    }
    private fun schedule(delay: Long) {
        if (!usable()) return
        handler.removeCallbacks(timer)
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val at = SystemClock.elapsedRealtime() + delay
        runCatching {
            if (alarms == null) handler.postDelayed(timer, delay)
            else if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms())
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pollIntent())
            else alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pollIntent())
        }.onFailure { handler.postDelayed(timer, delay) }
    }
}
