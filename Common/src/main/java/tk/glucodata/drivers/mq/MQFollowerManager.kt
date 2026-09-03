package tk.glucodata.drivers.mq

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.util.Locale
import kotlin.math.abs
import tk.glucodata.Applic
import tk.glucodata.GlucoseReadingSource
import tk.glucodata.HistorySyncAccess
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.VirtualGlucoseSensorBridge

@SuppressLint("MissingPermission")
class MQFollowerManager(
    serial: String,
    private val followerAccount: String,
    private val initialDisplayName: String,
    dataptr: Long,
) : SuperGattCallback(serial, dataptr, MQBleManager.SENSOR_GEN), MQDriver {

    companion object {
        private const val TAG = MQConstants.TAG
        private const val POLL_INTERVAL_MS = 60_000L
        private const val RETRY_INTERVAL_MS = 30_000L
        private const val HISTORY_LOOKBACK_MS = 20L * 24L * 60L * 60L * 1000L
        private const val HISTORY_OVERLAP_MS = 15L * 60L * 1000L
        private const val HISTORY_FUTURE_GRACE_MS = 5L * 60L * 1000L
    }

    private enum class Phase {
        IDLE,
        SYNCING,
        FOLLOWING,
    }

    private val handlerThread = HandlerThread("MQFollower-$serial").also { it.start() }
    private val handler = Handler(handlerThread.looper)
    private val pollRunnable = Runnable { refreshFollowerState("poll") }

    @Volatile private var phase: Phase = Phase.IDLE
    @Volatile private var currentDisplayName: String = initialDisplayName.ifBlank {
        "${MQConstants.DEFAULT_FOLLOWER_DISPLAY_NAME} · $followerAccount"
    }
    @Volatile private var currentStatus: String =
        localizedString(R.string.mq_follower_status_idle, "Follower idle")
    @Volatile private var snapshotId: String = ""
    @Volatile private var sensorStartAtMs: Long = 0L
    @Volatile private var lastImportedHistoryTailMs: Long = 0L
    @Volatile private var latestReadingTimeMs: Long = 0L
    @Volatile private var latestReadingMgdl: Float = Float.NaN
    @Volatile private var latestRateDisplay: Float = Float.NaN

    init {
        mActiveDeviceAddress = "Glutec Cloud"
    }

    override fun mygetDeviceName(): String = currentDisplayName

    override fun close() {
        handler.removeCallbacksAndMessages(null)
        runCatching { handlerThread.quitSafely() }
        super.close()
    }

    override fun matchesManagedSensorId(sensorId: String?): Boolean =
        MQConstants.matchesCanonicalOrKnownNativeAlias(SerialNumber, sensorId)

    override fun hasNativeSensorBacking(): Boolean = false

    override fun shouldUseNativeHistorySync(): Boolean = false

    override fun supportsSensorCalibration(): Boolean = false

    override fun supportsResetAction(): Boolean = false

    override var viewMode: Int = 0

    override fun getLifecycleSummary(): String = currentStatus

    override fun isUiEnabled(): Boolean = true

    override fun getPassiveConnectionStatus(): String = when (phase) {
        Phase.FOLLOWING -> localizedString(R.string.mq_follower_status_following, "Following")
        Phase.SYNCING -> localizedString(R.string.mq_follower_status_syncing, "Refreshing follower")
        Phase.IDLE -> localizedString(R.string.mq_follower_title, "Glutec follower")
    }

    override fun getDetailedBleStatus(): String = currentStatus

    override fun getCurrentSnapshot(maxAgeMillis: Long): MQCurrentSnapshot? {
        val timestampMs = latestReadingTimeMs
        val glucoseMgdl = latestReadingMgdl
        if (timestampMs <= 0L || !glucoseMgdl.isFinite() || glucoseMgdl <= 0f) {
            return null
        }
        if (abs(System.currentTimeMillis() - timestampMs) > maxAgeMillis) {
            return null
        }
        val glucoseDisplay = if (Applic.unit == 1) {
            (glucoseMgdl / MQConstants.MMOL_TO_MGDL).toFloat()
        } else {
            glucoseMgdl
        }
        return MQCurrentSnapshot(
            timeMillis = timestampMs,
            glucoseValue = glucoseDisplay,
            rawValue = Float.NaN,
            rate = latestRateDisplay,
            sensorGen = MQBleManager.SENSOR_GEN,
        )
    }

    override fun getStartTimeMs(): Long = sensorStartAtMs

    override fun getOfficialEndMs(): Long =
        if (sensorStartAtMs > 0L) {
            sensorStartAtMs + MQProfileResolver.resolve().ratedLifetimeMs()
        } else {
            0L
        }

    override fun getExpectedEndMs(): Long = getOfficialEndMs()

    override fun isSensorExpired(): Boolean {
        val end = getOfficialEndMs()
        return end > 0L && System.currentTimeMillis() >= end
    }

    override fun getSensorRemainingHours(): Int {
        val end = getOfficialEndMs()
        if (end <= 0L) return -1
        val remaining = end - System.currentTimeMillis()
        return if (remaining <= 0L) 0 else ((remaining + 30 * 60 * 1000L) / (60L * 60 * 1000L)).toInt()
    }

    override fun getSensorAgeHours(): Int {
        if (sensorStartAtMs <= 0L) return -1
        val age = System.currentTimeMillis() - sensorStartAtMs
        if (age <= 0L) return 0
        return ((age + 30 * 60 * 1000L) / (60L * 60 * 1000L)).toInt()
    }

    override fun getReadingIntervalMinutes(): Int = MQConstants.DEFAULT_READING_INTERVAL_MINUTES

    override fun calibrateSensor(glucoseMgDl: Int): Boolean = false

    override val vendorFirmwareVersion: String = ""

    override val vendorModelName: String
        get() = MQConstants.DEFAULT_FOLLOWER_DISPLAY_NAME

    override val batteryMillivolts: Int = 0

    override val batteryPercent: Int = -1

    override fun connectDevice(delayMillis: Long): Boolean {
        stop = false
        scheduleRefresh(delayMillis.coerceAtLeast(0L))
        return true
    }

    override fun softDisconnect() {
        stop = true
        handler.removeCallbacksAndMessages(null)
        phase = Phase.IDLE
        currentStatus = localizedString(R.string.mq_follower_status_paused, "Follower paused")
        UiRefreshBus.requestStatusRefresh()
    }

    override fun softReconnect() {
        stop = false
        scheduleRefresh(0L)
    }

    override fun terminateManagedSensor(wipeData: Boolean) {
        stop = true
        handler.removeCallbacksAndMessages(null)
        if (wipeData) {
            Applic.app?.let { MQRegistry.removeSensor(it, SerialNumber) }
        }
    }

    override fun removeManagedPersistence(context: Context) {
        MQRegistry.removeSensor(context, SerialNumber)
    }

    override fun refreshVendorBootstrap(
        context: Context,
        qrCode: String?,
        account: String?,
        password: String?,
    ): Boolean = false

    private fun scheduleRefresh(delayMillis: Long) {
        handler.removeCallbacks(pollRunnable)
        if (!stop) {
            handler.postDelayed(pollRunnable, delayMillis)
        }
    }

    private fun ensureCloudToken(
        context: Context,
        accountState: MQRegistry.AccountState,
    ): String? {
        accountState.authToken?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val credentials = accountState.credentials ?: return null
        val login = MQCloudClient.signInWithPassword(context, credentials)
        val token = login.token?.trim().orEmpty()
        if (token.isNotEmpty()) {
            MQRegistry.saveAuthToken(context, token)
            return token
        }
        if (login.failure == MQBootstrapFailure.AUTH_EXPIRED) {
            MQRegistry.clearAuthToken(context)
        }
        return null
    }

    private fun setStatus(
        phase: Phase,
        status: String,
    ) {
        this.phase = phase
        currentStatus = status
        UiRefreshBus.requestStatusRefresh()
    }

    private fun localizedString(resId: Int, fallback: String): String =
        Applic.app?.getString(resId) ?: fallback

    private fun refreshFollowerState(reason: String) {
        if (stop) return
        val context = Applic.app ?: return
        val targetAccount = followerAccount.trim().ifEmpty {
            MQRegistry.loadFollowerAccount(context).trim()
        }
        if (targetAccount.isEmpty()) {
            setStatus(
                Phase.IDLE,
                localizedString(R.string.mq_follower_status_enter_account, "Enter follower account"),
            )
            return
        }
        val accountState = MQRegistry.loadAccountState(context)
        val currentUser = accountState.phone.trim()
        if (currentUser.isEmpty()) {
            setStatus(
                Phase.IDLE,
                localizedString(R.string.mq_follower_status_sign_in, "Sign in to MQ / Glutec first"),
            )
            return
        }

        setStatus(
            Phase.SYNCING,
            localizedString(R.string.mq_follower_status_syncing, "Refreshing follower"),
        )

        try {
            val token = ensureCloudToken(context, accountState)
            if (token.isNullOrEmpty()) {
                setStatus(
                    Phase.IDLE,
                    localizedString(R.string.mq_follower_status_need_login, "Need MQ login for follower"),
                )
                scheduleRefresh(RETRY_INTERVAL_MS)
                return
            }

            val friend = MQCloudClient.findFriendUser(
                context = context,
                authToken = token,
                userAccount = currentUser,
                friendAccount = targetAccount,
            )
            when {
                friend.failure == MQBootstrapFailure.AUTH_EXPIRED -> {
                    MQRegistry.clearAuthToken(context)
                    setStatus(
                        Phase.IDLE,
                        localizedString(R.string.mq_login_expired_status, "MQ login expired"),
                    )
                    scheduleRefresh(RETRY_INTERVAL_MS)
                    return
                }

                !friend.exists -> {
                    setStatus(
                        Phase.IDLE,
                        friend.message ?: localizedString(
                            R.string.mq_follower_account_not_found,
                            "Follower account not found",
                        ),
                    )
                    scheduleRefresh(RETRY_INTERVAL_MS)
                    return
                }

                !friend.isFriend -> {
                    currentDisplayName = friend.name?.takeIf { it.isNotBlank() }
                        ?.let { "$it · $targetAccount" }
                        ?: "${MQConstants.DEFAULT_FOLLOWER_DISPLAY_NAME} · $targetAccount"
                    setStatus(
                        Phase.IDLE,
                        localizedString(
                            R.string.mq_follower_status_waiting_approval,
                            "Waiting for share approval",
                        ),
                    )
                    scheduleRefresh(POLL_INTERVAL_MS)
                    return
                }
            }

            currentDisplayName = friend.name?.takeIf { it.isNotBlank() }
                ?.let { "$it · $targetAccount" }
                ?: "${MQConstants.DEFAULT_FOLLOWER_DISPLAY_NAME} · $targetAccount"

            val resolvedSnapshotId = friend.snapshotId?.trim().orEmpty()
            if (resolvedSnapshotId.isEmpty()) {
                setStatus(
                    Phase.IDLE,
                    if (friend.monitor == 0) {
                        localizedString(
                            R.string.mq_follower_status_no_active_session,
                            "No active Glutec session",
                        )
                    } else {
                        localizedString(
                            R.string.mq_follower_status_waiting_session,
                            "Waiting for Glutec session",
                        )
                    },
                )
                scheduleRefresh(POLL_INTERVAL_MS)
                return
            }

            val snapshotState = MQCloudClient.querySnapshotById(
                context = context,
                authToken = token,
                snapshotId = resolvedSnapshotId,
            )
            if (snapshotState.failure == MQBootstrapFailure.AUTH_EXPIRED) {
                MQRegistry.clearAuthToken(context)
                setStatus(
                    Phase.IDLE,
                    localizedString(R.string.mq_login_expired_status, "MQ login expired"),
                )
                scheduleRefresh(RETRY_INTERVAL_MS)
                return
            }
            if (!snapshotState.success) {
                setStatus(
                    Phase.IDLE,
                    snapshotState.message
                        ?: localizedString(R.string.mq_follower_status_sync_failed, "Follower sync failed"),
                )
                scheduleRefresh(RETRY_INTERVAL_MS)
                return
            }

            snapshotId = snapshotState.snapshotId.orEmpty()
            if (snapshotState.createTimeMs > 0L) {
                sensorStartAtMs = snapshotState.createTimeMs
                MQRegistry.saveSensorStartAt(context, SerialNumber, sensorStartAtMs)
            }
            if (snapshotId.isNotEmpty()) {
                MQRegistry.saveSnapshotId(context, SerialNumber, snapshotId)
            }

            val now = System.currentTimeMillis()
            val latestRoomMs = HistorySyncAccess.getLatestTimestampForSensor(SerialNumber)
            val historyStartMs = when {
                lastImportedHistoryTailMs > 0L -> (lastImportedHistoryTailMs - HISTORY_OVERLAP_MS).coerceAtLeast(1L)
                sensorStartAtMs > 0L -> sensorStartAtMs
                latestRoomMs > 0L -> (latestRoomMs - HISTORY_OVERLAP_MS).coerceAtLeast(1L)
                else -> (now - HISTORY_LOOKBACK_MS).coerceAtLeast(1L)
            }
            val historyResult = MQCloudClient.fetchSnapshotTimeBucketHistory(
                context = context,
                authToken = token,
                snapshotId = snapshotId.ifBlank { resolvedSnapshotId },
                startTimeMs = historyStartMs,
                endTimeMs = now + HISTORY_FUTURE_GRACE_MS,
            )
            if (historyResult.failure == MQBootstrapFailure.AUTH_EXPIRED) {
                MQRegistry.clearAuthToken(context)
                setStatus(
                    Phase.IDLE,
                    localizedString(R.string.mq_login_expired_status, "MQ login expired"),
                )
                scheduleRefresh(RETRY_INTERVAL_MS)
                return
            }
            val history = if (historyResult.history.isNotEmpty()) {
                historyResult.history
            } else {
                MQCloudClient.fetchSnapshotDetailHistory(
                    context = context,
                    authToken = token,
                    snapshotId = snapshotId.ifBlank { resolvedSnapshotId },
                ).history
            }
            if (history.isEmpty()) {
                setStatus(
                    Phase.IDLE,
                    localizedString(R.string.mq_follower_status_no_readings, "No Glutec readings yet"),
                )
                scheduleRefresh(POLL_INTERVAL_MS)
                return
            }

            importHistory(history)
            val latest = history.last()
            updateCurrentSnapshot(latest, history)
            setStatus(
                Phase.FOLLOWING,
                localizedString(R.string.mq_follower_status_following, "Following"),
            )
            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "MQ follower refreshed (%s): account=%s snapshot=%s packet=%d glucose=%.1f",
                    reason,
                    targetAccount,
                    snapshotId,
                    latest.packetIndex,
                    latest.glucoseMgdl,
                ),
            )
            UiRefreshBus.requestDataRefresh()
            scheduleRefresh(POLL_INTERVAL_MS)
        } catch (t: Throwable) {
            Log.stack(TAG, "refreshFollowerState($reason)", t)
            setStatus(
                Phase.IDLE,
                localizedString(R.string.mq_follower_status_sync_failed, "Follower sync failed"),
            )
            scheduleRefresh(RETRY_INTERVAL_MS)
        }
    }

    private fun importHistory(history: List<MQBootstrapHistoryPoint>) {
        if (history.isEmpty()) return
        val tailMs = history.maxOf { it.timestampMs }
        if (tailMs > 0L && tailMs <= lastImportedHistoryTailMs) return
        VirtualGlucoseSensorBridge.importHistory(
            sensorSerial = SerialNumber,
            readings = history.map { point ->
                VirtualGlucoseSensorBridge.Reading(
                    timestampMs = point.timestampMs,
                    glucoseMgdl = point.glucoseMgdl,
                )
            },
            logLabel = "MQ follower",
            source = GlucoseReadingSource.MQ_FOLLOWER,
        )
        if (tailMs > 0L) {
            lastImportedHistoryTailMs = tailMs
        }
    }

    private fun updateCurrentSnapshot(
        latest: MQBootstrapHistoryPoint,
        history: List<MQBootstrapHistoryPoint>,
    ) {
        val previousTimeMs = latestReadingTimeMs
        val previousMgdl = latestReadingMgdl
        latestReadingTimeMs = latest.timestampMs
        latestReadingMgdl = latest.glucoseMgdl

        val previousPoint = history.dropLast(1).lastOrNull()
        latestRateDisplay = when {
            previousPoint != null && previousPoint.timestampMs > 0L && latest.timestampMs > previousPoint.timestampMs -> {
                val deltaMinutes = (latest.timestampMs - previousPoint.timestampMs) / 60000f
                if (deltaMinutes > 0f) {
                    val deltaMgdl = latest.glucoseMgdl - previousPoint.glucoseMgdl
                    val deltaDisplay = if (Applic.unit == 1) {
                        (deltaMgdl / MQConstants.MMOL_TO_MGDL).toFloat()
                    } else {
                        deltaMgdl
                    }
                    deltaDisplay.toFloat() / deltaMinutes
                } else {
                    0f
                }
            }

            else -> 0f
        }

        if (latest.timestampMs > previousTimeMs && latest.glucoseMgdl.isFinite() && latest.glucoseMgdl > 0f) {
            VirtualGlucoseSensorBridge.publishCurrent(
                sensorSerial = SerialNumber,
                reading = VirtualGlucoseSensorBridge.Reading(
                    timestampMs = latest.timestampMs,
                    glucoseMgdl = latest.glucoseMgdl,
                    rate = latestRateDisplay.takeIf { it.isFinite() } ?: 0f,
                ),
                sensorGen = MQBleManager.SENSOR_GEN,
                logLabel = "MQ follower",
                source = GlucoseReadingSource.MQ_FOLLOWER,
            )
        } else if (previousTimeMs > 0L && latest.timestampMs == previousTimeMs && previousMgdl != latest.glucoseMgdl) {
            UiRefreshBus.requestDataRefresh()
        }
    }
}
