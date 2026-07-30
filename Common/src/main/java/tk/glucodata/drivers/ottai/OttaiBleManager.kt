// OttaiBleManager.kt — BLE state machine for Ottai CGM.
//
// Flow after connect: discover -> requestMtu -> enable notifications (history,
// live, cgm-info) -> Auth V2 (read device time, read device param+sign, verify,
// write app param+sign, derive ECDH session key) -> STREAMING (decrypt+parse
// live/history). Activation is a separate, explicitly-gated action because it
// starts the sensor's irreversible lifetime (see OttaiDriver.requestActivation).
//
// The crypto/auth/parse are delegated to the unit-tested OttaiCrypto/OttaiBleAuth/
// OttaiParser. This manager only sequences GATT ops and wires results. GATT
// timing details will be refined during field testing; the structure + crypto
// calls are faithful to the decompile.

package tk.glucodata.drivers.ottai

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.util.UUID
import kotlin.math.abs
import tk.glucodata.Applic
import tk.glucodata.HistorySyncAccess
import tk.glucodata.Log
import tk.glucodata.logd
import tk.glucodata.logi
import tk.glucodata.NightscoutUploadWake
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.SensorBluetooth
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus

@SuppressLint("MissingPermission")
class OttaiBleManager(
    serial: String,
    dataptr: Long,
) : SuperGattCallback(serial, dataptr, SENSOR_GEN), OttaiDriver {

    companion object {
        private const val TAG = OttaiConstants.TAG
        const val SENSOR_GEN = 0
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val MTU = 247
        private const val MTU_SETTLE_BEFORE_DISCOVERY_MS = 1_250L
        private const val MTU_CALLBACK_FALLBACK_MS = 2_500L
        private const val SERVICE_DISCOVERY_TIMEOUT_MS = 10_000L

        /**
         * Set (by the setup wizard) to a canonical sensorId to request a one-time
         * activation on that sensor's next successful auth. Cleared once fired.
         * Activation starts the sensor's irreversible lifetime, so it is only ever
         * armed by an explicit user action.
         */
        @Volatile @JvmStatic var activateRequestedFor: String? = null

        private const val MAX_HISTORY_REQUEST_RECORDS = 0xFFFF
        private const val HISTORY_REQUEST_CHUNK_RECORDS = 270
        private const val HISTORY_CHUNK_DELAY_MS = 750L
        private const val RECENT_HISTORY_RECORDS = 60
        private const val HISTORY_REQUEST_COOLDOWN_MS = 60_000L
        // Independent (live-sample-free) initial history backfill. Fires a few seconds after
        // streaming starts so a session that opens with empty/rejected live reads still fetches
        // history; retries a bounded number of times while the dataNo basis is unknown.
        private const val INITIAL_HISTORY_DELAY_MS = 4_000L
        private const val INITIAL_HISTORY_RETRY_MS = 5_000L
        private const val INITIAL_HISTORY_MAX_ATTEMPTS = 4
        // History chunk-chain watchdog: an in-flight chunk whose response never lands (dropped
        // notify or short/empty frame) silently stalls the whole download. If no progress within
        // the timeout, retry the same window a bounded number of times, then skip it after
        // recording the window in the persisted hole ledger (retried when the chain is idle;
        // only arrived data or the cross-session attempt cap removes a hole).
        private const val HISTORY_PAGE_TIMEOUT_MS = 12_000L
        private const val HISTORY_MAX_RETRIES = 3
        // Hole ledger: requested-but-undelivered history windows, persisted per sensor (see
        // historyHoles). Attempts are cross-session; the size cap bounds the pref for a
        // runaway overshoot. Overflow drops the OLDEST window and loses those records, so the
        // cap has to stay clear of what a normal session produces: up to
        // MAX_HISTORY_GAP_RANGES diff windows plus whatever the chunk watchdog records.
        private const val MAX_HISTORY_HOLES = 32
        // Diffing the sensor's records against the local store yields the windows that are
        // genuinely missing. Two windows separated by fewer than this many stored records are
        // merged: one slightly redundant request costs less air time than a second round trip.
        // At most MAX_HISTORY_GAP_RANGES windows are produced, so a fragmented history cannot
        // flood the ledger. Both rules only ever widen a window — coverage is never traded away.
        private const val HISTORY_GAP_COALESCE_RECORDS = 5
        private const val MAX_HISTORY_GAP_RANGES = 8
        private const val HISTORY_HOLE_MAX_ATTEMPTS = 5
        private const val HISTORY_HOLE_RETRY_DELAY_MS = 5_000L
        private const val MAX_LIVE_POLL_INTERVAL_MS = 60_000L
        private const val STREAM_ACTIVITY_STALE_MS = 180_000L
        private const val SETUP_ACTIVITY_STALE_MS = 90_000L
        private const val CONNECTION_WATCHDOG_MS = 30_000L
        // Grip under RF interference. The sensor renegotiates to economical link params
        // (~385ms interval, slave latency 4, 6s supervision timeout) ~10s after every
        // connect; with latency 4 it wakes only every ~1.9s, so a noisy radio window has
        // ~3 chances to get a packet through before the link supervision-times-out
        // (status=8). While the link is provably unstable — repeated abnormal drops in a
        // short window — re-request the fast interval whenever the sensor renegotiates
        // away from it: at ~15ms the same 5-6s timeout window holds hundreds of retry
        // opportunities. Costs sensor battery, so it is strictly bounded to storm windows.
        internal const val UNSTABLE_LINK_WINDOW_MS = 15L * 60_000L
        internal const val UNSTABLE_LINK_MIN_DROPS = 2
        internal const val SLOW_CONN_INTERVAL_UNITS = 100 // 1.25ms units -> 125ms
        internal const val SLOW_CONN_LATENCY = 2
        internal const val PRIORITY_REASSERT_MIN_GAP_MS = 20_000L
        // A pending autoconnect only fires when the sensor's advertisement gets through;
        // tearing it down every 90s in a jamming storm kept destroying the one object
        // that could end the outage. Back the CONNECTING stale threshold off per
        // consecutive stall instead: 90s -> 180s -> 360s.
        internal const val MAX_CONNECT_STALL_BACKOFF_SHIFTS = 2
        // Consecutive payloads rejected in their entirety before the dataNo ceiling is treated as
        // wrong rather than the data. Low, because the state it protects against is unrecoverable
        // and each wasted payload is a missing reading; but above 1, so a single genuinely corrupt
        // frame does not switch the filter off.
        internal const val MAX_CONSECUTIVE_CEILING_FULL_DROPS = 3
        private const val LIVE_READ_MAX_RETRIES = 2
        private const val LIVE_READ_RETRY_DELAY_MS = 1_500L
        private const val USER_RECONNECT_DEBOUNCE_MS = 30_000L
        // How long candidate discovery may wait for a matching advertisement before it
        // gives up and reverts to connecting by the sensor's stored address. Generous
        // enough to cover an NFC wake plus a couple of advertisement bursts.
        private const val FRESH_ACTIVATION_ADVERTISEMENT_TIMEOUT_MS = 120_000L
        private const val RECORD_INTERVAL_MS = 60_000L
        // How close two independently-derived activation starts must be to corroborate each
        // other. Both are (wall clock - dataNo * interval); the wall clocks differ by the poll
        // gap and each is floored to the record minute, so a genuine pair agrees within a couple
        // of records while a corrupt dataNo lands hours or days away.
        internal const val CONFIRMED_START_AGREEMENT_MS = 2L * RECORD_INTERVAL_MS
        private const val CURRENT_SAMPLE_FRESH_MS = 120_000L
        private const val CURRENT_SAMPLE_FLOOR_GRACE_MS = RECORD_INTERVAL_MS
        private const val MAX_REASONABLE_DATA_NO_AHEAD = 120
        private const val MGDL_PER_MMOLL = 18.0f

        internal fun isFreshLiveSample(receivedAtMs: Long, sampleMs: Long): Boolean =
            receivedAtMs > 0L &&
                sampleMs > 0L &&
                abs(receivedAtMs - sampleMs) <= CURRENT_SAMPLE_FRESH_MS + CURRENT_SAMPLE_FLOOR_GRACE_MS

        internal fun isPersistedDataNoAheadOfLive(previousDataNo: Int, liveDataNo: Int): Boolean =
            previousDataNo >= 0 &&
                liveDataNo >= 0 &&
                previousDataNo > liveDataNo + MAX_REASONABLE_DATA_NO_AHEAD

        internal fun previousDataNoForHistory(previousDataNo: Int, liveDataNo: Int): Int =
            if (isPersistedDataNoAheadOfLive(previousDataNo, liveDataNo)) -1 else previousDataNo

        internal fun shouldDiffStoredHistory(previousDataNo: Int, diffRetryPending: Boolean): Boolean =
            previousDataNo < 0 || diffRetryPending

        /**
         * The `false` windows of [present], coalesced and capped — i.e. exactly the records the
         * local store is missing, expressed as the fewest requests that still cover all of them.
         *
         * Callers previously asked for one contiguous span from the first gap to the newest
         * record. A single permanently-missing early record (the continuity filter rejects some,
         * and rejected records are never stored) therefore re-requested the entire history on
         * every reconnect.
         *
         * Merging is deliberately one-directional: a window only ever grows, and every missing
         * index stays inside some returned window. Hitting [maxRanges] costs redundant records,
         * never coverage.
         */
        internal fun missingRanges(
            present: BooleanArray,
            coalesceGap: Int = HISTORY_GAP_COALESCE_RECORDS,
            maxRanges: Int = MAX_HISTORY_GAP_RANGES,
        ): List<MissingRange> {
            // An empty result is the caller's proof that nothing is missing, and it latches
            // "history complete" for the connection. A nonsensical cap must therefore still
            // return a covering window rather than that answer.
            val cap = maxRanges.coerceAtLeast(1)
            val ranges = ArrayList<MissingRange>()
            var index = 0
            while (index < present.size) {
                if (present[index]) {
                    index++
                    continue
                }
                val start = index
                while (index < present.size && !present[index]) index++
                ranges += MissingRange(start, index)
            }
            while (ranges.size > 1) {
                var mergeAt = -1
                var smallestSeparation = Int.MAX_VALUE
                for (i in 0 until ranges.size - 1) {
                    val separation = ranges[i + 1].start - ranges[i].endExclusive
                    if (separation < smallestSeparation) {
                        smallestSeparation = separation
                        mergeAt = i
                    }
                }
                // Stop once the closest pair is far apart AND the count already fits: past that
                // point merging would only add redundancy for nothing.
                if (mergeAt < 0 || (smallestSeparation > coalesceGap && ranges.size <= cap)) break
                ranges[mergeAt] = MissingRange(ranges[mergeAt].start, ranges[mergeAt + 1].endExclusive)
                ranges.removeAt(mergeAt + 1)
            }
            return ranges
        }

        internal fun isLinkUnstable(dropTimesMs: List<Long>, nowMs: Long): Boolean =
            dropTimesMs.count { nowMs - it <= UNSTABLE_LINK_WINDOW_MS } >= UNSTABLE_LINK_MIN_DROPS

        /**
         * Upper bound on a plausible dataNo, or Int.MAX_VALUE for "unbounded".
         *
         * [authoritativeStartMs] must be a *confirmed* activation start (cloud-supplied, or
         * committed from a reliable live anchor) — never provisionalActiveTimeMs, nor
         * streamStartTimeMs, which seedStreamTimeAnchor() writes from anchors whose own hint is
         * derived from the provisional. A "~now" start yields a bound near
         * [MAX_REASONABLE_DATA_NO_AHEAD] while the real dataNo is in the thousands, so every
         * record is dropped — and since the anchor that learns the true start is seeded
         * downstream of this filter, that state cannot recover on its own. [shouldDistrustCeiling]
         * is the backstop for exactly that class of mistake.
         *
         * Deliberately NOT bounded by the continuity baseline: lastAcceptedSampleMs is not an
         * observed arrival time but streamStartTimeMs + dataNo*interval, so a late anchor would
         * launder the provisional straight back into this bound.
         */
        internal fun dataNoCeilingFor(
            authoritativeStartMs: Long,
            nowMs: Long,
            lastDataNo: Int,
            live: Boolean,
        ): Int {
            // A start at or after "now" (clock moved backwards, or a bogus committed start) must
            // not produce a zero/negative bound — that drops everything, the very failure this
            // filter must never cause. Fall through to the unbounded/high-water-mark branches.
            if (authoritativeStartMs > 0L && authoritativeStartMs < nowMs) {
                val elapsed = ((nowMs - authoritativeStartMs) / RECORD_INTERVAL_MS)
                    .coerceAtMost((Int.MAX_VALUE / 2).toLong())
                    .toInt()
                return elapsed + MAX_REASONABLE_DATA_NO_AHEAD
            }
            // The persisted high-water mark gates history only: a legitimate live sample after a
            // long offline gap is far past it.
            if (live) return Int.MAX_VALUE
            return if (lastDataNo > 0) lastDataNo + MAX_REASONABLE_DATA_NO_AHEAD else Int.MAX_VALUE
        }

        /**
         * Every input that could widen the ceiling — materials.activeTimeMs, lastDataNo — is only
         * ever updated by a record that already passed the ceiling. So a bound that is once too
         * tight stays too tight forever, silently, and across restarts. When the filter has
         * rejected whole payloads this many times in a row, the bound is far likelier to be wrong
         * than the sensor, and the driver stops trusting it for the rest of the session.
         */
        internal fun shouldDistrustCeiling(consecutiveFullDrops: Int): Boolean =
            consecutiveFullDrops >= MAX_CONSECUTIVE_CEILING_FULL_DROPS

        /**
         * Percent of a running history chunk chain that has been requested, or -1 when no chain
         * is running or its bounds make no sense. Clamped to 0..99 while a chain is live: the
         * chain is only cleared once it finishes, so reporting 100% while requests are still in
         * flight would read as "done" for however long the tail takes.
         */
        internal fun historyBackfillPercent(chainStart: Int, nextStart: Int, endExclusive: Int): Int {
            if (chainStart < 0 || endExclusive <= chainStart) return -1
            val total = endExclusive - chainStart
            val done = (nextStart - chainStart).coerceIn(0, total)
            return ((done.toLong() * 100L) / total.toLong()).toInt().coerceIn(0, 99)
        }

        /**
         * Which address the transport should hold once [candidateAddress] is done with.
         *
         * The test is [candidateVerified] — did this address prove possession of *this* sensor's
         * auth material — and NOT "did we manage to disprove it". A probe that dies before the
         * signature read (GATT 133, service-discovery timeout, remote drop) never reaches
         * rejectActivationCandidate, so a disproved-only rule leaves that stranger installed. And
         * an installed stranger is not merely cosmetic: SensorBluetooth.getCallback() dispatches
         * on address before matchDeviceName/matchScanResult, so it is reconnected with no
         * admission check at all, and a completed auth would persist it into the registry record.
         *
         * Falls back to [homeAddress], then the registry's [recordAddress]; an unverified
         * candidate stands only when we know of no address of our own, since some address beats
         * none.
         */
        internal fun addressAfterCandidateFor(
            candidateAddress: String?,
            candidateVerified: Boolean,
            homeAddress: String?,
            recordAddress: String?,
        ): String? {
            val normalized = OttaiConstants.normalizeBleAddress(candidateAddress, allowPlain = false)
            if (candidateVerified && normalized != null) return normalized
            return homeAddress ?: recordAddress ?: normalized
        }

        internal fun shouldHoldFastParams(
            intervalUnits: Int,
            latency: Int,
            unstable: Boolean,
            nowMs: Long,
            lastReassertMs: Long,
        ): Boolean =
            unstable &&
                (intervalUnits >= SLOW_CONN_INTERVAL_UNITS || latency >= SLOW_CONN_LATENCY) &&
                nowMs - lastReassertMs >= PRIORITY_REASSERT_MIN_GAP_MS

        internal fun connectingStaleThresholdMs(stallStreak: Int): Long =
            SETUP_ACTIVITY_STALE_MS shl stallStreak.coerceIn(0, MAX_CONNECT_STALL_BACKOFF_SHIFTS)

        internal fun acceptedMaxActiveToCommit(
            commandStatus: Int,
            activationCommandAcknowledged: Boolean,
            pendingDurationMs: Long,
        ): Long =
            pendingDurationMs.takeIf {
                commandStatus == 3 && activationCommandAcknowledged && it > 0L
            } ?: 0L
    }

    enum class Phase { IDLE, CONNECTING, DISCOVERING, ENABLING_NOTIFY, AUTH, STREAMING }
    private enum class AuthStep { NONE, READ_DEVICE_TIME, READ_DEVICE_PARAM, READ_DEVICE_SIGN, WRITE_APP_PARAM, WRITE_APP_SIGN, DONE }
    private enum class ActStep { NONE, RTC, MAX_ACTIVE, DESTRUCTION, COMMAND, DONE }
    private data class EmittedReading(
        val sampleMs: Long,
        val mgdl: Float,
        val displayValue: Float,
        val publishCurrent: Boolean,
        val persist: Boolean,
        val dataNo: Int,
        val temperatureC: Float,
    )
    private data class RejectedSample(
        val rawCurrent: Int,
        val mmol: Float,
    )

    @Volatile var phase: Phase = Phase.IDLE
        private set

    private val handlerThread = HandlerThread("Ottai-$serial").also { it.start() }
    private val handler = Handler(handlerThread.looper)
    private val nativeGlucoseMirror = OttaiNativeGlucoseMirror(
        writeNative = { timestampSec, glucose, temperatureC, sensorId ->
            Natives.addGlucoseStreamWithTemp(timestampSec, glucose, temperatureC, sensorId)
        },
        wakeNightscout = { source, timestampMs ->
            NightscoutUploadWake.afterLiveNativeWrite(source, timestampMs)
        },
    )

    // Recent abnormal-disconnect timestamps (status!=0), pruned to UNSTABLE_LINK_WINDOW_MS;
    // feeds the fast-params hold and is only touched under its own lock (binder threads).
    private val abnormalDropAtMs = ArrayDeque<Long>()
    @Volatile private var lastPriorityReassertAtMs = 0L
    @Volatile private var connectStallStreak = 0
    @Volatile private var liveReadRetryCount = 0
    @Volatile private var lastUserReconnectAtMs = 0L

    private var svcDeviceInfo: BluetoothGattService? = null
    private var svcCgm: BluetoothGattService? = null
    private var svcAuth: BluetoothGattService? = null

    // ---- session state ----
    @Volatile private var materials: OttaiRegistry.DeviceMaterials = OttaiRegistry.DeviceMaterials("", "", "", 0L, "", 0)
    @Volatile private var authKeys: List<ByteArray>? = null
    @Volatile private var macBytes: ByteArray = ByteArray(0)

    @Volatile private var deviceTimeBytes: ByteArray = ByteArray(0)
    @Volatile private var deviceParamIndex: Int = 0
    @Volatile private var deviceParamTime: ByteArray = ByteArray(0)
    @Volatile private var devicePubX: ByteArray = ByteArray(0)
    @Volatile private var devicePubY: ByteArray = ByteArray(0)

    @Volatile private var appPrivate: ECPrivateKey? = null
    @Volatile private var appPubX: ByteArray = ByteArray(0)
    @Volatile private var appPubY: ByteArray = ByteArray(0)
    @Volatile private var appTime3: ByteArray = ByteArray(0)
    @Volatile private var appIndex: Int = 0
    @Volatile private var sessionKeyHex: String = ""

    @Volatile private var authStep: AuthStep = AuthStep.NONE
    @Volatile private var actStep: ActStep = ActStep.NONE
    @Volatile private var maxActiveCandidatesMs: List<Long> = emptyList()
    @Volatile private var maxActiveCandidateIndex = 0
    @Volatile private var maxActiveAttemptMs = 0L
    @Volatile private var activationNegotiationActive = false
    @Volatile private var activationRetryPending = false
    @Volatile private var activationRetryAddress: String? = null
    @Volatile private var activationCandidateDiscoveryPending = false
    @Volatile private var activationCandidateProbeActive = false
    // This sensor's own record-backed BLE address, captured when candidate discovery is
    // armed. selectActivationCandidate retargets activationRetryAddress/mActiveDeviceAddress
    // at whatever it probes, so without a separate copy a neighbouring Ottai permanently
    // displaces our address and knownBleAddress() can never find its way home again.
    @Volatile private var activationCandidateHomeAddress: String? = null
    private val rejectedActivationCandidateAddresses = linkedSetOf<String>()
    private val deferredActivationCandidateCgmInfo = mutableListOf<Pair<ByteArray, String>>()
    @Volatile private var latestCgmInfoActiveTimeCandidateMs = 0L
    @Volatile private var activationFailed = false
    @Volatile private var notifyEnableIndex = 0
    @Volatile private var discoveryStarted = false
    @Volatile private var activationCommandSentAtMs = 0L
    @Volatile private var pendingAcceptedMaxActiveMs = 0L
    @Volatile private var activationCommandAcknowledged = false
    // Actual maxActive duration (ms) the firmware accepted at activation = the real
    // sensor lifetime. Drives the displayed expected-end/remaining (Sensors tab) and
    // the native graph end (Home tab). 0 = unknown -> falls back to EXTENDED_LIFETIME_MS.
    @Volatile private var activatedMaxActiveMs = 0L
    @Volatile private var provisionalActiveTimeMs = 0L
    @Volatile private var commandStatus = -1
    @Volatile private var needsActivationAttempted = false
    @Volatile private var livePollIntervalMs = 60_000L
    @Volatile private var lastHistoryRequestAtMs = 0L
    @Volatile private var roomBackfillChecked = false
    @Volatile private var initialHistoryAttempt = 0
    @Volatile private var pendingHistoryReason: String? = null
    @Volatile private var pendingHistoryNextStart = 0
    @Volatile private var pendingHistoryEndExclusive = 0
    // First record of the chunk chain currently running, so its progress can be shown. A full
    // backfill after re-adding a sensor is ~19000 records and takes minutes, during which the
    // driver otherwise just reads "Connected" and the graph fills in silently from behind.
    @Volatile private var historyChainStart = -1
    @Volatile private var activeHistoryEndExclusive = -1
    @Volatile private var activeHistoryStart = -1
    @Volatile private var historyRetryCount = 0
    // Persisted ledger of requested-but-undelivered history windows. A gap-driven request
    // records its window here at request time; only actually-arrived data (or the cross-
    // session attempt cap) removes it — so watchdog skips, chain teardown, disconnects and
    // app restarts can no longer turn a missed window into a permanent history hole.
    // Guarded by historyHolesLock. The ledger is mutated from BOTH the driver's handler thread
    // (watchdog, hole retry, initial backfill) and the BLE binder thread —
    // onCharacteristicChanged calls handleGlucosePayload directly, with no handler hop, and that
    // path reaches trimHistoryHoles/noteHoleFailure. The operations are compound (sort, remove
    // while iterating, read-modify-write of attempts), so a lock is required rather than a
    // concurrent collection.
    private data class HistoryHole(var start: Int, var endExclusive: Int, var attempts: Int)

    /** A `[start, endExclusive)` window of records the local store does not have. */
    internal data class MissingRange(val start: Int, val endExclusive: Int)

    // Set by requestRoomBackfillAfterLive once it enters the local-store diff, which is the
    // branch taken when the previous dataNo is unusable.
    //
    // The live path answers a negative backfill result by calling requestHistoryAfterLive, and
    // that function's behaviour for an unusable previous dataNo is to ask for [0, live) — the
    // exact full pull the diff exists to eliminate, reached by a second route. It fires not
    // only when the diff cannot run but also when it runs fine and merely fails to get its
    // first GATT write out, which under RF trouble is routine.
    //
    // Suppressing it there is safe: the diff ledgers every window before issuing anything, the
    // ledger's retry driver owns recovery. If the diff itself cannot run yet, the separate
    // pending flag forces a later live sample back through the diff instead of taking the cheap
    // incremental branch and incorrectly latching history complete.
    @Volatile private var historyDiffOwnsRecovery = false
    @Volatile private var historyDiffRetryPending = false
    private val historyHolesLock = Any()
    private val historyHoles = mutableListOf<HistoryHole>()
    // Set while we re-run service discovery AFTER auth (the sensor exposes a Service
    // Changed characteristic and may restructure its GATT post-auth, leaving the
    // pre-auth handles for the activation chars stale). onServicesDiscovered consumes it.
    @Volatile private var pendingActivation = false
    // Set by an explicit activation request to bypass a stale cloud/provisional start time.
    @Volatile private var forceActivationRequested = false
    @Volatile private var awaitingFreshActivationAdvertisement = false
    @Volatile private var pendingServiceDiscoveryGatt: BluetoothGatt? = null

    @Volatile private var lastGlucoseAtMs = 0L
    @Volatile private var lastGlucoseMmol = Float.NaN
    @Volatile private var lastGlucoseMgdl = 0f
    @Volatile private var lastRawCurrent = Float.NaN
    @Volatile private var lastAcceptedDataNo = -1
    @Volatile private var lastAcceptedSampleMs = 0L
    @Volatile private var lastAcceptedMmol = Float.NaN
    @Volatile private var lastAcceptedRawCurrent = 0
    @Volatile private var lastDataNo = -1
    @Volatile private var consecutiveCeilingFullDrops = 0
    @Volatile private var ceilingDistrusted = false
    // Set when the bounded wait for a fresh advertisement gave up. connectDevice()'s
    // pending-setup-activation branch would otherwise re-arm the very scan we just abandoned —
    // the direct connect would never run and the NFC prompt would be torn down each cycle.
    // Cleared whenever activation is (re)negotiated or a connection actually lands.
    @Volatile private var freshActivationAdvertisementAbandoned = false
    // The address that passed verifyDeviceSign for this sensor in this session, if any.
    @Volatile private var verifiedTransportAddress: String? = null
    @Volatile private var streamStartTimeMs = 0L
    // Whether streamStartTimeMs came from a wall-clock-corroborated anchor. Without this the slot
    // is owned by whichever anchor happens to land first — often the initial-history seed a few
    // seconds after connect, whose hint is derived from the cgm-info provisional — and since
    // resolveSampleTimeMs() returns early on any non-zero anchor, the reliable live anchor (and
    // so commitConfirmedActiveTime) could never run for the rest of the session.
    @Volatile private var streamStartReliable = false
    // First reliable activation start seen this session, held until a second one corroborates it.
    @Volatile private var pendingConfirmedStartMs = 0L
    @Volatile private var lastBleActivityAtMs = 0L
    private val recentlyRejectedSamples = object : LinkedHashMap<Int, RejectedSample>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, RejectedSample>?): Boolean = size > 64
    }

    override var viewMode: Int = 0

    private val livePollRunnable = Runnable {
        if (stop || phase != Phase.STREAMING || sessionKeyHex.isBlank() || commandStatus >= 4) return@Runnable
        val gatt = mBluetoothGatt ?: return@Runnable
        readLiveGlucose(gatt, "poll")
        scheduleLivePoll()
    }

    private val postHistoryLiveRunnable = Runnable {
        if (stop || phase != Phase.STREAMING || sessionKeyHex.isBlank() || commandStatus >= 4) return@Runnable
        val gatt = mBluetoothGatt ?: return@Runnable
        readLiveGlucose(gatt, "post-history")
        scheduleLivePoll()
    }

    private val pendingHistoryChunkRunnable = Runnable {
        requestPendingHistoryChunk()
    }

    private val endedHistoryBackfillRunnable = Runnable { runEndedHistoryBackfill() }

    private val initialHistoryRunnable = Runnable { runInitialHistoryBackfill() }

    private val historyWatchdogRunnable = Runnable { checkHistoryWatchdog() }

    private val holeRetryRunnable = Runnable { retryNextHistoryHole() }

    private fun runEndedHistoryBackfill() {
        if (stop || commandStatus < 4 || phase != Phase.STREAMING || sessionKeyHex.isBlank()) {
            return
        }
        if (actStep != ActStep.NONE && actStep != ActStep.DONE) {
            handler.postDelayed(endedHistoryBackfillRunnable, 1_000L)
            return
        }
        val endExclusive = lastDataNo + 1
        if (endExclusive <= 0) return
        val issued = requestRoomBackfillAfterLive(endExclusive, -1)
        Log.i(TAG, "ended history backfill endExclusive=$endExclusive issued=$issued")
    }

    private val connectionWatchdogRunnable = Runnable {
        checkConnectionWatchdog()
    }
    // Candidate discovery has no natural end: it waits for an advertisement that a sensor
    // which is already connected elsewhere, out of range, or simply not re-advertising will
    // never send, and the UI sits on "Looking for nearby transmitters..." indefinitely. Give
    // up after a bounded wait and fall back to the ordinary connect-by-known-address path.
    private val freshActivationAdvertisementTimeoutRunnable = Runnable {
        abandonFreshActivationAdvertisement("no matching advertisement within timeout")
    }
    private val serviceDiscoveryRunnable = Runnable {
        pendingServiceDiscoveryGatt?.let { startServiceDiscovery(it) }
    }
    private val serviceDiscoveryTimeoutRunnable = Runnable {
        if (stop || mBluetoothGatt == null) return@Runnable
        if ((phase == Phase.DISCOVERING && discoveryStarted) || pendingActivation) {
            recoverGattAndReconnect("service discovery callback timeout")
        }
    }

    private val notifyOrder = listOf(
        OttaiConstants.SERVICE_CGM to OttaiConstants.CHAR_GLUCOSE_HISTORY,
        OttaiConstants.SERVICE_CGM to OttaiConstants.CHAR_GLUCOSE_LIVE,
        OttaiConstants.SERVICE_DEVICE_INFO to OttaiConstants.CHAR_CGM_INFO_NOTIFY,
    )

    // ---- persistence ----

    fun restoreFromPersistence(context: Context) {
        val id = SerialNumber ?: return
        materials = OttaiRegistry.loadMaterials(context, id)
        provisionalActiveTimeMs = if (materials.activeTimeMs > 0L) {
            OttaiRegistry.saveProvisionalActiveTime(context, id, 0L)
            0L
        } else {
            OttaiRegistry.loadProvisionalActiveTime(context, id)
        }
        authKeys = materials.authKeys
        activatedMaxActiveMs = OttaiRegistry.loadAcceptedMaxActive(context, id)
        lastDataNo = OttaiRegistry.loadLastDataNo(context, id)
        synchronized(historyHolesLock) {
            historyHoles.clear()
            OttaiRegistry.loadHistoryHoles(context, id).forEach { (s, e, attempts) ->
                historyHoles += HistoryHole(s, e, attempts)
            }
        }
        // Restore the spike-filter baseline so the first sample after an app restart is
        // still checked against the last real reading (an isolated raw spike otherwise
        // slips through with an empty baseline). The adjacency window in
        // continuityRejectReason makes a stale (long-downtime) baseline a no-op.
        OttaiRegistry.loadContinuityBaseline(context, id)?.let {
            lastAcceptedDataNo = it.dataNo
            lastAcceptedSampleMs = it.sampleMs
            lastAcceptedMmol = it.mmol
            lastAcceptedRawCurrent = it.rawCurrent
        }
        // Auth V2 signs the cloud id bytes, not necessarily the Android BLE address.
        macBytes = runCatching { OttaiCrypto.hexToBytes(OttaiConstants.canonicalSensorId(id)) }.getOrDefault(ByteArray(0))
        ensureNativePresenceShell("restore")
    }

    private val reconnectRunnable = Runnable {
        if (stop) return@Runnable
        connectDevice(0)
    }
    private val pendingActivationNfcPromptRunnable = Runnable {
        val id = SerialNumber ?: return@Runnable
        if (stop ||
            !awaitingFreshActivationAdvertisement ||
            activateRequestedFor?.let { matchesManagedSensorId(it) } != true ||
            OttaiRegistry.loadApiBase(Applic.app) != OttaiConstants.API_BASE
        ) {
            return@Runnable
        }
        Log.i(TAG, "pending setup activation still has no advertisement; arming NFC wake")
        OttaiNfc.armForActivationRetry(id)
        Applic.app?.let { OttaiNfcWakeReminder.show(it, id) }
        constatstatusstr = appString(R.string.ottai_nfc_dump_armed, "Hold the sensor near NFC")
        UiRefreshBus.requestStatusRefresh()
    }

    private fun scheduleReconnect(reason: String, delay: Long = RECONNECT_DELAY_MS) {
        if (stop) return
        Log.i(TAG, "reconnect: $reason")
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delay)
    }

    private fun lossOfSignalText(): String = appString(R.string.lossofsignal, "Loss of signal")

    private fun noteGattActivity() {
        lastBleActivityAtMs = System.currentTimeMillis()
        if (constatstatusstr == "Loss of signal" || constatstatusstr == lossOfSignalText()) {
            constatstatusstr = ""
        }
        if (phase != Phase.IDLE) scheduleConnectionWatchdog()
    }

    private fun scheduleConnectionWatchdog() {
        handler.removeCallbacks(connectionWatchdogRunnable)
        handler.postDelayed(connectionWatchdogRunnable, CONNECTION_WATCHDOG_MS)
    }

    private fun connectionStaleThresholdMs(): Long =
        when {
            phase == Phase.STREAMING -> maxOf(STREAM_ACTIVITY_STALE_MS, livePollIntervalMs * 3L + 15_000L)
            phase == Phase.CONNECTING -> connectingStaleThresholdMs(connectStallStreak)
            else -> SETUP_ACTIVITY_STALE_MS
        }

    private fun lastConnectionActivityMs(): Long = maxOf(lastBleActivityAtMs, connectTime)

    private fun isConnectionStale(now: Long = System.currentTimeMillis()): Boolean {
        if (phase == Phase.IDLE) return false
        if (mBluetoothGatt == null && mActiveBluetoothDevice == null) return true
        val last = lastConnectionActivityMs()
        return last > 0L && now - last > connectionStaleThresholdMs()
    }

    private fun checkConnectionWatchdog() {
        if (stop || phase == Phase.IDLE) return
        val now = System.currentTimeMillis()
        if (isConnectionStale(now)) {
            if (phase == Phase.CONNECTING) connectStallStreak += 1
            val ageSec = ((now - lastConnectionActivityMs()).coerceAtLeast(0L)) / 1000L
            recoverGattAndReconnect("no GATT activity for ${ageSec}s")
        } else {
            scheduleConnectionWatchdog()
        }
    }

    @Synchronized
    private fun clearGattTransport(
        reason: String,
        markSignalLoss: Boolean,
        preserveActivationNegotiation: Boolean = false,
    ) {
        Log.w(TAG, "clearing Ottai GATT: $reason phase=$phase")
        if (markSignalLoss) constatstatusstr = lossOfSignalText()
        handler.removeCallbacks(livePollRunnable)
        handler.removeCallbacks(postHistoryLiveRunnable)
        handler.removeCallbacks(pendingHistoryChunkRunnable)
        handler.removeCallbacks(endedHistoryBackfillRunnable)
        handler.removeCallbacks(initialHistoryRunnable)
        handler.removeCallbacks(connectionWatchdogRunnable)
        handler.removeCallbacks(pendingActivationNfcPromptRunnable)
        handler.removeCallbacks(serviceDiscoveryRunnable)
        handler.removeCallbacks(serviceDiscoveryTimeoutRunnable)
        pendingServiceDiscoveryGatt = null
        clearPendingHistoryRange()
        svcDeviceInfo = null
        svcCgm = null
        svcAuth = null
        sessionKeyHex = ""
        authStep = AuthStep.NONE
        actStep = ActStep.NONE
        if (!preserveActivationNegotiation) resetActivationNegotiation()
        awaitingFreshActivationAdvertisement = false
        notifyEnableIndex = 0
        discoveryStarted = false
        pendingActivation = false
        commandStatus = -1
        phase = Phase.IDLE
        val oldGatt = mBluetoothGatt
        runCatching { disconnect() }
            .onFailure { Log.stack(TAG, "clearGattTransport(disconnect)", it) }
        mBluetoothGatt = null
        mActiveBluetoothDevice = null
        runCatching { oldGatt?.close() }
            .onFailure { Log.stack(TAG, "clearGattTransport(close)", it) }
        UiRefreshBus.requestStatusRefresh()
    }

    private fun recoverGattAndReconnect(reason: String) {
        val explicitActivationRequest =
            activateRequestedFor?.let { matchesManagedSensorId(it) } == true
        val scanForPendingActivation = OttaiConstants.shouldRescanPendingSetupActivation(
            commandStatus,
            explicitActivationRequest,
        )
        // Same rule as the disconnect handlers: whatever knownBleAddress() answers here may be a
        // candidate we were probing and never verified, and re-seeding it would reinstall a
        // stranger — with no admission check, since SensorBluetooth dispatches on address first.
        val previousAddress = addressAfterCandidate(knownBleAddress())
        // Read the latch BEFORE the teardown: on the pre-auth path clearGattTransport runs
        // resetActivationNegotiation, which clears it, so testing it afterwards was inert.
        val alreadyAbandoned = freshActivationAdvertisementAbandoned
        clearGattTransport(
            reason,
            markSignalLoss = true,
            preserveActivationNegotiation = activationNegotiationActive,
        )
        // Honour a bounded wait that already gave up: re-arming it here would restart the loop
        // abandonFreshActivationAdvertisement() exists to break, and tear down the NFC prompt.
        if (scanForPendingActivation && !alreadyAbandoned && previousAddress != null) {
            mActiveDeviceAddress = previousAddress
            val scanning = awaitFreshActivationAdvertisement()
            Log.w(TAG, "pre-auth transport stalled; authenticated advertisement scan " +
                "started=$scanning previousAddress=$previousAddress")
            if (scanning) {
                handler.postDelayed(pendingActivationNfcPromptRunnable, 10_000L)
                return
            }
        }
        scheduleReconnect(reason, 250L)
    }

    // ---- lifecycle ----

    override fun getService(): UUID = OttaiConstants.SERVICE_CGM

    @Synchronized
    override fun connectDevice(delayMillis: Long): Boolean {
        if (stop) return false
        val explicitActivationRequest =
            activateRequestedFor?.let { matchesManagedSensorId(it) } == true
        if (!activationCandidateDiscoveryPending &&
            !freshActivationAdvertisementAbandoned &&
            phase == Phase.IDLE &&
            mBluetoothGatt == null &&
            OttaiConstants.shouldRescanPendingSetupActivation(
                commandStatus,
                explicitActivationRequest,
            )
        ) {
            val scanning = awaitFreshActivationAdvertisement()
            Log.i(TAG, "pending setup activation uses authenticated advertisement scan " +
                "started=$scanning previousAddress=${knownBleAddress()}")
            if (scanning) return true
        }
        if (mActiveBluetoothDevice != null) awaitingFreshActivationAdvertisement = false
        if (phase != Phase.IDLE && (mBluetoothGatt != null || mActiveBluetoothDevice != null)) {
            if (isConnectionStale() || constatstatusstr == "Loss of signal" || constatstatusstr == lossOfSignalText()) {
                clearGattTransport(
                    "connect requested with stale transport",
                    markSignalLoss = true,
                    preserveActivationNegotiation = activationNegotiationActive,
                )
            } else {
                return true
            }
        }
        if (mActiveBluetoothDevice == null && !hydrateBluetoothDeviceFromAddress()) {
            phase = Phase.IDLE
            Log.i(TAG, "connect postponed — no Android BLE address for $SerialNumber")
            UiRefreshBus.requestStatusRefresh()
            return true
        }
        phase = Phase.CONNECTING
        lastBleActivityAtMs = System.currentTimeMillis()
        scheduleConnectionWatchdog()
        val scheduled = super.connectDevice(delayMillis)
        if (!scheduled && phase == Phase.CONNECTING) phase = Phase.IDLE
        return scheduled
    }

    @Synchronized
    override fun reconnect(now: Long): Boolean {
        if (stop) return true
        if (phase != Phase.IDLE && !isConnectionStale(now)) return true
        if (phase != Phase.IDLE || mBluetoothGatt != null || mActiveBluetoothDevice != null) {
            val ageSec = ((now - lastConnectionActivityMs()).coerceAtLeast(0L)) / 1000L
            clearGattTransport(
                "generic reconnect after stale activity age=${ageSec}s",
                markSignalLoss = true,
                preserveActivationNegotiation = activationNegotiationActive,
            )
        }
        return connectDevice(0)
    }

    override fun softDisconnect() {
        setPause(true)
        clearGattTransport("user disconnect", markSignalLoss = false)
        constatstatusstr = appString(R.string.status_disconnected, "Disconnected")
        UiRefreshBus.requestStatusRefresh()
    }

    override fun softReconnect() {
        setPause(false)
        needsActivationAttempted = false
        // Rapid repeated taps during an outage each destroyed the pending autoconnect that
        // was waiting for the sensor's advertisement, restarting the recovery from zero
        // (5 taps in 58s extended the worst logged outage). First tap acts immediately;
        // follow-ups within the window just make sure an attempt is running.
        val now = System.currentTimeMillis()
        if (phase != Phase.IDLE && now - lastUserReconnectAtMs < USER_RECONNECT_DEBOUNCE_MS) {
            Log.i(TAG, "user reconnect debounced — attempt already in flight phase=$phase")
            connectDevice(0)
            UiRefreshBus.requestStatusRefresh()
            return
        }
        lastUserReconnectAtMs = now
        clearGattTransport("user reconnect", markSignalLoss = false)
        connectDevice(0)
    }

    override fun close() {
        if (stop) {
            // Permanent shutdown: free() sets stop=true before calling close(), so
            // quit the HandlerThread here — otherwise it outlives the sensor object.
            // Transient close() calls (stop=false) keep the thread alive for reconnect.
            handler.removeCallbacksAndMessages(null)
            runCatching { handlerThread.quitSafely() }
        }
        super.close()
    }

    override fun onBluetoothAdapterUnavailable() {
        clearGattTransport("Bluetooth adapter off", markSignalLoss = false)
        constatstatusstr = appString(R.string.status_bluetooth_off, "Bluetooth off")
    }

    private fun knownBleAddress(): String? =
        OttaiConstants.normalizeBleAddress(mActiveDeviceAddress, allowPlain = false)
            ?: ownRecordAddress()

    /**
     * This sensor's own address as persisted in the registry. Unlike [knownBleAddress] it never
     * consults mActiveDeviceAddress, which candidate discovery retargets at whatever it probes —
     * so this is the one value a neighbouring sensor's advertisement cannot displace.
     */
    private fun ownRecordAddress(): String? =
        OttaiConstants.normalizeBleAddress(
            OttaiRegistry.findRecord(Applic.app, SerialNumber)?.address,
            allowPlain = false,
        )

    /**
     * The address the transport should hold after [candidate] disconnected. A candidate that has
     * already failed this sensor's auth signature must never be adopted: the disconnect handlers
     * re-arm discovery from mActiveDeviceAddress, so adopting it promotes a stranger to "home"
     * and the driver spends the rest of the session chasing a device it has already disproved.
     */
    /**
     * Trust is decided by one positive fact — did this exact address prove possession of this
     * sensor's auth material in this session — rather than by inferring "unverified" from the
     * activation state machine. Inferring it was wrong in both directions: an activation reset
     * cleared the marker while leaving a stranger installed (so it read as verified), and after
     * an abandoned scan the marker stuck (so an address the driver had just authenticated was
     * refused). Outside a probe there is nothing to verify against, and the registry record is
     * the durable truth.
     */
    private fun addressAfterCandidate(candidate: String?): String? {
        val normalized = OttaiConstants.normalizeBleAddress(candidate, allowPlain = false)
        val verified = normalized != null &&
            normalized.equals(verifiedTransportAddress, ignoreCase = true)
        return addressAfterCandidateFor(
            candidateAddress = normalized,
            candidateVerified = verified,
            homeAddress = null,
            recordAddress = ownRecordAddress(),
        )
    }

    private fun hydrateBluetoothDeviceFromAddress(): Boolean {
        val address = knownBleAddress() ?: return false
        mActiveDeviceAddress = address
        if (mActiveBluetoothDevice?.address?.equals(address, ignoreCase = true) == true) return true
        val adapter = runCatching {
            (Applic.app?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: BluetoothAdapter.getDefaultAdapter()
        }.getOrNull() ?: return false
        mActiveBluetoothDevice = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        return mActiveBluetoothDevice != null
    }

    /**
     * Fresh/NFC-woken sensors can advertise only briefly and may use a new Android
     * BLE address. Connect from the managed scan result instead of parking an
     * address-only autoConnect GATT. A changed address remains a transport candidate
     * until the sensor proves possession of this sensor's saved auth material.
     */
    @Synchronized
    fun awaitFreshActivationAdvertisement(): Boolean {
        val address = knownBleAddress() ?: return false
        if (phase != Phase.IDLE || mBluetoothGatt != null || mActiveBluetoothDevice != null) {
            clearGattTransport(
                "fresh activation waiting for exact advertisement",
                markSignalLoss = false,
            )
        }
        searchforDeviceAddress()
        activationRetryAddress = address
        // From the registry record, never from the address we may already have been retargeted
        // to: an earlier probe can have left a stranger in mActiveDeviceAddress.
        activationCandidateHomeAddress = ownRecordAddress() ?: address
        // Every deliberate arming path reaches here, so clearing the latch here — rather than
        // relying on an activation reset that preserveActivationNegotiation can skip — keeps a
        // user-driven retry working while connectDevice()'s automatic branch stays suppressed.
        freshActivationAdvertisementAbandoned = false
        activationCandidateDiscoveryPending = true
        activationCandidateProbeActive = false
        clearDeferredActivationCandidateCgmInfo()
        mActiveDeviceAddress = address
        awaitingFreshActivationAdvertisement = true
        constatstatusstr = appString(R.string.looking_for_transmitters, "Looking for nearby transmitters...")
        SensorBluetooth.blueone?.scanStarter(0L)
        handler.removeCallbacks(freshActivationAdvertisementTimeoutRunnable)
        handler.postDelayed(
            freshActivationAdvertisementTimeoutRunnable,
            FRESH_ACTIVATION_ADVERTISEMENT_TIMEOUT_MS,
        )
        UiRefreshBus.requestStatusRefresh()
        return true
    }

    /**
     * Leave candidate discovery and go back to connecting by this sensor's own address.
     * Safe to call from any state: it is a no-op unless discovery is armed, and it never
     * interrupts a probe that is mid-authentication (that probe either verifies and clears
     * discovery itself, or is rejected and re-arms the scan).
     */
    @Synchronized
    private fun abandonFreshActivationAdvertisement(reason: String) {
        if (stop || !activationCandidateDiscoveryPending) return
        if (activationCandidateProbeActive) {
            // A candidate is being authenticated right now — give it the full window again
            // rather than tearing the transport down mid-handshake.
            handler.postDelayed(
                freshActivationAdvertisementTimeoutRunnable,
                FRESH_ACTIVATION_ADVERTISEMENT_TIMEOUT_MS,
            )
            return
        }
        val home = activationCandidateHomeAddress ?: ownRecordAddress()
        activationCandidateDiscoveryPending = false
        awaitingFreshActivationAdvertisement = false
        freshActivationAdvertisementAbandoned = true
        clearDeferredActivationCandidateCgmInfo()
        if (home != null) {
            activationRetryAddress = home
            mActiveDeviceAddress = home
        }
        // The blacklist must not outlive the discovery episode. verifyDeviceSign() returns false
        // for reasons that are not "this is a stranger" — absent authKeys, a deviceParamIndex out
        // of range after key rotation, and its own acknowledged noisy polarity — and
        // shouldProbeActivationAdvertisement() consults the rejected set BEFORE the exact-address
        // match, so one bad signature read would otherwise blacklist our own sensor for the rest
        // of the negotiation with no way back. Trust now rests on verifiedTransportAddress, which
        // is a positive fact and cannot be poisoned by clearing this.
        rejectedActivationCandidateAddresses.clear()
        Log.w(TAG, "abandoning activation candidate discovery ($reason); " +
            "falling back to direct connect address=$home")
        UiRefreshBus.requestStatusRefresh()
        if (home != null) {
            hydrateBluetoothDeviceFromAddress()
            connectDevice(0)
        }
    }

    fun isSetupConnectionComplete(): Boolean =
        phase == Phase.STREAMING && sessionKeyHex.isNotBlank() && commandStatus >= 3

    override fun matchDeviceName(deviceName: String?, address: String?): Boolean {
        val scanned = OttaiConstants.normalizeBleAddress(address, allowPlain = false) ?: return false
        if (activationCandidateDiscoveryPending) {
            return selectActivationCandidate(scanned, deviceName)
        }
        val known = OttaiConstants.normalizeBleAddress(mActiveDeviceAddress, allowPlain = false)
            ?: OttaiConstants.normalizeBleAddress(
                OttaiRegistry.findRecord(Applic.app, SerialNumber)?.address,
                allowPlain = false,
            )
        return known != null && scanned.equals(known, ignoreCase = true)
    }

    override fun matchScanResult(result: ScanResult): Boolean {
        if (!activationCandidateDiscoveryPending) return false
        val scanned = OttaiConstants.normalizeBleAddress(
            runCatching { result.device.address }.getOrNull(),
            allowPlain = false,
        ) ?: return false
        val advertisedName = runCatching { result.scanRecord?.deviceName }.getOrNull()
            ?: runCatching { result.device.name }.getOrNull()
        return selectActivationCandidate(scanned, advertisedName)
    }

    /**
     * The name-only fallback ("any advertisement called *ottai*") exists for a sensor whose
     * BLE address changed across an activation. A sensor that already carries a persisted
     * activation start keeps its address, so admitting strangers by name can only mislead:
     * every neighbouring Ottai gets probed, fails the auth-signature check, and costs a
     * connect/disconnect cycle while our own sensor waits.
     */
    private fun allowActivationCandidateNameMatch(): Boolean {
        val ctx = Applic.app ?: return true
        val id = SerialNumber ?: return true
        return runCatching { OttaiRegistry.loadMaterials(ctx, id).activeTimeMs }.getOrDefault(0L) <= 0L
    }

    @Synchronized
    private fun selectActivationCandidate(scannedAddress: String, advertisedName: String?): Boolean {
        val expectedAddress = activationRetryAddress
        val selected = OttaiConstants.shouldProbeActivationAdvertisement(
            discoveryPending = activationCandidateDiscoveryPending,
            scannedAddress = scannedAddress,
            expectedAddress = expectedAddress,
            advertisedName = advertisedName,
            rejectedAddresses = rejectedActivationCandidateAddresses,
            allowNameMatch = allowActivationCandidateNameMatch(),
        )
        if (!selected) return false
        if (!scannedAddress.equals(expectedAddress, ignoreCase = true)) {
            Log.i(TAG, "probing NFC-woken Ottai address=$scannedAddress previous=$expectedAddress")
        }
        activationRetryAddress = scannedAddress
        mActiveDeviceAddress = scannedAddress
        return true
    }

    override fun setDeviceAddress(address: String?) {
        // A scan hit is only a transport candidate. Persist it after the candidate
        // proves possession of this sensor's auth material and the session completes.
        mActiveDeviceAddress = if (activationCandidateDiscoveryPending) {
            activationRetryAddress ?: mActiveDeviceAddress ?: address
        } else {
            address
        }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (stop) return
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                Log.i(TAG, "connected ${gatt.device?.address}")
                handler.removeCallbacks(reconnectRunnable)
                handler.removeCallbacks(pendingActivationNfcPromptRunnable)
                handler.removeCallbacks(serviceDiscoveryRunnable)
                handler.removeCallbacks(serviceDiscoveryTimeoutRunnable)
                pendingServiceDiscoveryGatt = null
                SerialNumber?.let { sensorId ->
                    OttaiNfc.disarmActivationRetry(sensorId)
                    Applic.app?.let { OttaiNfcWakeReminder.cancel(it, sensorId) }
                }
                mBluetoothGatt = gatt
                mActiveBluetoothDevice = gatt.device
                gatt.device?.address?.let { setDeviceAddress(it) }
                activationCandidateProbeActive = activationCandidateDiscoveryPending
                connectTime = System.currentTimeMillis()
                lastBleActivityAtMs = connectTime
                connectStallStreak = 0
                liveReadRetryCount = 0
                // A link landed, so the abandoned-scan latch has served its purpose: a later
                // activation attempt in this session may legitimately arm discovery again.
                freshActivationAdvertisementAbandoned = false
                if (constatstatusstr == "Loss of signal" || constatstatusstr == lossOfSignalText()) {
                    constatstatusstr = ""
                }
                phase = Phase.DISCOVERING
                authStep = AuthStep.NONE
                actStep = ActStep.NONE
                commandStatus = -1
                notifyEnableIndex = 0
                discoveryStarted = false
                roomBackfillChecked = false
                clearPendingHistoryRange()
                // CGM links drop quickly at default (balanced) params; request a fast
                // interval so auth/activation completes before the sensor drops idle.
                runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                // Start discovery after the final MTU callback settles. Some V1.5 sensors
                // report MTU twice about a second apart; discovery started from the first
                // callback then loses onServicesDiscovered when the second callback lands.
                // Do NOT call gatt.refresh(): it breaks discovery on the Mi9T.
                val mtuRequested = runCatching { gatt.requestMtu(MTU) }.getOrDefault(false)
                // Fallback: if onMtuChanged never fires, discover anyway.
                scheduleServiceDiscovery(
                    gatt,
                    if (mtuRequested) MTU_CALLBACK_FALLBACK_MS else 300L,
                )
                scheduleConnectionWatchdog()
                UiRefreshBus.requestStatusRefresh()
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                Log.i(TAG, "disconnected status=$status")
                if (status != 0) noteAbnormalDrop()
                liveReadRetryCount = 0
                val disconnectedAddress = OttaiConstants.normalizeBleAddress(
                    gatt.device?.address,
                    allowPlain = false,
                ) ?: knownBleAddress()
                val explicitActivationRequest =
                    activateRequestedFor?.let { matchesManagedSensorId(it) } == true
                val resumeActivation = activationNegotiationActive &&
                    activationRetryPending &&
                    maxActiveCandidateIndex in maxActiveCandidatesMs.indices
                val stoppedBeforeActivationCommand = OttaiConstants.commandNeedsActivation(commandStatus) &&
                    needsActivationAttempted && activationCommandSentAtMs <= 0L
                phase = Phase.IDLE
                handler.removeCallbacks(livePollRunnable)
                handler.removeCallbacks(postHistoryLiveRunnable)
                handler.removeCallbacks(pendingHistoryChunkRunnable)
                handler.removeCallbacks(endedHistoryBackfillRunnable)
                handler.removeCallbacks(initialHistoryRunnable)
                handler.removeCallbacks(connectionWatchdogRunnable)
                handler.removeCallbacks(serviceDiscoveryRunnable)
                handler.removeCallbacks(serviceDiscoveryTimeoutRunnable)
                pendingServiceDiscoveryGatt = null
                clearPendingHistoryRange()
                svcDeviceInfo = null; svcCgm = null; svcAuth = null
                sessionKeyHex = ""
                authStep = AuthStep.NONE
                actStep = ActStep.NONE
                pendingActivation = false
                runCatching { gatt.close() }
                mBluetoothGatt = null
                mActiveBluetoothDevice = null
                if (resumeActivation && activationCandidateDiscoveryPending) {
                    activationCandidateProbeActive = false
                    clearDeferredActivationCandidateCgmInfo()
                    searchforDeviceAddress()
                    mActiveDeviceAddress = addressAfterCandidate(activationRetryAddress)
                    Log.i(TAG, "activation retry candidate disconnected status=$status; resuming authenticated scan")
                    SensorBluetooth.blueone?.scanStarter(250L)
                } else if (resumeActivation && status == 147) {
                    beginActivationCandidateDiscovery(
                        "direct reconnect timed out with status=147",
                    )
                } else if (resumeActivation) {
                    mActiveDeviceAddress = addressAfterCandidate(activationRetryAddress)
                    Log.i(TAG, "activation lifetime retry requires fresh auth; reconnecting for " +
                        "attempt=${maxActiveCandidateIndex + 1}/${maxActiveCandidatesMs.size} " +
                        "address=$mActiveDeviceAddress")
                    scheduleReconnect("activation lifetime retry", 1_000L)
                } else if (stoppedBeforeActivationCommand || activationFailed) {
                    failActivation("connection closed before activation command was accepted")
                    Log.e(TAG, "activation did not reach command=3; automatic reconnect stopped " +
                        "until the user requests Reconnect")
                } else if (OttaiConstants.shouldRescanPendingSetupActivation(
                        commandStatus,
                        explicitActivationRequest,
                    )
                ) {
                    mActiveDeviceAddress = addressAfterCandidate(disconnectedAddress)
                    val scanning = awaitFreshActivationAdvertisement()
                    Log.w(TAG, "setup activation disconnected before command status=$status; " +
                        "fresh advertisement scan started=$scanning address=$mActiveDeviceAddress")
                    if (scanning) {
                        handler.removeCallbacks(pendingActivationNfcPromptRunnable)
                        handler.postDelayed(pendingActivationNfcPromptRunnable, 10_000L)
                    } else {
                        scheduleReconnect("setup activation pre-status disconnect", 1_000L)
                    }
                } else if (!stop) {
                    scheduleReconnect("gatt disconnect")
                }
                UiRefreshBus.requestStatusRefresh()
            }
        }
    }

    private fun noteAbnormalDrop(nowMs: Long = System.currentTimeMillis()) {
        synchronized(abnormalDropAtMs) {
            abnormalDropAtMs.addLast(nowMs)
            while (abnormalDropAtMs.isNotEmpty() &&
                nowMs - abnormalDropAtMs.first() > UNSTABLE_LINK_WINDOW_MS
            ) {
                abnormalDropAtMs.removeFirst()
            }
        }
    }

    private fun linkUnstable(nowMs: Long): Boolean =
        synchronized(abnormalDropAtMs) { isLinkUnstable(abnormalDropAtMs.toList(), nowMs) }

    override fun onConnectionParamsUpdated(
        gatt: BluetoothGatt,
        interval: Int,
        latency: Int,
        timeout: Int,
        status: Int,
    ) {
        if (stop || status != 0 || gatt !== mBluetoothGatt) return
        logi(TAG) { "conn params interval=$interval latency=$latency timeout=$timeout" }
        val now = System.currentTimeMillis()
        if (!shouldHoldFastParams(interval, latency, linkUnstable(now), now, lastPriorityReassertAtMs)) return
        lastPriorityReassertAtMs = now
        Log.i(TAG, "unstable link: holding fast conn params over interval=$interval latency=$latency timeout=$timeout")
        runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        Log.d(TAG, "mtu=$mtu status=$status")
        noteGattActivity()
        scheduleServiceDiscovery(gatt, MTU_SETTLE_BEFORE_DISCOVERY_MS)
    }

    private fun scheduleServiceDiscovery(gatt: BluetoothGatt, delayMs: Long) {
        if (gatt !== mBluetoothGatt || phase != Phase.DISCOVERING || discoveryStarted) return
        pendingServiceDiscoveryGatt = gatt
        handler.removeCallbacks(serviceDiscoveryRunnable)
        handler.postDelayed(serviceDiscoveryRunnable, delayMs)
    }

    /** Idempotent: kicks off service discovery exactly once per connection. */
    private fun startServiceDiscovery(gatt: BluetoothGatt) {
        if (gatt !== mBluetoothGatt || discoveryStarted || phase != Phase.DISCOVERING) return
        handler.removeCallbacks(serviceDiscoveryRunnable)
        pendingServiceDiscoveryGatt = null
        discoveryStarted = true
        val started = runCatching { gatt.discoverServices() }.getOrDefault(false)
        Log.i(TAG, "discoverServices() started=$started")
        if (started) {
            handler.removeCallbacks(serviceDiscoveryTimeoutRunnable)
            handler.postDelayed(serviceDiscoveryTimeoutRunnable, SERVICE_DISCOVERY_TIMEOUT_MS)
        } else {
            discoveryStarted = false
            scheduleServiceDiscovery(gatt, 800L)
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (gatt !== mBluetoothGatt) {
            Log.w(TAG, "ignoring service discovery callback from stale GATT")
            return
        }
        handler.removeCallbacks(serviceDiscoveryRunnable)
        handler.removeCallbacks(serviceDiscoveryTimeoutRunnable)
        pendingServiceDiscoveryGatt = null
        noteGattActivity()
        if (status != BluetoothGatt.GATT_SUCCESS) {
            recoverGattAndReconnect("discover failed $status")
            return
        }
        // One-time GATT map dump: service UUID + instanceId (≈ATT handle), then each
        // characteristic's short UUID, handle and properties. Reveals duplicate-UUID
        // services and the real handle of b8fd9848/6aa799b6 (the activation writes that
        // fail) so we can tell a resolution bug from the sensor rejecting the write.
        runCatching {
            for (s in gatt.services) {
                val chars = s.characteristics.joinToString(" ") {
                    "${it.uuid.toString().take(8)}#${it.instanceId}/0x${it.properties.toString(16)}"
                }
                Log.i(TAG, "GATT svc ${s.uuid.toString().take(8)}#${s.instanceId} [$chars]")
            }
        }
        svcDeviceInfo = gatt.getService(OttaiConstants.SERVICE_DEVICE_INFO)
        svcCgm = gatt.getService(OttaiConstants.SERVICE_CGM)
        svcAuth = gatt.getService(OttaiConstants.SERVICE_AUTH)
        if (svcCgm == null || svcAuth == null || svcDeviceInfo == null) {
            Log.e(TAG, "missing Ottai services (cgm=$svcCgm auth=$svcAuth info=$svcDeviceInfo)")
            scheduleReconnect("missing services"); return
        }
        // Post-auth re-discovery (requested by requestActivation): handles above are now
        // fresh — start the activation writes. The GATT map dumped above lets us compare
        // b8fd9848's handle pre- vs post-auth.
        if (pendingActivation) {
            pendingActivation = false
            Log.i(TAG, "post-auth re-discovery complete — starting activation")
            startActivationWrites(gatt)
            return
        }
        authKeys = materials.authKeys
        if (authKeys == null) {
            Log.w(TAG, "no auth keys (cloud materials missing) — cannot authenticate")
            // Stay connected but idle; the driver needs cloud bind first.
            phase = Phase.IDLE
            return
        }
        phase = Phase.ENABLING_NOTIFY
        notifyEnableIndex = 0
        enableNextNotification(gatt)
    }

    private fun enableNextNotification(gatt: BluetoothGatt) {
        if (notifyEnableIndex >= notifyOrder.size) {
            startAuth(gatt)
            return
        }
        val (svcUuid, chUuid) = notifyOrder[notifyEnableIndex]
        val ch = gatt.getService(svcUuid)?.getCharacteristic(chUuid)
        if (ch == null) {
            notifyEnableIndex++
            enableNextNotification(gatt)
            return
        }
        gatt.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(OttaiConstants.CCCD)
        if (cccd != null) {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            runCatching { gatt.writeDescriptor(cccd) }
        } else {
            notifyEnableIndex++
            enableNextNotification(gatt)
        }
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        noteGattActivity()
        if (phase == Phase.ENABLING_NOTIFY) {
            notifyEnableIndex++
            enableNextNotification(gatt)
        }
    }

    // ---- Auth V2 ----

    private fun startAuth(gatt: BluetoothGatt) {
        phase = Phase.AUTH
        authStep = AuthStep.READ_DEVICE_TIME
        readChar(gatt, OttaiConstants.SERVICE_DEVICE_INFO, OttaiConstants.CHAR_CURRENT_TIME)
    }

    @Suppress("DEPRECATION")
    override fun onCharacteristicRead(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
        val value = ch.value ?: ByteArray(0)
        noteGattActivity()
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "read err ${ch.uuid.toString().take(8)} status=$status phase=$phase")
            // The maxActive read is an optional display-only probe — never let its failure
            // tear down a healthy stream.
            if (ch.uuid == OttaiConstants.CHAR_MAX_ACTIVE_TIME) return
            if (phase == Phase.STREAMING || phase == Phase.AUTH) recoverGattAndReconnect("read failed status=$status")
            return
        }
        when (ch.uuid) {
            OttaiConstants.CHAR_CURRENT_TIME -> {
                deviceTimeBytes = value.copyOf()
                authStep = AuthStep.READ_DEVICE_PARAM
                readChar(gatt, OttaiConstants.SERVICE_AUTH, OttaiConstants.CHAR_AUTH_DEVICE_PARAM)
            }
            OttaiConstants.CHAR_AUTH_DEVICE_PARAM -> {
                parseDeviceAuthParam(value)
                authStep = AuthStep.READ_DEVICE_SIGN
                readChar(gatt, OttaiConstants.SERVICE_AUTH, OttaiConstants.CHAR_AUTH_SIGN)
            }
            OttaiConstants.CHAR_AUTH_SIGN -> {
                if (!verifyDeviceSign(value) && activationCandidateProbeActive) {
                    rejectActivationCandidate(gatt)
                    return
                }
                writeAppParam(gatt)
            }
            OttaiConstants.CHAR_MAX_ACTIVE_TIME -> handleMaxActiveRead(value)
            OttaiConstants.CHAR_CGM_INFO_NOTIFY -> handleCgmInfo(value, source = "read")
            OttaiConstants.CHAR_GLUCOSE_LIVE -> handleGlucosePayload(value, live = true, source = "read")
            OttaiConstants.CHAR_COMMAND -> {
                val status = if (value.isNotEmpty()) value[0].toInt() and 0xFF else -1
                Log.i(TAG, "cmd/activation status=$status (official: 3=activated, <3=needs activation) raw=${OttaiCrypto.bytesToHex(value)}")
                handleCommandStatus(gatt, status)
            }
        }
    }

    private fun handleCommandStatus(gatt: BluetoothGatt, status: Int) {
        val previous = commandStatus
        commandStatus = status
        if (status < 0) {
            Log.w(TAG, "invalid empty command status; activation not attempted")
            UiRefreshBus.requestStatusRefresh()
            return
        }
        if (OttaiConstants.commandNeedsActivation(status)) {
            if (activationCommandAcknowledged) {
                Log.w(TAG, "activation command was acknowledged but sensor still reports status=$status; " +
                    "discarding staged maxActive")
                clearStagedActivationLifetime()
            }
            repairPoisonedProvisionalActiveTime()
            handler.removeCallbacks(livePollRunnable)
            handler.removeCallbacks(postHistoryLiveRunnable)
            handler.removeCallbacks(endedHistoryBackfillRunnable)
            val explicitRequest = activateRequestedFor?.let { matchesManagedSensorId(it) } == true
            val activationBusy = pendingActivation || (actStep != ActStep.NONE && actStep != ActStep.DONE)
            val resumeNegotiation = activationNegotiationActive && activationRetryPending
            if (resumeNegotiation && !activationBusy) {
                needsActivationAttempted = true
                Log.i(TAG, "sensor still needs activation status=$status; resuming lifetime " +
                    "attempt=${maxActiveCandidateIndex + 1}/${maxActiveCandidatesMs.size} after fresh auth")
                handler.postDelayed({
                    val started = runCatching { requestForceActivation() }.getOrDefault(false)
                    if (!started) {
                        failActivation("could not resume lifetime negotiation after authentication")
                    }
                }, 250L)
            } else if (OttaiConstants.shouldStartActivation(status, explicitRequest) &&
                !needsActivationAttempted && !activationBusy) {
                activateRequestedFor = null
                needsActivationAttempted = true
                beginActivationNegotiation()
                Log.i(TAG, "sensor command status=$status and user requested activation; " +
                    "starting official activation sequence")
                handler.postDelayed({
                    val started = runCatching { requestForceActivation() }.getOrDefault(false)
                    if (!started) {
                        needsActivationAttempted = false
                        failActivation("could not start after sensor reported status=$status")
                    }
                }, 250L)
            } else if (explicitRequest || activationBusy || needsActivationAttempted) {
                Log.i(TAG, "sensor still needs activation status=$status " +
                    "(requested=$explicitRequest attempted=$needsActivationAttempted busy=$activationBusy)")
            } else {
                Log.i(TAG, "sensor needs activation status=$status; awaiting explicit user action")
            }
            UiRefreshBus.requestStatusRefresh()
            return
        }
        if (status == 3) {
            commitStagedActivationLifetime(status)
            if (activateRequestedFor?.let { matchesManagedSensorId(it) } == true) {
                activateRequestedFor = null
            }
            handler.removeCallbacks(pendingActivationNfcPromptRunnable)
            SerialNumber?.let { sensorId ->
                OttaiNfc.disarmActivationRetry(sensorId)
                Applic.app?.let { OttaiNfcWakeReminder.cancel(it, sensorId) }
            }
            handler.removeCallbacks(endedHistoryBackfillRunnable)
            needsActivationAttempted = false
            resetActivationNegotiation()
            if (previous >= 4) {
                Log.i(TAG, "ended-sensor recovery accepted; normal streaming restored")
            }
            if (previous != 3) startStreamingAfterCommandStatus(gatt)
            UiRefreshBus.requestStatusRefresh()
            return
        }

        handler.removeCallbacks(livePollRunnable)
        handler.removeCallbacks(postHistoryLiveRunnable)
        clearStagedActivationLifetime()
        if (activateRequestedFor?.let { matchesManagedSensorId(it) } == true) {
            activateRequestedFor = null
        }
        handler.removeCallbacks(pendingActivationNfcPromptRunnable)
        SerialNumber?.let { sensorId ->
            OttaiNfc.disarmActivationRetry(sensorId)
            Applic.app?.let { OttaiNfcWakeReminder.cancel(it, sensorId) }
        }
        Log.w(TAG, "sensor ended cmd=$status; no lifetime write will be attempted automatically")
        // The ended-history path needs the final dataNo from the live buffer before it can
        // request the missing range. Reading this characteristic is safe after expiry;
        // handleEndedLiveBuffer indexes it without publishing a new glucose value.
        handler.postDelayed({
            if (commandStatus >= 4) {
                runCatching { readLiveGlucose(gatt, "ended-history-index") }
            }
        }, 500L)
        UiRefreshBus.requestStatusRefresh()
    }

    private fun startStreamingAfterCommandStatus(gatt: BluetoothGatt) {
        handler.postDelayed({ runCatching { readCgmInfo(gatt) } }, 250L)
        handler.postDelayed({
            runCatching {
                readLiveGlucose(gatt, "command-status-3")
                scheduleLivePoll()
            }
        }, 700L)
        // Recover the real activated lifetime for a sensor we didn't just activate (e.g.
        // already streaming from a previous session): read maxActive back off the sensor.
        // One-shot — once known it is persisted and this is skipped. A failed read is
        // swallowed in onCharacteristicRead so it never destabilizes the stream.
        if (activatedMaxActiveMs <= 0L) {
            handler.postDelayed({
                runCatching { readChar(gatt, OttaiConstants.SERVICE_DEVICE_INFO, OttaiConstants.CHAR_MAX_ACTIVE_TIME) }
            }, 1_200L)
        }
        // Independent history backfill: don't wait on the first accepted live sample. If the
        // live path fills history first it flips roomBackfillChecked and this no-ops; otherwise
        // this drives the full/gap backfill off the real lastDataNo or the activation-age estimate.
        initialHistoryAttempt = 0
        handler.removeCallbacks(initialHistoryRunnable)
        handler.postDelayed(initialHistoryRunnable, INITIAL_HISTORY_DELAY_MS)
        // Holes restored from prefs need a driver too: without this, a restart into a
        // session that never starts another chunk chain would leave them unretried.
        // Delayed past the initial backfill so it keeps chain priority (the retry never
        // preempts an active chain and re-arms itself off chain completion anyway).
        scheduleHoleRetry(delayMs = INITIAL_HISTORY_DELAY_MS + HISTORY_HOLE_RETRY_DELAY_MS)
    }

    private fun parseDeviceAuthParam(v: ByteArray) {
        // [0]=index, [1..3]=time(3), [4..35]=pubX(32), [36..67]=pubY(32)
        if (v.size < 68) { Log.w(TAG, "device param short ${v.size}"); return }
        deviceParamIndex = v[0].toInt() and 0xFF
        deviceParamTime = v.copyOfRange(1, 4)
        devicePubX = v.copyOfRange(4, 36)
        devicePubY = v.copyOfRange(36, 68)
    }

    private fun verifyDeviceSign(deviceSign: ByteArray): Boolean {
        val keys = authKeys ?: return false
        if (deviceParamIndex !in keys.indices) {
            Log.w(TAG, "device index oob")
            return false
        }
        val authKeyHex = OttaiCrypto.bytesToHex(keys[deviceParamIndex])
        val ok = OttaiBleAuth.verifyDeviceSign(
            deviceSign, authKeyHex, OttaiCrypto.bytesToHex(macBytes),
            devicePubX, devicePubY, deviceParamTime,
        )
        // Per decompile notes the boolean polarity is noisy; log, do not hard-fail.
        Log.i(TAG, "device sign verify=$ok")
        if (ok) {
            // The one durable fact about trust: this address proved possession of THIS sensor's
            // auth material. Recorded independently of the activation state machine, whose flags
            // are cleared by a dozen teardown paths, so addressAfterCandidate() cannot be fooled
            // by a stale marker in either direction.
            OttaiConstants.normalizeBleAddress(mBluetoothGatt?.device?.address, allowPlain = false)
                ?.let { verifiedTransportAddress = it }
        }
        if (ok && activationCandidateProbeActive) {
            val verifiedAddress = OttaiConstants.normalizeBleAddress(
                mBluetoothGatt?.device?.address,
                allowPlain = false,
            )
            activationRetryAddress = verifiedAddress
            activationCandidateProbeActive = false
            activationCandidateDiscoveryPending = false
            activationCandidateHomeAddress = null
            handler.removeCallbacks(freshActivationAdvertisementTimeoutRunnable)
            rejectedActivationCandidateAddresses.clear()
            mActiveDeviceAddress = verifiedAddress
            SerialNumber?.let { sensorId ->
                Applic.app?.let { OttaiNfcWakeReminder.cancel(it, sensorId) }
                OttaiNfc.disarmActivationRetry(sensorId)
            }
            Log.i(TAG, "activation retry candidate verified address=$verifiedAddress")
            replayDeferredActivationCandidateCgmInfo()
        }
        return ok
    }

    private fun rejectActivationCandidate(gatt: BluetoothGatt) {
        val address = OttaiConstants.normalizeBleAddress(gatt.device?.address, allowPlain = false)
        if (address != null) rejectedActivationCandidateAddresses += address
        activationCandidateProbeActive = false
        clearDeferredActivationCandidateCgmInfo()
        // Point the transport back at our own sensor. Without this the rejected stranger's
        // address stays in activationRetryAddress/mActiveDeviceAddress, the disconnect
        // handler re-arms the scan around it, and knownBleAddress() keeps answering with a
        // device we have just proven is not ours. Falls back to the registry record so this
        // still works on the entry path that did not capture a home address.
        (activationCandidateHomeAddress ?: ownRecordAddress())?.let { home ->
            activationCandidateHomeAddress = home
            activationRetryAddress = home
            mActiveDeviceAddress = home
        }
        Log.w(TAG, "activation retry candidate rejected by device signature address=$address")
        handler.post { runCatching { gatt.disconnect() } }
    }

    private fun writeAppParam(gatt: BluetoothGatt) {
        val keys = authKeys ?: return
        val kp = OttaiBleAuth.generateKeyPair()
        appPrivate = kp.private as ECPrivateKey
        val (x, y) = OttaiBleAuth.publicCoords(kp.public as ECPublicKey)
        appPubX = x; appPubY = y
        appTime3 = OttaiBleAuth.appTime3(deviceTimeBytes)
        // The sensor rotates which of its keys is valid over its life (~day 15 for a
        // 30-day unit) and broadcasts the current one as deviceParamIndex. Mirror it for
        // the app's half of the handshake — a random pick auth'd early (one lucky index
        // held while streaming) but failed intermittently after a rotation, recovering
        // only by chance on reconnect. Coerce in case of a malformed device index.
        appIndex = deviceParamIndex.coerceIn(0, keys.size - 1)
        val param = OttaiBleAuth.appAuthParameter(appIndex, appTime3, appPubX, appPubY)
        authStep = AuthStep.WRITE_APP_PARAM
        writeChar(gatt, OttaiConstants.SERVICE_AUTH, OttaiConstants.CHAR_AUTH_APP_PARAM, param)
    }

    @Suppress("DEPRECATION")
    override fun onCharacteristicWrite(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
        noteGattActivity()
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "write err ${ch.uuid.toString().take(8)} status=$status actStep=$actStep")
            if (actStep == ActStep.MAX_ACTIVE &&
                ch.uuid == OttaiConstants.CHAR_MAX_ACTIVE_TIME &&
                retryMaxActiveTime(gatt, status)
            ) {
                return
            }
            if (actStep != ActStep.NONE) {
                failActivation(
                    "aborted after $actStep write error (status=$status); " +
                        "sensor rejected the advertised characteristic",
                )
            }
            return
        }
        when {
            ch.uuid == OttaiConstants.CHAR_HISTORY_REQUEST -> {
                Log.i(TAG, "history request write accepted")
            }
            authStep == AuthStep.WRITE_APP_PARAM && ch.uuid == OttaiConstants.CHAR_AUTH_APP_PARAM -> {
                val keys = authKeys ?: return
                val authKeyHex = OttaiCrypto.bytesToHex(keys[appIndex])
                val sign = OttaiBleAuth.authSignHex(authKeyHex, OttaiCrypto.bytesToHex(macBytes), appPubX, appPubY, appTime3)
                authStep = AuthStep.WRITE_APP_SIGN
                writeChar(gatt, OttaiConstants.SERVICE_AUTH, OttaiConstants.CHAR_AUTH_SIGN, sign)
            }
            authStep == AuthStep.WRITE_APP_SIGN && ch.uuid == OttaiConstants.CHAR_AUTH_SIGN -> {
                deriveSession()
                authStep = AuthStep.DONE
                phase = Phase.STREAMING
                lastBleActivityAtMs = System.currentTimeMillis()
                val sessionOk = sessionKeyHex.isNotBlank()
                Log.i(TAG, "auth complete; session=${if (sessionOk) "ok" else "FAILED"}")
                if (sessionOk) {
                    val address = OttaiConstants.normalizeBleAddress(gatt.device?.address, allowPlain = false)
                    val context = Applic.app
                    val id = SerialNumber
                    if (address != null && context != null && id != null) {
                        if (dataptr != 0L) {
                            runCatching { Natives.setDeviceAddress(dataptr, address) }
                                .onFailure { Log.stack(TAG, "persist authenticated Ottai address", it) }
                        }
                        OttaiRegistry.ensureSensorRecord(
                            context,
                            id,
                            address,
                            OttaiConstants.DEFAULT_DISPLAY_NAME,
                        )
                    }
                    scheduleConnectionWatchdog()
                }
                UiRefreshBus.requestStatusRefresh()
                // Read the activation-state byte exactly like the official app (CgmActivate
                // readCmd → 0000181f/d78d0706: bArr[0], where 3 = activated, <3 = needs
                // activation). Reads on this post-CCCD char may work even though writes hit
                // Invalid Handle — and this tells us whether the empty glucose is because
                // the sensor was never started vs. a dead element.
                if (sessionOk) readChar(gatt, OttaiConstants.SERVICE_CGM, OttaiConstants.CHAR_COMMAND)
                // Do not infer activation from cloud or provisional timestamps. The command
                // byte read above is the sensor's authoritative state: 0..2 activates, 3
                // streams, and 4+ follows the ended-sensor path.
            }
            // Any successful activation write advances the sequence (RTC/maxActive on
            // 0000180a, destruction on 84c5b711, cmd on 0000181f).
            actStep != ActStep.NONE -> {
                if (actStep == ActStep.MAX_ACTIVE &&
                    ch.uuid == OttaiConstants.CHAR_MAX_ACTIVE_TIME
                ) {
                    markMaxActiveAccepted()
                }
                advanceActivation(gatt)
            }
        }
    }

    private fun deriveSession() {
        val priv = appPrivate ?: return
        sessionKeyHex = OttaiBleAuth.deriveSessionKey(
            OttaiCrypto.bytesToHex(devicePubX),
            OttaiCrypto.bytesToHex(devicePubY),
            priv,
        ).orEmpty()
        // DEBUG (remove): dump the session key so a captured live/history cipher can be
        // decrypted offline in every mode — to settle whether records are genuinely empty
        // or our AES-ECB path is wrong (different ciphers → identical empty payload is
        // impossible for correct ECB). Local single-sensor RE only; not a normal secret log.
        Log.i(TAG, "DEBUG sessionKey=$sessionKeyHex")
    }

    // ---- streaming ----

    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        val value = ch.value ?: return
        noteGattActivity()
        when (ch.uuid) {
            OttaiConstants.CHAR_GLUCOSE_LIVE -> handleGlucosePayload(value, live = true, source = "notify")
            OttaiConstants.CHAR_GLUCOSE_HISTORY -> handleGlucosePayload(value, live = false, source = "notify")
            OttaiConstants.CHAR_CGM_INFO_NOTIFY -> handleCgmInfo(value, source = "notify")
        }
    }

    private fun handleCgmInfo(value: ByteArray, source: String) {
        logi(TAG) { "cgm-info $source len=${value.size} hex=${OttaiCrypto.bytesToHex(value).take(160)}" }
        if (activationCandidateProbeActive) {
            synchronized(deferredActivationCandidateCgmInfo) {
                if (deferredActivationCandidateCgmInfo.size >= 8) {
                    deferredActivationCandidateCgmInfo.removeAt(0)
                }
                deferredActivationCandidateCgmInfo += value.copyOf() to source
            }
            Log.i(TAG, "cgm-info deferred until activation candidate authentication")
            return
        }
        maybeUpdateLivePollInterval(value)
        maybeSeedProvisionalActiveTimeFromCgmInfo(value)
        // NOTE: the 0x0477 cgm-info packet is NOT glucose. Its 8-byte records are
        // [epochSec LE :4][state LE :2][flags :2], and the state field only ever takes a
        // few discrete values (e.g. 4104/4115/4118) that do NOT track real glucose — a
        // reference CGM moved 4..6 mmol/L while this stayed flat. Real glucose is the
        // 12-byte live/history record (rawCurrent → method/coefficient → mmol/L), which
        // this sensor returns as an EMPTY frame on every read (never a record). So there
        // is nothing to emit from cgm-info; do not fabricate a reading here.
    }

    private fun handleMaxActiveRead(value: ByteArray) {
        val secs = decodeMaxActiveSeconds(value)
        if (secs == null) {
            Log.w(TAG, "maxActive read: undecodable len=${value.size} hex=${OttaiCrypto.bytesToHex(value).take(48)}")
            return
        }
        val days = secs / 86_400L
        Log.i(TAG, "maxActive read: ${secs}s (~${days}d)")
        // Accept only a plausible activation window; guards a wrong read format / stray bytes.
        if (secs < 10L * 86_400L || secs > 45L * 86_400L) {
            Log.w(TAG, "maxActive read out of plausible range — ignoring")
            return
        }
        val ms = secs * 1000L
        if (ms == activatedMaxActiveMs) return
        activatedMaxActiveMs = ms
        val id = SerialNumber.orEmpty()
        Applic.app?.let { ctx -> if (id.isNotBlank()) OttaiRegistry.saveAcceptedMaxActive(ctx, id, ms) }
        applyActivatedWearToNative(id)
        UiRefreshBus.requestStatusRefresh()
        Log.i(TAG, "activated lifetime recovered from sensor = ${days}d")
    }

    // b8fd9848 holds the activated maxActive duration. The write format is
    // AES-ECB(zeroPad16(secondsLE)); a read returns the same cipher, so decrypt and read
    // the 8-byte LE seconds. Fall back to a plaintext LE read if a firmware returns it raw.
    private fun decodeMaxActiveSeconds(value: ByteArray): Long? {
        if (value.isEmpty()) return null
        OttaiCrypto.decryptPayload(value, sessionKeyHex)?.let { pt -> readSecondsLE(pt)?.let { return it } }
        return readSecondsLE(value)
    }
    private fun readSecondsLE(b: ByteArray): Long? {
        if (b.size < 4) return null
        val n = if (b.size >= 8) 8 else 4
        var v = 0L
        for (i in 0 until n) v = v or ((b[i].toLong() and 0xFF) shl (i * 8))
        return v.takeIf { it > 0L }
    }

    private fun maybeUpdateLivePollInterval(value: ByteArray) {
        if (value.size < 4) return
        if (value[0].toInt() != 0x07 || value[1].toInt() != 0x77) return
        val seconds = le16(value[2], value[3])
        if (seconds !in 5..3600) return
        val next = (seconds * 1000L).coerceAtMost(MAX_LIVE_POLL_INTERVAL_MS)
        if (next == livePollIntervalMs) return
        livePollIntervalMs = next
        Log.i(TAG, "live poll interval=${next / 1000}s from cgm-info raw=${seconds}s")
        if (phase == Phase.STREAMING && sessionKeyHex.isNotBlank()) scheduleLivePoll(next)
    }

    private fun maybeSeedProvisionalActiveTimeFromCgmInfo(value: ByteArray) {
        if (materials.activeTimeMs > 0L || value.size < 22) return
        if (value[0].toInt() != 0x04 || value[1].toInt() != 0x77) return
        val nowSec = System.currentTimeMillis() / 1000L
        val minSec = nowSec - 30L * 24L * 3600L
        val candidates = mutableListOf<Long>()
        var offset = 10
        while (offset + 4 <= value.size) {
            val epoch = uint32Le(value, offset)
            if (epoch in minSec..(nowSec + 3600L)) candidates.add(epoch)
            offset += 4
        }
        val activeSec = candidates.minOrNull() ?: return
        latestCgmInfoActiveTimeCandidateMs = activeSec * 1000L
        setProvisionalActiveTime(latestCgmInfoActiveTimeCandidateMs, "cgm-info")
    }

    private fun replayDeferredActivationCandidateCgmInfo() {
        val deferred = synchronized(deferredActivationCandidateCgmInfo) {
            deferredActivationCandidateCgmInfo.toList().also {
                deferredActivationCandidateCgmInfo.clear()
            }
        }
        deferred.forEach { (value, source) ->
            handleCgmInfo(value, source = "verified-$source")
        }
    }

    private fun clearDeferredActivationCandidateCgmInfo() {
        synchronized(deferredActivationCandidateCgmInfo) {
            deferredActivationCandidateCgmInfo.clear()
        }
    }

    private fun repairPoisonedProvisionalActiveTime() {
        if (materials.activeTimeMs > 0L) return
        val observed = latestCgmInfoActiveTimeCandidateMs
        val current = provisionalActiveTimeMs
        if (observed <= 0L || current <= 0L || observed <= current) return
        val expectedLifetime = materials.activeExpireTimeMs
            .takeIf { it > 0L }
            ?: OttaiConstants.DEFAULT_ACTIVE_EXPIRE_MS
        if (System.currentTimeMillis() - current <= expectedLifetime + 24L * 3600L * 1000L) return

        provisionalActiveTimeMs = observed
        val id = SerialNumber.orEmpty()
        Applic.app?.let { context ->
            if (id.isNotBlank()) {
                OttaiRegistry.saveProvisionalActiveTime(context, id, observed)
            }
        }
        Log.w(TAG, "corrected stale provisional activeTime $current -> $observed " +
            "after authenticated sensor reported activation status=$commandStatus")
        ensureNativePresenceShell("provisional-correction")
        UiRefreshBus.requestStatusRefresh()
    }

    private fun effectiveActiveTimeMs(): Long =
        materials.activeTimeMs.takeIf { it > 0L }
            // A start derived from the live stream's own dataNo counter (streamStartTimeMs) is
            // the true activation instant for a sensor we didn't just activate — it must win over
            // the cgm-info provisional, whose 0x0477 window only ever yields a "just now" epoch.
            ?: streamStartTimeMs.takeIf { it > 0L }
            ?: provisionalActiveTimeMs.takeIf { it > 0L }
            ?: 0L

    private fun preheatPeriodMs(): Long =
        materials.preheatPeriodMs.takeIf { it > 0L } ?: OttaiConstants.DEFAULT_PREHEAT_PERIOD_MS

    private fun warmupRemainingMs(now: Long = System.currentTimeMillis()): Long {
        val start = effectiveActiveTimeMs()
        if (start <= 0L) return -1L
        return (start + preheatPeriodMs() - now).coerceAtLeast(0L)
    }

    private fun setProvisionalActiveTime(activeTimeMs: Long, reason: String) {
        if (activeTimeMs <= 0L || materials.activeTimeMs > 0L) return
        val old = provisionalActiveTimeMs
        // Only ever move EARLIER. The cgm-info 0x0477 window is a sliding set of recent
        // records, so its min epoch creeps forward every reconnect; letting it drift kept
        // re-anchoring the stream start and broke chart/DB timestamps.
        if (old > 0L && activeTimeMs >= old) return
        provisionalActiveTimeMs = activeTimeMs
        val id = SerialNumber.orEmpty()
        Applic.app?.let { ctx ->
            if (id.isNotBlank()) {
                OttaiRegistry.saveProvisionalActiveTime(ctx, id, activeTimeMs)
            }
        }
        Log.i(TAG, "provisional activeTime set=$activeTimeMs source=$reason")
        ensureNativePresenceShell("provisional-$reason")
        UiRefreshBus.requestStatusRefresh()
    }

    /**
     * Promote a live-anchor start to the authoritative, persisted activeTime. Runs once, only when
     * no cloud activeTime is known: a sensor activated on-device otherwise leaves activeTimeMs=0 in
     * its materials/exported JSON, so the wizard offers "start warmup" on the next add/import even
     * though it's activated. The live dataNo counter gives the true activation, so persist it (and
     * drop the now-redundant provisional).
     */
    /**
     * Gate before [commitConfirmedActiveTime]. The commit is one-way — nothing repairs
     * materials.activeTimeMs once written — and it is the sole input the dataNo ceiling trusts,
     * so a single corrupt frame must not be able to set it. Require two reliable anchors that
     * agree: they are computed as (wall clock - dataNo * interval) from independent reads, so a
     * genuine pair lands within a record or two while a corrupt dataNo lands far away.
     */
    private fun offerConfirmedActiveTime(startMs: Long) {
        if (startMs <= 0L || materials.activeTimeMs > 0L) return
        val now = System.currentTimeMillis()
        // Physically impossible starts are rejected outright rather than corroborated.
        if (startMs > now || now - startMs > OttaiConstants.EXTENDED_LIFETIME_MS) {
            Log.w(TAG, "implausible activation start=${startMs / 1000L} ignored (now=${now / 1000L})")
            return
        }
        val pending = pendingConfirmedStartMs
        if (pending <= 0L) {
            pendingConfirmedStartMs = startMs
            Log.i(TAG, "activation start candidate=${startMs / 1000L} awaiting corroboration")
            return
        }
        if (kotlin.math.abs(pending - startMs) <= CONFIRMED_START_AGREEMENT_MS) {
            commitConfirmedActiveTime(startMs)
            return
        }
        Log.w(TAG, "activation start candidates disagree: ${pending / 1000L} vs ${startMs / 1000L}; " +
            "re-arming corroboration")
        pendingConfirmedStartMs = startMs
    }

    private fun commitConfirmedActiveTime(startMs: Long) {
        if (startMs <= 0L || materials.activeTimeMs > 0L) return
        val id = SerialNumber.orEmpty()
        if (id.isBlank()) return
        materials = materials.copy(activeTimeMs = startMs)
        provisionalActiveTimeMs = 0L
        Applic.app?.let { ctx ->
            OttaiRegistry.saveActiveTimeMs(ctx, id, startMs)
            OttaiRegistry.saveProvisionalActiveTime(ctx, id, 0L)
        }
        Log.i(TAG, "confirmed activeTime=${startMs / 1000L} persisted from live anchor")
        ensureNativePresenceShell("confirmed-active")
        UiRefreshBus.requestStatusRefresh()
    }

    private fun appString(resId: Int, fallback: String, vararg args: Any): String =
        runCatching { Applic.app?.getString(resId, *args) }.getOrNull() ?: fallback

    private fun handleGlucosePayload(cipher: ByteArray, live: Boolean, source: String) {
        if (sessionKeyHex.isBlank()) { Log.w(TAG, "payload before session key"); return }
        if (live && commandStatus >= 4) {
            handleEndedLiveBuffer(cipher, source)
            return
        }
        val activeMs = effectiveActiveTimeMs()
        val receivedAtMs = System.currentTimeMillis()
        val kind = if (live) "live" else "history"
        // Lazy: hex-encoding the cipher on every notification is pure waste when tracing is off.
        logi(TAG) { "$kind $source cipher len=${cipher.size} hex=${OttaiCrypto.bytesToHex(cipher).take(96)}" }
        val payload = OttaiCrypto.decryptPayload(cipher, sessionKeyHex)
        if (payload == null) {
            Log.w(TAG, "$kind $source decrypt failed len=${cipher.size} blockMod=${cipher.size % 16}")
            return
        }
        val records = OttaiParser.frameRecords(payload, materials.deviceVersion)
        if (records.isEmpty()) {
            Log.w(TAG, "$kind $source no records payloadLen=${payload.size} hex=${OttaiCrypto.bytesToHex(payload).take(160)}")
            if (live) {
                handler.postDelayed({ requestRecentHistory("empty-live") }, 1_800L)
            } else if (activeHistoryEndExclusive > 0) {
                // An empty response IS a response: don't let it stall the chunk chain — treat the
                // in-flight window as yielding nothing and advance to the next pending chunk.
                // Record the miss first: a detected-gap window stays on the hole ledger until its
                // data arrives or the attempt cap retires it (truly-empty overshoot ranges).
                val missedStart = activeHistoryStart
                val missedEnd = activeHistoryEndExclusive
                cancelHistoryWatchdog()
                historyRetryCount = 0
                noteHoleFailure(missedStart, missedEnd)
                advanceHistoryChunkChain()
            }
            return
        }
        logi(TAG) { "$kind $source decrypted payloadLen=${payload.size} records=${records.size} front=${OttaiParser.frontDataNo(payload)}" }
        val readings = if (live) {
            listOf(OttaiParser.toReading(records.last(), materials.method, materials.coefficients, activeMs))
        } else {
            records.map { OttaiParser.toReading(it, materials.method, materials.coefficients, activeMs) }
        }
        val previousDataNo = lastDataNo
        // Corrupt/misaligned frames: a dataNo far past the sensor's current position is garbage
        // (seen: front ~17k at sensor ~1.6k). Drop such records BEFORE any state mutation — the
        // emit loop, lastDataNo/continuity updates AND the chunk-chain accounting below all
        // consume only the plausible set, so a corrupt frame can neither land in the future,
        // poison the persisted lastDataNo (live included), nor mark an in-flight chunk complete.
        val ceiling = dataNoCeiling(live)
        val plausible =
            if (ceiling == Int.MAX_VALUE) readings else readings.filter { it.record.dataNo <= ceiling }
        if (plausible.size < readings.size) {
            Log.w(TAG, "$kind $source dropped ${readings.size - plausible.size} corrupt records dataNo>ceiling=$ceiling")
        }
        noteCeilingOutcome(offered = readings.size, kept = plausible.size, ceiling = ceiling)
        val emittedReadings = ArrayList<EmittedReading>(plausible.size)
        for ((index, r) in plausible.withIndex()) {
            if (!r.valid) {
                Log.w(TAG, "$kind record rejected dataNo=${r.record.dataNo} runtime=${r.record.runtimeSec} raw=${r.record.rawCurrent}")
                continue
            }
            if (materials.method.isBlank()) {
                Log.w(TAG, "no method — skipping emit (raw only) dataNo=${r.record.dataNo}")
                continue
            }
            emitReading(r, live, receivedAtMs, plausible, index)?.let(emittedReadings::add)
        }
        if (emittedReadings.isNotEmpty()) {
            storeDecodedReadings(emittedReadings, live)
            emittedReadings.lastOrNull { it.publishCurrent }?.let(::publishCurrentReading)
            if (live) {
                scheduleLivePoll()
                val liveDataNo = lastDataNo
                val previousForHistory = previousDataNoForHistory(previousDataNo, liveDataNo)
                if (previousForHistory != previousDataNo) {
                    Log.w(TAG, "ignore ahead previous dataNo for history previous=$previousDataNo live=$liveDataNo")
                }
                // A live sample arrived — the live path owns the backfill now; drop the
                // independent backstop so the two don't both issue (which would wipe and restart
                // the in-flight chunk chain via clearPendingHistoryRange).
                handler.removeCallbacks(initialHistoryRunnable)
                if (!requestRoomBackfillAfterLive(liveDataNo, previousForHistory) &&
                    !roomBackfillChecked &&
                    !historyDiffOwnsRecovery
                ) {
                    val missingBeforeLive = liveDataNo - previousForHistory - 1
                    if (previousForHistory < 0 || missingBeforeLive > 0) {
                        handler.postDelayed({ requestHistoryAfterLive(previousForHistory, liveDataNo) }, 1_500L)
                    }
                }
            } else {
                if (!continueHistoryAfterPayload(plausible)) {
                    // History can arrive in a long burst and still end several minutes behind
                    // wall time. Ask live immediately afterwards on a one-shot that cgm-info
                    // cadence updates cannot cancel.
                    handler.removeCallbacks(postHistoryLiveRunnable)
                    handler.postDelayed(postHistoryLiveRunnable, 1_000L)
                }
            }
            UiRefreshBus.requestStatusRefresh()
        } else if (!live) {
            continueHistoryAfterPayload(plausible)
        }
    }

    private fun handleEndedLiveBuffer(cipher: ByteArray, source: String) {
        val payload = OttaiCrypto.decryptPayload(cipher, sessionKeyHex)
        if (payload == null) {
            Log.w(TAG, "ended live $source decrypt failed len=${cipher.size}")
            return
        }
        val latest = OttaiParser.frameRecords(payload, materials.deviceVersion)
            .map(OttaiParser::parseRecord)
            .maxByOrNull { it.dataNo }
        if (latest == null) {
            Log.w(TAG, "ended live $source has no records")
            return
        }
        noteSeenDataNo(latest.dataNo)
        // An ended sensor never produces a live read, so no wall-clock-corroborated anchor is
        // possible here and this seed would own the session unchallenged. Derive it only from a
        // confirmed activation: seeding from the cgm-info provisional instead would misdate the
        // whole final backfill with no way back. No anchor is better than a wrong one — the
        // backfill is then dated once a confirmed start is available, or not at all.
        val confirmedMs = materials.activeTimeMs
        if (streamStartTimeMs <= 0L && confirmedMs > 0L) {
            seedStreamTimeAnchor(
                latest.dataNo,
                confirmedMs + latest.runtimeSec.toLong() * 1_000L,
                "ended-live",
            )
        } else if (streamStartTimeMs <= 0L) {
            Log.w(TAG, "ended live buffer with no confirmed activation start; backfill left undated")
        }
        Log.i(TAG, "ended live buffer indexed dataNo=${latest.dataNo}; glucose suppressed")
        handler.removeCallbacks(endedHistoryBackfillRunnable)
        handler.postDelayed(endedHistoryBackfillRunnable, 4_500L)
    }

    private fun emitReading(
        r: OttaiReading,
        live: Boolean,
        receivedAtMs: Long,
        batch: List<OttaiReading>,
        batchIndex: Int,
    ): EmittedReading? {
        // adjustGlucose is mmol/L; convert to mg/dL for Room storage.
        val mmol = r.adjustGlucose.toFloat()
        val mgdl = mmol * MGDL_PER_MMOLL
        OttaiOutputFilter.hardRejectReason(r.record, mmol)?.let { reason ->
            return rejectReading(r, mmol, live, "hard-$reason")
        }
        if (mgdl <= 1f) return rejectReading(r, mmol, live, "mgdl=$mgdl")
        recentRejectedSampleReason(r, mmol)?.let { reason ->
            return rejectReading(r, mmol, live, reason)
        }
        repairAheadLastDataNoIfNeeded(r.record.dataNo, live)
        val advancesDataNo = r.record.dataNo > lastDataNo
        val sampleMs = resolveSampleTimeMs(r, live, receivedAtMs)
        if (sampleMs <= 0L) {
            Log.w(TAG, "no active-time anchor — skipping emit dataNo=${r.record.dataNo}")
            return null
        }
        continuityRejectReason(r, mmol, sampleMs, live, batch, batchIndex)?.let { reason ->
            return rejectReading(r, mmol, live, reason)
        }
        val previousGlucoseAtMs = lastGlucoseAtMs
        val freshLiveSample = live && isFreshLiveSample(receivedAtMs, sampleMs)
        val newest = sampleMs >= previousGlucoseAtMs
        if (newest && (!live || freshLiveSample)) {
            lastGlucoseAtMs = sampleMs
            lastGlucoseMmol = mmol
            lastGlucoseMgdl = mgdl
            lastRawCurrent = r.record.rawCurrent.toFloat()
        }
        rememberAcceptedReading(r, mmol, sampleMs)
        if (advancesDataNo) noteSeenDataNo(r.record.dataNo)
        Log.i(TAG, "BG dataNo=${r.record.dataNo} mmol=%.2f mgdl=%.0f raw=%d T=%.1f".format(
            mmol, mgdl, r.record.rawCurrent, r.record.temperatureC))
        val shouldPersist = !live || (freshLiveSample && sampleMs > previousGlucoseAtMs)
        return EmittedReading(
            sampleMs = sampleMs,
            mgdl = mgdl,
            displayValue = if (Applic.unit == 1) mmol else mgdl,
            publishCurrent = freshLiveSample && sampleMs > previousGlucoseAtMs,
            persist = shouldPersist,
            dataNo = r.record.dataNo,
            temperatureC = r.record.temperatureC.toFloat(),
        )
    }

    private fun rejectReading(r: OttaiReading, mmol: Float, live: Boolean, reason: String): EmittedReading? {
        rememberRejectedReading(r, mmol)
        val kind = if (live) "live" else "history"
        Log.w(TAG, "$kind BG rejected reason=$reason dataNo=${r.record.dataNo} mmol=%.2f raw=%d T=%.1f".format(
            mmol, r.record.rawCurrent, r.record.temperatureC))
        return null
    }

    private fun rememberRejectedReading(r: OttaiReading, mmol: Float) {
        recentlyRejectedSamples[r.record.dataNo] = RejectedSample(r.record.rawCurrent, mmol)
    }

    private fun recentRejectedSampleReason(r: OttaiReading, mmol: Float): String? {
        val rejected = recentlyRejectedSamples[r.record.dataNo] ?: return null
        val sameRaw = rejected.rawCurrent == r.record.rawCurrent
        val sameValue = abs(rejected.mmol - mmol) < 0.05f
        return if (sameRaw || sameValue) {
            "recent-rejected raw=${rejected.rawCurrent} mmol=%.2f".format(rejected.mmol)
        } else {
            null
        }
    }

    private fun repairAheadLastDataNoIfNeeded(acceptedDataNo: Int, live: Boolean) {
        if (!live || !isPersistedDataNoAheadOfLive(lastDataNo, acceptedDataNo)) return
        val previous = lastDataNo
        lastDataNo = acceptedDataNo - 1
        Applic.app?.let { OttaiRegistry.saveLastDataNo(it, SerialNumber.orEmpty(), lastDataNo) }
        Log.w(TAG, "reset ahead lastDataNo previous=$previous acceptedLive=$acceptedDataNo")
    }

    /**
     * Physical upper bound for a record's dataNo, since no sample can be newer than "now".
     * See dataNoCeilingFor for the individual bounds and why the tightest of several is used
     * rather than one: while the activation start is unconfirmed the frame that passes this
     * filter is the one that commits that start, so the bound must not depend on it alone.
     * The persisted lastDataNo gates history only — a legit live sample after a long offline
     * gap is far past it.
     */
    private fun dataNoCeiling(live: Boolean): Int {
        // ONLY an authoritative start may bound dataNo — materials.activeTimeMs, which is set
        // either by the cloud or by commitConfirmedActiveTime() from a reliable live anchor.
        // The other two fallbacks inside effectiveActiveTimeMs() must never be used here:
        //   - provisionalActiveTimeMs is the cgm-info 0x0477 sliding window, which for a
        //     vendor-activated sensor is ~now, so the ceiling lands around
        //     MAX_REASONABLE_DATA_NO_AHEAD while the real dataNo is in the thousands;
        //   - streamStartTimeMs is written by every anchor, including "active"/"initial-history"
        //     whose sample hint is itself derived from that provisional.
        // Gating on either would drop every record, and since seedStreamTimeAnchor() runs
        // downstream of this filter the true start could then never be learned: the sensor goes
        // permanently silent, across restarts too, because the provisional is persisted and
        // setProvisionalActiveTime() only ever moves it earlier.
        if (ceilingDistrusted) return Int.MAX_VALUE
        return dataNoCeilingFor(
            authoritativeStartMs = materials.activeTimeMs,
            nowMs = System.currentTimeMillis(),
            lastDataNo = lastDataNo,
            live = live,
        )
    }

    /**
     * Feed the outcome of one payload back to the ceiling. A payload whose records were ALL
     * rejected is the signature of a wrong bound rather than a corrupt sensor — corruption comes
     * in occasional frames, not in every frame in a row — so after
     * [MAX_CONSECUTIVE_CEILING_FULL_DROPS] such payloads the filter is switched off for the
     * session and the driver recovers on the next record.
     */
    private fun noteCeilingOutcome(offered: Int, kept: Int, ceiling: Int) {
        if (offered <= 0 || ceiling == Int.MAX_VALUE) return
        if (kept > 0) {
            consecutiveCeilingFullDrops = 0
            return
        }
        consecutiveCeilingFullDrops++
        if (!ceilingDistrusted && shouldDistrustCeiling(consecutiveCeilingFullDrops)) {
            ceilingDistrusted = true
            Log.e(TAG, "dataNo ceiling=$ceiling rejected $consecutiveCeilingFullDrops payloads in a " +
                "row — distrusting it for this session (activeTime=${materials.activeTimeMs / 1000L} " +
                "lastDataNo=$lastDataNo)")
        }
    }

    private fun noteSeenDataNo(dataNo: Int) {
        if (dataNo <= lastDataNo) return
        lastDataNo = dataNo
        Applic.app?.let { OttaiRegistry.saveLastDataNo(it, SerialNumber.orEmpty(), dataNo) }
    }

    private fun rememberAcceptedReading(r: OttaiReading, mmol: Float, sampleMs: Long) {
        recentlyRejectedSamples.remove(r.record.dataNo)
        lastAcceptedDataNo = r.record.dataNo
        lastAcceptedSampleMs = sampleMs
        lastAcceptedMmol = mmol
        lastAcceptedRawCurrent = r.record.rawCurrent
        Applic.app?.let {
            OttaiRegistry.saveContinuityBaseline(it, SerialNumber.orEmpty(), r.record.dataNo, sampleMs, mmol, r.record.rawCurrent)
        }
    }

    private data class NeighborSample(
        val dataNo: Int,
        val mmol: Float,
        val rawCurrent: Int,
    )

    private fun continuityRejectReason(
        r: OttaiReading,
        mmol: Float,
        sampleMs: Long,
        live: Boolean,
        batch: List<OttaiReading>,
        batchIndex: Int,
    ): String? {
        if (isAdjacentToLastAccepted(r.record.dataNo, sampleMs) &&
            OttaiOutputFilter.isOneMinuteRawExcursion(
                candidateMmol = mmol,
                candidateRaw = r.record.rawCurrent,
                baselineMmol = lastAcceptedMmol,
                baselineRaw = lastAcceptedRawCurrent,
            )
        ) {
            return "continuity-prev dataNo=${lastAcceptedDataNo} mmol=%.2f raw=%d".format(
                lastAcceptedMmol, lastAcceptedRawCurrent)
        }
        if (!live &&
            isImmediatelyBeforeLastAccepted(r.record.dataNo, sampleMs) &&
            OttaiOutputFilter.isOneMinuteRawExcursion(
                candidateMmol = mmol,
                candidateRaw = r.record.rawCurrent,
                baselineMmol = lastAcceptedMmol,
                baselineRaw = lastAcceptedRawCurrent,
            )
        ) {
            return "continuity-next dataNo=${lastAcceptedDataNo} mmol=%.2f raw=%d".format(
                lastAcceptedMmol, lastAcceptedRawCurrent)
        }
        if (!live) {
            historyIsolatedSpikeReason(batch, batchIndex, r, mmol)?.let { return it }
        }
        return null
    }

    private fun isAdjacentToLastAccepted(dataNo: Int, sampleMs: Long): Boolean {
        val previousDataNo = lastAcceptedDataNo
        if (previousDataNo < 0) return false
        val dataNoGap = dataNo - previousDataNo
        if (dataNoGap in 1..2) return true
        val previousMs = lastAcceptedSampleMs
        if (previousMs <= 0L || sampleMs <= previousMs) return false
        val timeGap = sampleMs - previousMs
        return timeGap in 1..(2 * RECORD_INTERVAL_MS + 15_000L)
    }

    private fun isImmediatelyBeforeLastAccepted(dataNo: Int, sampleMs: Long): Boolean {
        val nextDataNo = lastAcceptedDataNo
        if (nextDataNo < 0) return false
        val dataNoGap = nextDataNo - dataNo
        if (dataNoGap in 1..2) return true
        val nextMs = lastAcceptedSampleMs
        if (nextMs <= 0L || sampleMs >= nextMs) return false
        val timeGap = nextMs - sampleMs
        return timeGap in 1..(2 * RECORD_INTERVAL_MS + 15_000L)
    }

    private fun historyIsolatedSpikeReason(
        batch: List<OttaiReading>,
        batchIndex: Int,
        candidate: OttaiReading,
        candidateMmol: Float,
    ): String? {
        val previous = neighborSample(batch, batchIndex - 1, -1)
        val next = neighborSample(batch, batchIndex + 1, 1)
        val previousAdjacent = previous != null && candidate.record.dataNo - previous.dataNo in 1..2
        val nextAdjacent = next != null && next.dataNo - candidate.record.dataNo in 1..2

        if (previousAdjacent && nextAdjacent) {
            val neighborsAgree = abs(previous!!.mmol - next!!.mmol) < OttaiOutputFilter.SINGLE_SAMPLE_DELTA_MMOL
            if (neighborsAgree &&
                OttaiOutputFilter.isOneMinuteRawExcursion(candidateMmol, candidate.record.rawCurrent, previous.mmol, previous.rawCurrent) &&
                OttaiOutputFilter.isOneMinuteRawExcursion(candidateMmol, candidate.record.rawCurrent, next.mmol, next.rawCurrent)
            ) {
                return "history-isolated prev=${previous.dataNo} next=${next.dataNo}"
            }
        }

        if (!previousAdjacent && nextAdjacent && candidate.record.dataNo <= 5 &&
            OttaiOutputFilter.isOneMinuteRawExcursion(candidateMmol, candidate.record.rawCurrent, next!!.mmol, next.rawCurrent)
        ) {
            return "history-start next=${next.dataNo}"
        }

        return null
    }

    private fun neighborSample(batch: List<OttaiReading>, start: Int, step: Int): NeighborSample? {
        var index = start
        while (index in batch.indices) {
            val reading = batch[index]
            if (reading.valid) {
                val mmol = reading.adjustGlucose.toFloat()
                if (OttaiOutputFilter.hardRejectReason(reading.record, mmol) == null) {
                    return NeighborSample(reading.record.dataNo, mmol, reading.record.rawCurrent)
                }
            }
            index += step
        }
        return null
    }

    private fun storeDecodedReadings(readings: List<EmittedReading>, live: Boolean) {
        val id = SerialNumber ?: return
        // Temperature is keyed by sample time and deduped on write, so keep it for every
        // accepted reading — a live sample whose glucose is a re-send of one already in
        // Room still carries a temperature the stats card wants.
        storeTemperatures(id, readings)
        val toPersist = readings.filter { it.persist }
        if (toPersist.isEmpty()) return
        if (live && toPersist.size == 1) {
            val reading = toPersist.single()
            HistorySyncAccess.storeCurrentReadingAsync(reading.sampleMs, reading.mgdl, 0f, 0f, id)
            mirrorDecodedReadingsIntoNative(id, toPersist, live = true)
            return
        }
        val timestamps = LongArray(toPersist.size) { index -> toPersist[index].sampleMs }
        val values = FloatArray(toPersist.size) { index -> toPersist[index].mgdl }
        // Ottai rawCurrent is an electrode/current diagnostic, not raw glucose mg/dL.
        val rawValues = FloatArray(toPersist.size) { 0f }
        HistorySyncAccess.storeSensorHistoryBatchAsync(id, timestamps, values, rawValues)
        mirrorDecodedReadingsIntoNative(id, toPersist, live)
    }

    private fun mirrorDecodedReadingsIntoNative(
        id: String,
        readings: List<EmittedReading>,
        live: Boolean,
    ) {
        runCatching {
            ensureNativePresenceShell("glucose-mirror")
            val timestampsMs = LongArray(readings.size) { readings[it].sampleMs }
            val glucoseMgdl = FloatArray(readings.size) { readings[it].mgdl }
            val temperaturesC = FloatArray(readings.size) { readings[it].temperatureC }
            val storedCount = nativeGlucoseMirror.mirror(
                id,
                timestampsMs,
                glucoseMgdl,
                temperaturesC,
                live,
            )
            if (storedCount > 0) {
                applyActivatedWearToNative(id)
                Natives.wakebackup()
            }
        }.onFailure { Log.stack(TAG, "mirrorDecodedReadingsIntoNative", it) }
    }

    /** Keeps the per-sample skin temperature so the stats screen can chart it. */
    private fun storeTemperatures(id: String, readings: List<EmittedReading>) {
        val context = Applic.app ?: return
        val records = readings
            .filter { it.temperatureC.isFinite() && it.sampleMs > 0L }
            .map { OttaiRegistry.TemperatureRecord(it.dataNo, it.sampleMs, it.temperatureC) }
        if (records.isEmpty()) return
        runCatching { OttaiRegistry.appendTemperatureHistory(context, id, records) }
            .onFailure { Log.stack(TAG, "persist Ottai temperature", it) }
    }

    private fun publishCurrentReading(reading: EmittedReading) {
        val id = SerialNumber ?: return
        if (!reading.displayValue.isFinite() || reading.displayValue <= 0f) return
        SuperGattCallback.processExternalCurrentReading(id, reading.displayValue, 0f, reading.sampleMs, SENSOR_GEN)
        Log.i(TAG, "current publish sec=${reading.sampleMs / 1000L} display=%.2f mgdl=%.1f".format(reading.displayValue, reading.mgdl))
    }

    private fun resolveSampleTimeMs(
        r: OttaiReading,
        live: Boolean,
        receivedAtMs: Long,
    ): Long {
        // Take the shortcut once the start is CONFIRMED, or for a history record, which cannot
        // produce a wall-clock-corroborated anchor of its own.
        //
        // Deliberately NOT keyed on streamStartReliable: offerConfirmedActiveTime() needs a
        // SECOND reliable anchor to corroborate the first, and seedStreamTimeAnchor() below is
        // the only place one is offered. Short-circuiting as soon as a reliable anchor existed
        // made that gate unreachable for the life of the driver, so the confirmed start was never
        // written — and with materials.activeTimeMs stuck at 0 the dataNo ceiling never gained a
        // bound at all, for exactly the vendor-activated sensors it was rewritten to protect.
        // While the start is reliable-but-unconfirmed, live records must keep flowing through the
        // anchor path; an unreliable anchor is still overridden by them, as before.
        if (streamStartTimeMs > 0L && (materials.activeTimeMs > 0L || !live)) {
            return streamStartTimeMs + r.record.dataNo.toLong() * RECORD_INTERVAL_MS
        }

        if (live && receivedAtMs > 0L && r.record.dataNo >= 0) {
            val monitorMs = r.monitorTimeMs
            if (monitorMs > 0L && kotlin.math.abs(receivedAtMs - monitorMs) <= CURRENT_SAMPLE_FRESH_MS) {
                return seedStreamTimeAnchor(r.record.dataNo, monitorMs, "monitor-live", reliable = true)
            }
            if (monitorMs > 0L) {
                val deltaSec = kotlin.math.abs(receivedAtMs - monitorMs) / 1000L
                Log.w(TAG, "ignore stale live monitor timestamp dataNo=${r.record.dataNo} monitor=${monitorMs / 1000L} live=${receivedAtMs / 1000L} delta=${deltaSec}s")
            }
            return seedStreamTimeAnchor(r.record.dataNo, receivedAtMs, "live", reliable = true)
        }

        r.monitorTimeMs.takeIf { it > 0L }?.let { monitorMs ->
            // monitorTimeMs is activeTimeMs + runtime (OttaiParser), i.e. derived from
            // effectiveActiveTimeMs — possibly the provisional "just now" cgm-info seed. Unlike
            // "monitor-live" there is no wall-clock corroboration here, so the derived start is
            // circular and must NOT be committed as the confirmed activation (reliable).
            return seedStreamTimeAnchor(r.record.dataNo, monitorMs, "monitor")
        }
        val activeMs = effectiveActiveTimeMs()
        if (activeMs > 0L) {
            return seedStreamTimeAnchor(r.record.dataNo, activeMs + r.record.runtimeSec * 1000L, "active")
        }
        return r.monitorTimeMs
    }

    // reliable = the sampleHint is an independent wall-clock/monitor timestamp (live poll or a
    // record's own monitor time), so start = hint - dataNo*interval is the true activation. Such
    // a start is committed as the effective active time (persisted) so the dashboard and native
    // record stop showing the bogus cgm-info "just now" provisional. Sources whose hint is itself
    // derived from effectiveActiveTimeMs (active/initial-history/ended-live) must NOT set reliable.
    private fun seedStreamTimeAnchor(dataNo: Int, sampleHintMs: Long, reason: String, reliable: Boolean = false): Long {
        if (dataNo < 0 || sampleHintMs <= 0L) return sampleHintMs
        val sampleMs = floorToRecordMinute(sampleHintMs)
        val start = sampleMs - dataNo.toLong() * RECORD_INTERVAL_MS
        if (start > 0L) {
            val old = streamStartTimeMs
            // Never downgrade: once a wall-clock-corroborated anchor is in place, a hint derived
            // from effectiveActiveTimeMs must not replace it. Dating stays consistent with the
            // anchor that is actually trusted.
            if (old > 0L && streamStartReliable && !reliable) {
                return old + dataNo.toLong() * RECORD_INTERVAL_MS
            }
            streamStartTimeMs = start
            streamStartReliable = reliable
            if (old == 0L || kotlin.math.abs(old - start) > RECORD_INTERVAL_MS) {
                Log.i(TAG, "stream time anchor dataNo=$dataNo start=${start / 1000L} sample=${sampleMs / 1000L} source=$reason reliable=$reliable")
            }
            // A live-dataNo-derived start is the true activation. Persist it as the authoritative
            // activeTime so the setup wizard / exported JSON recognise an already-activated sensor
            // (instead of offering "start warmup") on the next add or import.
            if (reliable) offerConfirmedActiveTime(start)
            if (effectiveActiveTimeMs() <= 0L) ensureNativePresenceShell("stream-anchor-$reason")
        }
        return sampleMs
    }

    private fun floorToRecordMinute(timestampMs: Long): Long =
        (timestampMs / RECORD_INTERVAL_MS) * RECORD_INTERVAL_MS

    private fun ensureNativePresenceShell(reason: String) {
        val id = SerialNumber ?: return
        val startMs = nativePresenceStartTimeMs()
        if (id.isBlank() || startMs <= 0L) return
        runCatching {
            Natives.ensureSensorShell(id, (startMs / 1000L).coerceAtLeast(1L))
            applyActivatedWearToNative(id)
        }.onFailure { Log.stack(TAG, "ensureNativePresenceShell($reason)", it) }
    }

    /** Real activated lifetime in whole days (rounded), or 0 if unknown. */
    private fun activatedLifetimeDays(): Int {
        val ms = activatedMaxActiveMs
        return if (ms <= 0L) 0 else ((ms + 43_200_000L) / 86_400_000L).toInt()
    }

    /** Push the real activated lifetime to the native sensor record (main-graph end). */
    private fun applyActivatedWearToNative(id: String) {
        val days = activatedLifetimeDays()
        if (days <= 0 || id.isBlank()) return
        runCatching { Natives.setSensorWearDays(id, days) }
            .onFailure { Log.stack(TAG, "setSensorWearDays", it) }
    }

    /**
     * The native shell's starttime update is monotone-decreasing, so whatever reaches it first
     * and earliest sticks — a provisional "just now" that later moves earlier can never be
     * corrected, and the explicit correction path becomes a no-op. Only offer a start we have
     * actually confirmed, or a wall-clock-corroborated stream anchor; a provisional stays out.
     */
    private fun nativePresenceStartTimeMs(): Long =
        materials.activeTimeMs.takeIf { it > 0L }
            ?: streamStartTimeMs.takeIf { it > 0L && streamStartReliable }
            ?: 0L

    // ---- activation (gated) ----

    /** Explicit re-activate that bypasses the already-started guard (Advanced action). */
    fun requestForceActivation(): Boolean {
        forceActivationRequested = true
        return requestActivation()
    }

    override fun requestActivation(): Boolean {
        val force = forceActivationRequested
        forceActivationRequested = false
        if (!force && (effectiveActiveTimeMs() > 0L || activationCommandSentAtMs > 0L)) {
            Log.i(TAG, "activation request ignored — command already sent/start time known")
            activateRequestedFor = null
            return true
        }
        if (force) Log.i(TAG, "FORCE activation (bypassing already-started guard)")
        val gatt = mBluetoothGatt ?: return false
        if (phase != Phase.STREAMING || sessionKeyHex.isBlank()) {
            Log.w(TAG, "activation refused — not authenticated (phase=$phase)")
            return false
        }
        // Re-discover first. The pre-auth GATT can be stale post-auth (b8fd9848/6aa799b6
        // returned Invalid Handle on the handles discovered before auth, while the sensor
        // advertises Service Changed). A fresh discovery picks up the post-auth handles;
        // if discovery can't start, fall back to the current handles.
        pendingActivation = true
        discoveryStarted = true // suppress the connect-time discovery guard
        Log.i(TAG, "re-discovering services before activation")
        if (runCatching { gatt.discoverServices() }.getOrDefault(false)) {
            handler.removeCallbacks(serviceDiscoveryTimeoutRunnable)
            handler.postDelayed(serviceDiscoveryTimeoutRunnable, SERVICE_DISCOVERY_TIMEOUT_MS)
        } else {
            pendingActivation = false
            startActivationWrites(gatt)
        }
        return true
    }

    private fun beginActivationNegotiation() {
        val cloudExpireMs = materials.activeExpireTimeMs.takeIf { it > 0L }
            ?: OttaiConstants.DEFAULT_ACTIVE_EXPIRE_MS
        maxActiveCandidatesMs = OttaiConstants.activationMaxActiveCandidatesMs(cloudExpireMs)
        maxActiveCandidateIndex = 0
        maxActiveAttemptMs = 0L
        clearStagedActivationLifetime()
        activationNegotiationActive = true
        activationRetryPending = false
        activationRetryAddress = null
        activationCandidateDiscoveryPending = false
        activationCandidateProbeActive = false
        activationCandidateHomeAddress = null
        freshActivationAdvertisementAbandoned = false
        handler.removeCallbacks(freshActivationAdvertisementTimeoutRunnable)
        rejectedActivationCandidateAddresses.clear()
        clearDeferredActivationCandidateCgmInfo()
        activationFailed = false
        UiRefreshBus.requestStatusRefresh()
    }

    private fun resetActivationNegotiation(
        failed: Boolean = false,
        preserveStagedLifetime: Boolean = false,
    ) {
        SerialNumber?.let { sensorId ->
            Applic.app?.let { OttaiNfcWakeReminder.cancel(it, sensorId) }
            OttaiNfc.disarmActivationRetry(sensorId)
        }
        activationNegotiationActive = false
        activationRetryPending = false
        activationRetryAddress = null
        activationCandidateDiscoveryPending = false
        activationCandidateProbeActive = false
        activationCandidateHomeAddress = null
        freshActivationAdvertisementAbandoned = false
        handler.removeCallbacks(freshActivationAdvertisementTimeoutRunnable)
        rejectedActivationCandidateAddresses.clear()
        clearDeferredActivationCandidateCgmInfo()
        activationFailed = failed
        maxActiveCandidatesMs = emptyList()
        maxActiveCandidateIndex = 0
        maxActiveAttemptMs = 0L
        if (!preserveStagedLifetime) clearStagedActivationLifetime()
        pendingActivation = false
        actStep = ActStep.NONE
    }

    private fun failActivation(reason: String) {
        Log.e(TAG, "activation failed: $reason")
        resetActivationNegotiation(failed = true)
        needsActivationAttempted = false
        constatstatusstr = appString(R.string.ottai_status_activation_failed, "Activation failed")
        UiRefreshBus.requestStatusRefresh()
    }

    private fun startActivationWrites(gatt: BluetoothGatt) {
        if (!activationNegotiationActive || maxActiveCandidatesMs.isEmpty()) {
            beginActivationNegotiation()
        }
        activationRetryPending = false
        activationFailed = false
        Log.i(TAG, "starting activation sequence attempt=${maxActiveCandidateIndex + 1}/" +
            "${maxActiveCandidatesMs.size}")
        actStep = ActStep.RTC
        writeRtc(gatt)
    }

    private fun advanceActivation(gatt: BluetoothGatt) {
        when (actStep) {
            ActStep.RTC -> { actStep = ActStep.MAX_ACTIVE; writeMaxActiveTime(gatt) }
            ActStep.MAX_ACTIVE -> { actStep = ActStep.DESTRUCTION; writeDestructionTime(gatt) }
            ActStep.DESTRUCTION -> { actStep = ActStep.COMMAND; writeActivateCmd(gatt) }
            ActStep.COMMAND -> { actStep = ActStep.DONE; markActivationCommandSent() }
            else -> {}
        }
    }

    private fun markActivationCommandSent() {
        val now = System.currentTimeMillis()
        activationCommandSentAtMs = now
        activationCommandAcknowledged = true
        resetActivationNegotiation(preserveStagedLifetime = true)
        val id = SerialNumber.orEmpty()
        val ctx = Applic.app
        if (ctx != null && id.isNotBlank()) {
            OttaiRegistry.setActivationAttempted(ctx, id, true)
            if (materials.activeTimeMs <= 0L) {
                setProvisionalActiveTime(now, "activation-command")
            }
        }
        Log.i(TAG, "activation command sent; sensor accepted activation writes")
        mBluetoothGatt?.let { gatt ->
            handler.postDelayed({ runCatching { readCgmInfo(gatt) } }, 1_000)
            handler.postDelayed({
                runCatching {
                    readLiveGlucose(gatt, "post-activation")
                    scheduleLivePoll()
                }
            }, 2_000)
            // Re-read the cmd/activation byte: if our writes took, an expired (cmd>3) unit
            // should drop back to 3 (activated). Logs "cmd/activation status=N".
            handler.postDelayed({ runCatching { readChar(gatt, OttaiConstants.SERVICE_CGM, OttaiConstants.CHAR_COMMAND) } }, 3_000)
        }
        UiRefreshBus.requestStatusRefresh()
    }

    private fun writeRtc(gatt: BluetoothGatt) {
        val secs = (System.currentTimeMillis() / 1000L).toInt()
        if (!writeChar(
                gatt,
                OttaiConstants.SERVICE_DEVICE_INFO,
                OttaiConstants.CHAR_CURRENT_TIME,
                OttaiBleAuth.intToBytesLE(secs),
            )
        ) {
            failActivation("RTC characteristic is unavailable")
        }
    }

    private fun writeMaxActiveTime(gatt: BluetoothGatt) {
        // Official: p.U(p.w0(p.D / 1000), hex2bytes(sessionKey)), where p.D =
        // activeExpireTime (ms) from the cloud validate-by-mac response. p.U zero-pads
        // to one AES block and encrypts with AES/ECB/ZeroBytePadding; this is a 16-byte
        // write, not plaintext duration + session key.
        val cloudExpireMs = materials.activeExpireTimeMs.takeIf { it > 0L }
            ?: OttaiConstants.DEFAULT_ACTIVE_EXPIRE_MS
        if (maxActiveCandidatesMs.isEmpty()) {
            maxActiveCandidatesMs = OttaiConstants.activationMaxActiveCandidatesMs(cloudExpireMs)
            maxActiveCandidateIndex = 0
        }
        val expireMs = maxActiveCandidatesMs.getOrNull(maxActiveCandidateIndex)
            ?: cloudExpireMs
        maxActiveAttemptMs = expireMs
        val secs = expireMs / 1000L
        Log.i(TAG, "maxActive attempt=${maxActiveCandidateIndex + 1}/${maxActiveCandidatesMs.size} " +
            "target=${secs}s cloud=${cloudExpireMs / 1000L}s")
        val payload = OttaiCrypto.encryptPayload(longToBytesLE(secs), sessionKeyHex)
            ?: run {
                failActivation("maxActive encryption failed")
                return
            }
        val issued = writeChar(gatt, OttaiConstants.SERVICE_DEVICE_INFO, OttaiConstants.CHAR_MAX_ACTIVE_TIME, payload)
        if (!issued) {
            // Characteristic not present at all (some firmwares omit it) — skip to the
            // next step. A present-but-rejected write is handled in onCharacteristicWrite.
            Log.w(TAG, "maxActive char absent — skipping to destruction")
            advanceActivation(gatt)
        }
    }

    private fun retryMaxActiveTime(gatt: BluetoothGatt, status: Int): Boolean {
        val nextIndex = maxActiveCandidateIndex + 1
        val nextMs = maxActiveCandidatesMs.getOrNull(nextIndex) ?: return false
        val rejectedMs = maxActiveAttemptMs
        maxActiveCandidateIndex = nextIndex
        maxActiveAttemptMs = 0L
        activationRetryPending = true
        activationRetryAddress =
            OttaiConstants.normalizeBleAddress(gatt.device?.address, allowPlain = false)
                ?: knownBleAddress()
        if (activationRetryAddress == null) {
            Log.e(TAG, "maxActive retry has no authenticated Bluetooth address")
            return false
        }
        actStep = ActStep.NONE
        Log.w(TAG, "maxActive rejected duration=${rejectedMs / 1000L}s status=$status; " +
            "will reconnect and retry ${nextMs / 1000L}s")
        if (OttaiRegistry.loadApiBase(Applic.app) == OttaiConstants.API_BASE) {
            SerialNumber?.takeIf { it.isNotBlank() }?.let { sensorId ->
                OttaiNfc.armForActivationRetry(sensorId)
                Applic.app?.let { OttaiNfcWakeReminder.show(it, sensorId) }
            }
        }
        UiRefreshBus.requestStatusRefresh()
        handler.postDelayed({
            if (activationNegotiationActive && activationRetryPending && mBluetoothGatt === gatt) {
                Log.i(TAG, "disconnecting rejected maxActive session before next attempt")
                runCatching { gatt.disconnect() }
            }
        }, 200L)
        handler.postDelayed({
            if (activationNegotiationActive && activationRetryPending &&
                mBluetoothGatt === gatt && phase != Phase.IDLE
            ) {
                recoverGattAndReconnect("maxActive retry disconnect timeout")
            }
        }, 1_500L)
        return true
    }

    private fun beginActivationCandidateDiscovery(reason: String) {
        if (!activationNegotiationActive || !activationRetryPending) return
        activationCandidateDiscoveryPending = true
        activationCandidateProbeActive = false
        // This is the second way into candidate discovery, and it used to leave the home address
        // at the null beginActivationNegotiation() writes — which made rejectActivationCandidate's
        // "restore our own address" a no-op on this path, so a disproved stranger stayed pinned.
        activationCandidateHomeAddress = ownRecordAddress() ?: activationCandidateHomeAddress
        clearDeferredActivationCandidateCgmInfo()
        mActiveBluetoothDevice = null
        searchforDeviceAddress()
        mActiveDeviceAddress = addressAfterCandidate(activationRetryAddress)
        Log.w(TAG, "activation retry scanning for an authenticated Ottai candidate: $reason")
        if (OttaiRegistry.loadApiBase(Applic.app) == OttaiConstants.API_BASE) {
            SerialNumber?.takeIf { it.isNotBlank() }?.let { sensorId ->
                OttaiNfc.armForActivationRetry(sensorId)
                Applic.app?.let { OttaiNfcWakeReminder.show(it, sensorId) }
            }
        }
        SensorBluetooth.blueone?.scanStarter(250L)
        UiRefreshBus.requestStatusRefresh()
    }

    private fun markMaxActiveAccepted() {
        val acceptedMs = maxActiveAttemptMs
        if (acceptedMs <= 0L) return
        activationRetryPending = false
        pendingAcceptedMaxActiveMs = acceptedMs
        Log.i(TAG, "maxActive accepted duration=${acceptedMs / 1000L}s; staged until status=3")
        UiRefreshBus.requestStatusRefresh()
    }

    private fun commitStagedActivationLifetime(status: Int) {
        val acceptedMs = acceptedMaxActiveToCommit(
            status,
            activationCommandAcknowledged,
            pendingAcceptedMaxActiveMs,
        )
        if (acceptedMs <= 0L) return
        activatedMaxActiveMs = acceptedMs
        val id = SerialNumber.orEmpty()
        Applic.app?.takeIf { id.isNotBlank() }?.let { context ->
            OttaiRegistry.saveAcceptedMaxActive(context, id, acceptedMs)
        }
        applyActivatedWearToNative(id)
        clearStagedActivationLifetime()
        Log.i(TAG, "activation confirmed status=3; committed maxActive=${acceptedMs / 1000L}s")
    }

    private fun clearStagedActivationLifetime() {
        pendingAcceptedMaxActiveMs = 0L
        activationCommandAcknowledged = false
    }

    private fun writeDestructionTime(gatt: BluetoothGatt) {
        // Official: p.w0(p.E / 1000) || {0x04}, where p.E = retainTime (ms) from the cloud
        // response, defaulting to 172800000 (= 172800 s) when the server omits it. This is
        // a small DURATION, not an absolute epoch — writing now+lifetime here made the
        // sensor terminate the link (HCI reason 0x13).
        val retainMs = materials.retainTimeMs.takeIf { it > 0L } ?: OttaiConstants.DEFAULT_RETAIN_TIME_MS
        val secs = retainMs / 1000L
        val payload = longToBytesLE(secs) + byteArrayOf(0x04)
        if (!writeChar(gatt, OttaiConstants.SERVICE_DESTRUCTIVE, OttaiConstants.CHAR_DESTRUCTIVE, payload)) {
            failActivation("destruction characteristic is unavailable")
        }
    }

    private fun writeActivateCmd(gatt: BluetoothGatt) {
        // Official: p.U({0x03}, hex2bytes(sessionKey)) then write to the CGM command
        // characteristic. This is encrypted and exactly one AES block.
        val cmd = OttaiCrypto.encryptActivateCmd(byteArrayOf(OttaiConstants.ACTIVATE_CMD), sessionKeyHex)
            ?: run {
                failActivation("activation command encryption failed")
                return
            }
        if (!writeChar(gatt, OttaiConstants.SERVICE_CGM, OttaiConstants.CHAR_COMMAND, cmd)) {
            failActivation("activation command characteristic is unavailable")
        }
    }

    private fun longToBytesLE(v: Long): ByteArray = ByteArray(8) { ((v ushr (it * 8)) and 0xFF).toByte() }
    private fun shortToBytesLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
    )
    private fun le16(lo: Byte, hi: Byte): Int =
        (lo.toInt() and 0xFF) or ((hi.toInt() and 0xFF) shl 8)
    private fun uint32Le(b: ByteArray, offset: Int): Long =
        ((b[offset].toLong() and 0xFFL) or
            ((b[offset + 1].toLong() and 0xFFL) shl 8) or
            ((b[offset + 2].toLong() and 0xFFL) shl 16) or
            ((b[offset + 3].toLong() and 0xFFL) shl 24))

    override fun requestHistoryBackfill(): Boolean {
        val latest = lastDataNo.takeIf { it > 0 } ?: return false
        return requestHistoryRange("manual", 0, latest + 1)
    }

    /**
     * Drive the initial history backfill WITHOUT waiting for the first accepted live sample.
     * The live-triggered path (requestRoomBackfillAfterLive off a live reading) is the primary
     * route, but it only fires when a live frame is accepted — and this sensor can open a session
     * with empty/rejected live reads, leaving history unfetched. This backstop runs a few seconds
     * after streaming starts, bounds the range from the real lastDataNo when known or the
     * activation-age estimate otherwise, and hands off to the shared backfill.
     */
    private fun runInitialHistoryBackfill() {
        if (stop || phase != Phase.STREAMING || sessionKeyHex.isBlank() || commandStatus != 3) return
        if (roomBackfillChecked) return // the live path already issued the backfill
        SerialNumber ?: return
        val estimate = estimatedNewestDataNo()
        val newest = maxOf(lastDataNo, estimate)
        if (newest <= 0) {
            // No basis yet (activation time unknown and no sample). Nudge a live read to learn
            // the current dataNo, then retry a bounded number of times.
            if (initialHistoryAttempt < INITIAL_HISTORY_MAX_ATTEMPTS) {
                initialHistoryAttempt++
                mBluetoothGatt?.let { g -> runCatching { readLiveGlucose(g, "initial-history-probe") } }
                handler.postDelayed(initialHistoryRunnable, INITIAL_HISTORY_RETRY_MS)
            } else {
                Log.w(TAG, "initial history backfill gave up — no dataNo basis after $initialHistoryAttempt attempts")
            }
            return
        }
        // Seed a time anchor from activation so backfilled history records get correct
        // timestamps even before the first live sample lands (a later live sample re-anchors it).
        if (streamStartTimeMs <= 0L) {
            val activeMs = effectiveActiveTimeMs()
            if (activeMs > 0L) {
                seedStreamTimeAnchor(newest, activeMs + newest.toLong() * RECORD_INTERVAL_MS, "initial-history")
            }
        }
        val previousForHistory = previousDataNoForHistory(lastDataNo, newest)
        // 'newest' is only trustworthy when the activation-age estimate backs it; a stale
        // persisted lastDataNo compared against itself must not consume the one-shot with a
        // no-op (the live path then does the real gap check once a sample lands).
        val issued = requestRoomBackfillAfterLive(
            newest,
            previousForHistory,
            observedNewest = estimate > 0 && lastDataNo <= estimate,
        )
        Log.i(TAG, "initial history backfill newest=$newest previous=$previousForHistory issued=$issued")
    }

    /**
     * Estimate the newest history dataNo from activation age (dataNo == minutes since
     * activation). Stays a couple of records short of "now": the newest samples arrive via the
     * live poll, and overshooting the sensor's real newest dataNo would leave the final backfill
     * chunk empty (and stall the chunk chain). 0 when activation time is still unknown.
     */
    private fun estimatedNewestDataNo(): Int {
        val activeMs = effectiveActiveTimeMs()
        if (activeMs <= 0L) return 0
        val minutes = ((System.currentTimeMillis() - activeMs) / RECORD_INTERVAL_MS).toInt()
        return (minutes - 2).coerceAtLeast(0)
    }

    private fun requestRoomBackfillAfterLive(
        liveDataNo: Int,
        previousDataNo: Int,
        observedNewest: Boolean = true,
    ): Boolean {
        historyDiffOwnsRecovery = false
        if (roomBackfillChecked || liveDataNo <= 0) return false
        val id = SerialNumber ?: return false
        // The one-shot is consumed only by an actually-issued request or a trusted "nothing
        // missing" verdict — an early-out on a no-op (stale basis, missing anchor, failed
        // issue) must leave it clear so the live path can still do the real check.
        if (!shouldDiffStoredHistory(previousDataNo, historyDiffRetryPending)) {
            val missingBeforeLive = liveDataNo - previousDataNo - 1
            if (missingBeforeLive <= 0) {
                if (observedNewest) roomBackfillChecked = true
                Log.i(TAG, "skip history reason=room-backfill previous=$previousDataNo live=$liveDataNo observed=$observedNewest")
                return false
            }
            return requestHistoryRange("room-backfill", previousDataNo + 1, missingBeforeLive)
                .also { if (it) roomBackfillChecked = true }
        }
        // From here on the diff owns recovery for this payload — see historyDiffOwnsRecovery.
        historyDiffOwnsRecovery = true
        val startMs = streamStartTimeMs.takeIf { it > 0L } ?: run {
            historyDiffRetryPending = true
            Log.w(TAG, "history diff missing stream anchor — deferring backfill live=$liveDataNo")
            return false
        }
        val endMs = (System.currentTimeMillis() + RECORD_INTERVAL_MS).coerceAtLeast(startMs)
        val existing = HistorySyncAccess.getHistoryTimestampsForSensorOrNull(
            id,
            (startMs - RECORD_INTERVAL_MS / 2L).coerceAtLeast(0L),
            endMs,
        )
        if (existing == null) {
            // The local store could not be asked. "Don't know" must not collapse into "have
            // nothing": that answer costs a full re-download of the whole sensor history, and
            // it repeats on every reconnect because nothing about it self-heals. Keep the diff
            // pending so a later live sample retries this query even with a usable lastDataNo.
            historyDiffRetryPending = true
            Log.e(TAG, "history diff unavailable — deferring backfill live=$liveDataNo")
            return false
        }
        historyDiffRetryPending = false
        if (existing.isEmpty()) {
            return requestHistoryRange("room-backfill", 0, liveDataNo)
                .also { if (it) roomBackfillChecked = true }
        }
        val present = BooleanArray(liveDataNo)
        for (timestamp in existing) {
            val dataNo = ((timestamp - startMs + RECORD_INTERVAL_MS / 2L) / RECORD_INTERVAL_MS).toInt()
            if (dataNo in present.indices) present[dataNo] = true
        }
        val gaps = missingRanges(present)
        if (gaps.isEmpty()) {
            roomBackfillChecked = true // verified complete against Room = trusted
            return false
        }
        // Run the newest gap as the live chain — that is the data the chart is waiting for —
        // and put the older ones on the books first. The ledger's retry driver picks those up
        // once the chain drains (advanceHistoryChunkChain/continueHistoryAfterPayload call
        // scheduleHoleRetry), and because they are ledgered before anything is issued, a
        // disconnect mid-chain cannot lose them.
        val head = gaps.last()
        for (gap in gaps.dropLast(1)) addHistoryHole(gap.start, gap.endExclusive)
        val issued = requestHistoryRange("room-backfill", head.start, head.endExclusive - head.start)
        if (issued) roomBackfillChecked = true else scheduleHoleRetry()
        Log.i(
            TAG,
            "history diff live=$liveDataNo stored=${existing.size} gaps=${gaps.size} " +
                "missing=${gaps.sumOf { it.endExclusive - it.start }} " +
                "head=[${head.start},${head.endExclusive}) issued=$issued"
        )
        return issued
    }

    private fun requestRecentHistory(reason: String): Boolean {
        val latest = lastDataNo.takeIf { it > 0 } ?: return false
        val endExclusive = latest
        val start = (endExclusive - RECENT_HISTORY_RECORDS).coerceAtLeast(0)
        val count = endExclusive - start
        return requestHistoryRange(reason, start, count)
    }

    private fun requestHistoryAfterLive(previousDataNo: Int, liveDataNo: Int): Boolean {
        if (liveDataNo <= 0) return false
        val missingBeforeLive = liveDataNo - previousDataNo - 1
        if (previousDataNo >= 0 && missingBeforeLive <= 0) {
            return false
        }
        val count = if (previousDataNo < 0) {
            liveDataNo
        } else {
            missingBeforeLive
        }
        if (count <= 0) return false
        if (count > MAX_HISTORY_REQUEST_RECORDS) {
            Log.w(TAG, "history request too large reason=post-live previous=$previousDataNo live=$liveDataNo count=$count")
            return false
        }
        val start = if (previousDataNo < 0) {
            0
        } else {
            previousDataNo + 1
        }
        return requestHistoryRange("post-live", start, count)
    }

    private fun requestHistoryRange(reason: String, start: Int, count: Int): Boolean {
        if (start < 0 || count <= 0) return false
        if (count > MAX_HISTORY_REQUEST_RECORDS) {
            Log.w(TAG, "history request too large reason=$reason start=$start count=$count")
            return false
        }
        val bypassCooldown = reason == "manual" || reason == "room-backfill" || reason == "hole-retry"
        if (!bypassCooldown && System.currentTimeMillis() - lastHistoryRequestAtMs < HISTORY_REQUEST_COOLDOWN_MS) {
            // Never tear down an armed chain for a request that cannot issue anyway (cooldown).
            return false
        }
        clearPendingHistoryRange()
        // Detected-gap windows go on the books at request time; only arrived data (or the
        // attempt cap) takes them off — chain teardown/disconnect/restart can't lose them.
        // "manual" and speculative "empty-live" windows are deliberately not ledgered.
        if (reason == "room-backfill" || reason == "post-live" || reason == "hole-retry") {
            addHistoryHole(start, start + count)
        }
        val requestCount = count.coerceAtMost(HISTORY_REQUEST_CHUNK_RECORDS)
        val issued = issueHistoryRequest(reason, start, requestCount, bypassCooldown = bypassCooldown)
        if (issued && requestCount < count) {
            pendingHistoryReason = reason
            pendingHistoryNextStart = start + requestCount
            pendingHistoryEndExclusive = start + count
            historyChainStart = start
            Log.i(TAG, "history chunked reason=$reason start=$start count=$count first=$requestCount next=$pendingHistoryNextStart")
            UiRefreshBus.requestStatusRefresh()
        }
        if (!issued) scheduleHoleRetry() // a just-ledgered window still gets its retry driver
        return issued
    }

    private fun issueHistoryRequest(reason: String, start: Int, count: Int, bypassCooldown: Boolean): Boolean {
        if (stop || phase != Phase.STREAMING || sessionKeyHex.isBlank()) return false
        val gatt = mBluetoothGatt ?: return false
        if (start < 0 || count <= 0) return false
        if (count > MAX_HISTORY_REQUEST_RECORDS) {
            Log.w(TAG, "history request too large reason=$reason start=$start count=$count")
            return false
        }
        val now = System.currentTimeMillis()
        if (!bypassCooldown && now - lastHistoryRequestAtMs < HISTORY_REQUEST_COOLDOWN_MS) return false
        val payload = shortToBytesLE(start) + shortToBytesLE(count)
        val issued = writeChar(gatt, OttaiConstants.SERVICE_CGM, OttaiConstants.CHAR_HISTORY_REQUEST, payload)
        if (issued) {
            lastHistoryRequestAtMs = now
            activeHistoryStart = start
            activeHistoryEndExclusive = start + count
            armHistoryWatchdog()
            Log.i(TAG, "request history reason=$reason start=$start count=$count payload=${OttaiCrypto.bytesToHex(payload)}")
        }
        return issued
    }

    private fun requestPendingHistoryChunk(): Boolean {
        val reason = pendingHistoryReason ?: return false
        val start = pendingHistoryNextStart
        val endExclusive = pendingHistoryEndExclusive
        val remaining = endExclusive - start
        if (remaining <= 0) {
            clearPendingHistoryRange()
            return false
        }
        val count = remaining.coerceAtMost(HISTORY_REQUEST_CHUNK_RECORDS)
        val issued = issueHistoryRequest("$reason-chunk", start, count, bypassCooldown = true)
        if (!issued) {
            Log.w(TAG, "history chunk request failed reason=$reason start=$start count=$count end=$endExclusive")
            // Don't leave a half-dead chain (pending set, nothing armed): clear it and hand the
            // ledgered remainder to the hole-retry driver.
            clearPendingHistoryRange()
            scheduleHoleRetry()
            return false
        }
        pendingHistoryNextStart = start + count
        if (pendingHistoryNextStart >= endExclusive) {
            pendingHistoryReason = null
            pendingHistoryNextStart = 0
            pendingHistoryEndExclusive = 0
            // Retire the chain explicitly rather than relying on the zeroed bounds to make
            // historyBackfillPercent() answer -1: that is true today only by coincidence, and a
            // stale start left here would be one edit away from pinning the status on a
            // percentage that never moves again.
            historyChainStart = -1
            UiRefreshBus.requestStatusRefresh()
        }
        return true
    }

    private fun continueHistoryAfterPayload(readings: List<OttaiReading>): Boolean {
        val activeEndExclusive = activeHistoryEndExclusive
        if (activeEndExclusive <= 0) return pendingHistoryReason != null
        // Callers pass the ceiling-filtered plausible list: only plausible progress resets the
        // stall budget or completes the window, so a corrupt frame can neither starve the
        // watchdog bound nor mark the chunk done.
        val maxDataNo = readings.maxOfOrNull { it.record.dataNo } ?: return false
        historyRetryCount = 0
        if (maxDataNo + 1 < activeEndExclusive) {
            // Partial chunk — restart the stall timer so a later dropped frame is still caught.
            armHistoryWatchdog()
            return true
        }
        // Data actually arrived for the whole window — the only event that shrinks the ledger.
        trimHistoryHoles(activeHistoryStart, activeEndExclusive)
        cancelHistoryWatchdog()
        activeHistoryEndExclusive = -1
        activeHistoryStart = -1
        if (pendingHistoryReason == null) {
            scheduleHoleRetry()
            return false
        }
        handler.removeCallbacks(pendingHistoryChunkRunnable)
        handler.postDelayed(pendingHistoryChunkRunnable, HISTORY_CHUNK_DELAY_MS)
        Log.i(TAG, "history chunk complete through=$maxDataNo next=$pendingHistoryNextStart end=$pendingHistoryEndExclusive")
        UiRefreshBus.requestStatusRefresh()
        return true
    }

    // ---- history chunk-chain watchdog ----

    private fun armHistoryWatchdog() {
        handler.removeCallbacks(historyWatchdogRunnable)
        handler.postDelayed(historyWatchdogRunnable, HISTORY_PAGE_TIMEOUT_MS)
    }

    private fun cancelHistoryWatchdog() {
        handler.removeCallbacks(historyWatchdogRunnable)
    }

    /** Set the in-flight chunk to "done" and either fire the next pending chunk or finish. */
    private fun advanceHistoryChunkChain() {
        activeHistoryEndExclusive = -1
        activeHistoryStart = -1
        if (pendingHistoryReason == null) {
            scheduleHoleRetry() // chain drained — let the ledger pick up any recorded misses
            return
        }
        handler.removeCallbacks(pendingHistoryChunkRunnable)
        handler.postDelayed(pendingHistoryChunkRunnable, HISTORY_CHUNK_DELAY_MS)
    }

    private fun checkHistoryWatchdog() {
        if (stop || phase != Phase.STREAMING || sessionKeyHex.isBlank()) return
        val end = activeHistoryEndExclusive
        val start = activeHistoryStart
        if (end <= 0) return // nothing in flight
        if (start < 0 || end <= start) {
            historyRetryCount = 0
            advanceHistoryChunkChain()
            return
        }
        if (historyRetryCount < HISTORY_MAX_RETRIES) {
            historyRetryCount++
            Log.w(TAG, "history page watchdog: no progress for chunk [$start,$end) — retry $historyRetryCount/$HISTORY_MAX_RETRIES")
            // Re-issue the same window (issueHistoryRequest re-arms the watchdog on success).
            if (!issueHistoryRequest("watchdog-retry", start, end - start, bypassCooldown = true)) {
                noteHoleFailure(start, end)
                advanceHistoryChunkChain()
            }
        } else {
            Log.e(TAG, "history page watchdog: chunk [$start,$end) failed after $historyRetryCount retries — " +
                "recorded in the hole ledger for a later retry")
            historyRetryCount = 0
            noteHoleFailure(start, end)
            advanceHistoryChunkChain()
        }
    }

    /** Percent of the running backfill, or -1 when nothing is being fetched. */
    private fun historyBackfillPercentNow(): Int =
        historyBackfillPercent(historyChainStart, pendingHistoryNextStart, pendingHistoryEndExclusive)

    private fun clearPendingHistoryRange() {
        handler.removeCallbacks(pendingHistoryChunkRunnable)
        cancelHistoryWatchdog()
        historyRetryCount = 0
        pendingHistoryReason = null
        pendingHistoryNextStart = 0
        pendingHistoryEndExclusive = 0
        historyChainStart = -1
        activeHistoryEndExclusive = -1
        activeHistoryStart = -1
        UiRefreshBus.requestStatusRefresh()
    }

    // ---- history hole ledger ----
    // Requested-but-undelivered windows. Added at request time for detected gaps, trimmed only
    // when their data actually arrives, retired by the cross-session attempt cap. Survives
    // disconnects and app restarts via OttaiRegistry (per-sensor pref).

    private fun addHistoryHole(start: Int, endExclusive: Int) {
        if (endExclusive <= start || start < 0) return
        synchronized(historyHolesLock) {
            if (historyHoles.any { it.start <= start && it.endExclusive >= endExclusive }) return // covered
            historyHoles += HistoryHole(start, endExclusive, 0)
            historyHoles.sortBy { it.start }
            while (historyHoles.size > MAX_HISTORY_HOLES) {
                val dropped = historyHoles.removeAt(0)
                Log.e(TAG, "history hole ledger full — dropping [${dropped.start},${dropped.endExclusive}) (records lost)")
            }
            persistHistoryHoles()
        }
    }

    /** Data for [start,endExclusive) actually arrived — the ONLY way a hole shrinks. */
    private fun trimHistoryHoles(start: Int, endExclusive: Int) {
        if (endExclusive <= start) return
        synchronized(historyHolesLock) {
            var changed = false
            val iterator = historyHoles.listIterator()
            while (iterator.hasNext()) {
                val h = iterator.next()
                when {
                    h.start >= start && h.endExclusive <= endExclusive -> { iterator.remove(); changed = true }
                    h.start in start until endExclusive -> { h.start = endExclusive; changed = true }
                    h.endExclusive in (start + 1)..endExclusive -> { h.endExclusive = start; changed = true }
                    // A mid-split (arrival strictly inside a hole) can't happen: requests are issued
                    // from hole/gap boundaries. If a future caller violates that, the hole just stays
                    // slightly larger than reality and self-corrects via refetch — data is never lost.
                }
            }
            if (changed) persistHistoryHoles()
        }
    }

    /** A request covering [start,endExclusive) failed to deliver — ledger it and bump its counter. */
    private fun noteHoleFailure(start: Int, endExclusive: Int) {
        if (endExclusive <= start || start < 0) return
        synchronized(historyHolesLock) {
            // Bump every overlapping hole, not just an exact match: failures arrive per issued
            // CHUNK, so a multi-chunk hole must absorb its chunks' failures or the attempt cap
            // never retires it (a permanently-empty overshoot range would then retry forever).
            var overlapped = false
            for (h in historyHoles) {
                if (h.start < endExclusive && start < h.endExclusive) {
                    h.attempts++
                    overlapped = true
                }
            }
            if (!overlapped) {
                historyHoles += HistoryHole(start, endExclusive, 1)
                historyHoles.sortBy { it.start }
            }
            historyHoles.removeAll { h ->
                (h.attempts >= HISTORY_HOLE_MAX_ATTEMPTS).also {
                    if (it) Log.e(TAG, "history range [${h.start},${h.endExclusive}) undelivered after ${h.attempts} attempts — giving up")
                }
            }
            persistHistoryHoles()
        }
    }

    /** Caller must hold [historyHolesLock]. */
    private fun persistHistoryHoles() {
        val id = SerialNumber ?: return
        if (id.isBlank()) return
        Applic.app?.let {
            OttaiRegistry.saveHistoryHoles(it, id, historyHoles.joinToString(";") { h -> "${h.start}:${h.endExclusive}:${h.attempts}" })
        }
    }

    private fun scheduleHoleRetry(delayMs: Long = HISTORY_HOLE_RETRY_DELAY_MS) {
        handler.removeCallbacks(holeRetryRunnable)
        val pending = synchronized(historyHolesLock) { historyHoles.isNotEmpty() }
        if (pending) handler.postDelayed(holeRetryRunnable, delayMs)
    }

    private fun retryNextHistoryHole() {
        // commandStatus >= 4 is an ended sensor, and its final backfill (runEndedHistoryBackfill)
        // goes through the same diff, which ledgers every window but the newest. Gating this on
        // == 3 meant those windows had no driver at all on the sensor's last connection: the
        // records existed, were known to be missing, and were never asked for.
        if (stop || phase != Phase.STREAMING || sessionKeyHex.isBlank() || commandStatus < 3) return
        if (activeHistoryEndExclusive > 0 || pendingHistoryReason != null) return // never preempt a live chain
        // Snapshot under the lock: the request below can re-enter the ledger, and the binder
        // thread may be mutating it concurrently from a history notification.
        val hole = synchronized(historyHolesLock) { historyHoles.firstOrNull()?.copy() } ?: return
        Log.i(TAG, "hole retry [${hole.start},${hole.endExclusive}) attempt=${hole.attempts}")
        requestHistoryRange("hole-retry", hole.start, hole.endExclusive - hole.start)
    }

    // ---- GATT helpers ----

    @Suppress("DEPRECATION")
    private fun readChar(gatt: BluetoothGatt, svc: UUID, ch: UUID): Boolean {
        val c = gatt.getService(svc)?.getCharacteristic(ch)
        if (c == null) { Log.w(TAG, "readChar missing $ch"); return false }
        val started = runCatching { gatt.readCharacteristic(c) }.getOrDefault(false)
        Log.i(TAG, "read ${ch.toString().take(8)}#${c.instanceId} props=0x${c.properties.toString(16)} started=$started")
        return started
    }

    private fun readCgmInfo(gatt: BluetoothGatt) {
        readChar(gatt, OttaiConstants.SERVICE_DEVICE_INFO, OttaiConstants.CHAR_CGM_INFO_NOTIFY)
    }

    private fun readLiveGlucose(gatt: BluetoothGatt, reason: String) {
        if (sessionKeyHex.isBlank()) return
        Log.i(TAG, "read live glucose reason=$reason")
        if (readChar(gatt, OttaiConstants.SERVICE_CGM, OttaiConstants.CHAR_GLUCOSE_LIVE)) {
            liveReadRetryCount = 0
            return
        }
        if (phase != Phase.STREAMING) return
        // A false start usually means another GATT op is still in flight (history chunk,
        // notify enable right after auth) — a transient, not a dead transport. Tearing the
        // link down here cost a full reconnect cycle per collision; retry briefly first.
        if (liveReadRetryCount < LIVE_READ_MAX_RETRIES) {
            liveReadRetryCount += 1
            val attempt = liveReadRetryCount
            handler.postDelayed({
                val current = mBluetoothGatt
                if (!stop && phase == Phase.STREAMING && current != null) {
                    readLiveGlucose(current, "$reason-retry$attempt")
                }
            }, LIVE_READ_RETRY_DELAY_MS)
            return
        }
        liveReadRetryCount = 0
        recoverGattAndReconnect("live read could not start")
    }

    private fun scheduleLivePoll(delayMs: Long = livePollIntervalMs) {
        if (stop || phase != Phase.STREAMING || sessionKeyHex.isBlank()) return
        handler.removeCallbacks(livePollRunnable)
        handler.postDelayed(livePollRunnable, delayMs.coerceIn(5_000L, 3_600_000L))
    }

    /** Returns true if a write was issued (characteristic resolved), false if missing. */
    @Suppress("DEPRECATION")
    private fun writeChar(gatt: BluetoothGatt, svc: UUID, ch: UUID, value: ByteArray): Boolean {
        val c = gatt.getService(svc)?.getCharacteristic(ch)
        if (c == null) { Log.w(TAG, "writeChar missing $ch"); return false }
        c.value = value
        // Match the official app (a.java d()): use the characteristic's supported write
        // type. The post-CCCD Invalid-Handle we saw was on an EXPIRED unit locking its
        // control chars; a no-response Write Command gave no ATT feedback and didn't
        // un-expire it, so this stays with the official's proven with-response form.
        c.writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        Log.i(TAG, "write ${ch.toString().take(8)}#${c.instanceId} props=0x${c.properties.toString(16)} wt=${c.writeType} len=${value.size}")
        runCatching { gatt.writeCharacteristic(c) }
        return true
    }

    // ---- OttaiDriver ----

    /** Apply freshly-fetched cloud materials (after bind/validate + decrypt). */
    fun applyMaterials(context: Context, m: OttaiRegistry.DeviceMaterials) {
        materials = m
        if (m.activeTimeMs > 0L) {
            provisionalActiveTimeMs = 0L
            OttaiRegistry.saveProvisionalActiveTime(context, SerialNumber.orEmpty(), 0L)
        } else {
            provisionalActiveTimeMs = OttaiRegistry.loadProvisionalActiveTime(context, SerialNumber.orEmpty())
        }
        authKeys = m.authKeys
        if (activatedMaxActiveMs <= 0L) {
            activatedMaxActiveMs = OttaiRegistry.loadAcceptedMaxActive(context, SerialNumber.orEmpty())
        }
        OttaiRegistry.saveMaterials(context, SerialNumber.orEmpty(), m)
    }

    override fun getCurrentSnapshot(maxAgeMillis: Long): OttaiCurrentSnapshot? {
        if (lastGlucoseAtMs == 0L) return null
        if (System.currentTimeMillis() - lastGlucoseAtMs > maxAgeMillis) return null
        val displayValue = if (Applic.unit == 1) {
            lastGlucoseMmol.takeIf { it.isFinite() && it > 0f } ?: (lastGlucoseMgdl / MGDL_PER_MMOLL)
        } else {
            lastGlucoseMgdl
        }
        return OttaiCurrentSnapshot(lastGlucoseAtMs, displayValue, lastRawCurrent, Float.NaN, SENSOR_GEN)
    }

    override fun getStartTimeMs(): Long = effectiveActiveTimeMs()
    // Rated/"official" end = activation + the cloud-reported rated lifetime (activeExpireTime ms;
    // ~14d for these units), falling back to the local default. Uses the effective activation so a
    // sensor started in the vendor app (no cloud activeTime, start recovered over BLE) still shows
    // a rated end instead of a blank.
    override fun getOfficialEndMs(): Long {
        val start = effectiveActiveTimeMs()
        if (start <= 0L) return 0L
        val ratedMs = materials.activeExpireTimeMs.takeIf { it > 0L } ?: OttaiConstants.DEFAULT_ACTIVE_EXPIRE_MS
        return start + ratedMs
    }
    // Expected end follows the maxActive value this sensor accepted. Before activation (or
    // for installations upgraded from older builds), fall back to the cloud-rated lifetime.
    override fun getExpectedEndMs(): Long {
        val start = effectiveActiveTimeMs()
        if (start <= 0L) return 0L
        // Real lifetime the firmware accepted at activation; fall back to the cloud-rated
        // horizon while it is still unknown (honest, not optimistically 30d).
        val acceptedMs = activatedMaxActiveMs.takeIf { it > 0L }
            ?: Applic.app?.let { OttaiRegistry.loadAcceptedMaxActive(it, SerialNumber.orEmpty()) }
            ?: 0L
        return start + OttaiConstants.expectedLifetimeMs(materials.activeExpireTimeMs, acceptedMs)
    }
    // Never expire on the calendar alone: past the negotiated horizon, keep reading while
    // samples arrive and declare expiry only after the stale grace.
    override fun isSensorExpired(): Boolean {
        if (commandStatus >= 4) return true
        val end = getExpectedEndMs()
        if (end <= 0L) return false
        val now = System.currentTimeMillis()
        if (now <= end) return false
        val lastData = lastGlucoseAtMs
        return lastData <= 0L || now - lastData > OttaiConstants.EXPIRED_STALE_GRACE_MS
    }
    override fun getSensorRemainingHours(): Int {
        val end = getExpectedEndMs(); if (end <= 0L) return -1
        val ms = end - System.currentTimeMillis(); return if (ms <= 0L) 0 else (ms / 3_600_000L).toInt()
    }
    override fun getSensorAgeHours(): Int {
        val start = effectiveActiveTimeMs()
        if (start <= 0L) return -1
        return ((System.currentTimeMillis() - start) / 3_600_000L).toInt()
    }
    override fun getReadingIntervalMinutes(): Int = OttaiConstants.DEFAULT_READING_INTERVAL_MINUTES

    override val vendorFirmwareVersion: String get() = materials.deviceVersion
    override val vendorModelName: String get() = OttaiConstants.DEFAULT_DISPLAY_NAME
    override fun isUiEnabled(): Boolean = !stop
    override fun isVendorConnectedForUi(): Boolean =
        !stop && phase == Phase.STREAMING && sessionKeyHex.isNotBlank() && commandStatus < 4 && !isConnectionStale()

    override fun matchesManagedSensorId(sensorId: String?): Boolean =
        OttaiRegistry.resolveCanonicalSensorId(Applic.app, sensorId)
            ?.let { OttaiConstants.matchesCanonicalOrKnownNativeAlias(it, SerialNumber) }
            ?: OttaiConstants.matchesCanonicalOrKnownNativeAlias(sensorId, SerialNumber)

    override fun hasNativeSensorBacking(): Boolean = false

    private fun activationProgressStatus(): String {
        val total = maxActiveCandidatesMs.size.coerceAtLeast(1)
        val targetMs = maxActiveCandidatesMs.getOrNull(maxActiveCandidateIndex)
            ?: materials.activeExpireTimeMs.takeIf { it > 0L }
            ?: OttaiConstants.DEFAULT_ACTIVE_EXPIRE_MS
        val days = (targetMs / (24L * 60L * 60L * 1000L)).toInt()
        return if (activationCandidateDiscoveryPending) {
            appString(
                R.string.ottai_status_waiting_for_sensor,
                "Waiting for sensor • next: $days days (${maxActiveCandidateIndex + 1}/$total)",
                days,
                maxActiveCandidateIndex + 1,
                total,
            )
        } else {
            appString(
                R.string.ottai_status_activating_days,
                "Activating: $days days (${maxActiveCandidateIndex + 1}/$total)",
                days,
                maxActiveCandidateIndex + 1,
                total,
            )
        }
    }

    override fun getDetailedBleStatus(): String {
        val loss = lossOfSignalText()
        val hasLossStatus = constatstatusstr == "Loss of signal" || constatstatusstr == loss
        if (isConnectionStale()) return loss
        if (commandStatus >= 4) return appString(
            R.string.ottai_state_expired,
            "Expired or ended",
        )
        if (activationNegotiationActive) {
            if (activationRetryPending && OttaiNfc.isActivationRetryArmed(SerialNumber)) {
                return appString(
                    R.string.ottai_nfc_dump,
                    "Wake sensor with NFC",
                )
            }
            return activationProgressStatus()
        }
        if (activationFailed) return appString(
            R.string.ottai_status_activation_failed,
            "Activation failed",
        )
        if (OttaiConstants.commandNeedsActivation(commandStatus) && activationCommandSentAtMs <= 0L) return appString(
            R.string.ottai_state_not_activated,
            "Ready to activate",
        )
        return when (phase) {
            Phase.IDLE -> if (hasLossStatus) loss
                else if (awaitingFreshActivationAdvertisement &&
                    OttaiNfc.isActivationRetryArmed(SerialNumber)
                ) appString(
                    R.string.ottai_nfc_dump_armed,
                    "Hold the sensor near NFC",
                )
                else if (awaitingFreshActivationAdvertisement) appString(
                    R.string.looking_for_transmitters,
                    "Looking for nearby transmitters...",
                )
                else if (authKeys == null) "Needs cloud bind"
                else if (knownBleAddress() == null) appString(
                    R.string.ottai_status_needs_ble_address,
                    "Needs BLE address",
                )
                else "Idle"
            Phase.CONNECTING -> if (hasLossStatus) loss else "Connecting"
            Phase.DISCOVERING -> "Discovering"
            Phase.ENABLING_NOTIFY -> "Subscribing"
            Phase.AUTH -> "Authenticating"
            Phase.STREAMING -> if (historyBackfillPercentNow() >= 0) appString(
                R.string.ottai_status_loading_history,
                "Loading history • ${historyBackfillPercentNow()}%",
                historyBackfillPercentNow(),
            )
            else if (lastGlucoseAtMs > 0L) {
                "Connected"
            } else if (effectiveActiveTimeMs() > 0L) {
                val remaining = warmupRemainingMs()
                val minutes = ((remaining + 59_999L) / 60_000L).toInt()
                if (remaining > 0L) appString(
                    R.string.ottai_status_warmup_provisional,
                    "Provisional warmup • ${minutes}m left",
                    minutes,
                )
                else "Streaming (awaiting data)"
            } else "Streaming (awaiting data)"
        }
    }
}
