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
import android.os.Looper
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.util.UUID
import kotlin.math.abs
import tk.glucodata.Applic
import tk.glucodata.HistoryRepositoryAccess
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
import tk.glucodata.drivers.ManagedSensorUiFamily

@SuppressLint("MissingPermission")
class OttaiBleManager(
    serial: String,
    dataptr: Long,
) : SuperGattCallback(serial, dataptr, SENSOR_GEN), OttaiDriver {

    companion object {
        private const val TAG = OttaiConstants.TAG
        const val SENSOR_GEN = 0
        private const val RECONNECT_DELAY_MS = 3_000L
        // Link supervision timeout — the peer stopped answering rather than closing the link.
        // Android exports no constant for the BTA GATT_CONN_* codes, and this was the status on
        // all 88 drops of the 2026-08-01 jamming storm. See reconnectDelayAfterDisconnectMs.
        private const val GATT_CONN_TIMEOUT = 8
        private const val SUPERVISION_TIMEOUT_RECONNECT_DELAY_MS = 1_500L
        // Below this many consecutive failed connects, the flat 3s default holds — it is what
        // recovered an isolated drop in the 2026-08-01 storm. At or past it, a run of failures
        // means the peripheral is not answering at all, and retrying at the same cadence forever
        // just burns the radio; back off instead. See reconnectDelayAfterDisconnectMs.
        private const val FAILURE_STREAK_BACKOFF_THRESHOLD = 3
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        // The generic stack error a connectGatt() issued before registerApp() has settled comes
        // back as; see fastReArmBounced.
        private const val GATT_ERROR_STATUS = 133
        private const val FAST_REARM_BOUNCE_WINDOW_MS = 2_000L
        private const val MTU = 247
        private const val MTU_SETTLE_BEFORE_DISCOVERY_MS = 1_250L
        private const val MTU_CALLBACK_FALLBACK_MS = 2_500L
        private const val SERVICE_DISCOVERY_TIMEOUT_MS = 10_000L
        private const val V3_CREDENTIAL_BOOTSTRAP_TIMEOUT_MS = 90_000L

        /**
         * Set (by the setup wizard) to a canonical sensorId to request a one-time
         * activation on that sensor's next successful auth. Cleared once fired.
         * Activation starts the sensor's irreversible lifetime, so it is only ever
         * armed by an explicit user action.
         */
        @Volatile @JvmStatic var activateRequestedFor: String? = null

        /**
         * Advanced Activate only. Not [requestForceActivation] — leftover 0–2 first-use
         * still posted that helper. Wizard Connect must never set this.
         */
        @Volatile @JvmStatic var advancedActivateRequestedFor: String? = null

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
        // A sensor this young has nothing behind its live frame — dataNo counts minutes since
        // activation and estimatedNewestDataNo() stays two records short of now, so the backstop
        // cannot possibly find a basis. Defer instead of burning attempts: on 2026-07-29 it
        // probed a sensor activated four seconds earlier five times and closed with a "gave up"
        // warning that reads like a failure after every successful activation.
        private const val INITIAL_HISTORY_MIN_SENSOR_AGE_MS = 3L * 60_000L
        // Confirming that activation took is the only read worth issuing straight after the
        // command; see markActivationCommandSent. Retried a few times because a read that cannot
        // start (another operation still in flight) otherwise leaves status=3 unseen until some
        // later poll happens to pick it up.
        private const val POST_ACTIVATION_CONFIRM_DELAY_MS = 1_000L
        private const val POST_ACTIVATION_CONFIRM_RETRY_MS = 1_500L
        private const val POST_ACTIVATION_CONFIRM_MAX_ATTEMPTS = 4
        // History chunk-chain watchdog: an in-flight chunk whose response never lands (dropped
        // notify or short/empty frame) silently stalls the whole download. If no progress within
        // the timeout, retry the same window a bounded number of times, then skip it after
        // recording the window in the persisted hole ledger (retried when the chain is idle;
        // only arrived data or the cross-session attempt cap removes a hole).
        private const val HISTORY_PAGE_TIMEOUT_MS = 12_000L
        internal const val HISTORY_MAX_RETRIES = 3
        // Hole ledger: requested-but-undelivered history windows, persisted per sensor (see
        // historyHoles). Attempts are cross-session; the size cap bounds the pref for a
        // runaway overshoot. Overflow drops the OLDEST window and loses those records, so the
        // cap has to stay clear of what a normal session produces: up to
        // MAX_HISTORY_GAP_RANGES diff windows plus whatever the chunk watchdog records.
        internal const val MAX_HISTORY_HOLES = 32
        // Diffing the sensor's records against the local store yields the windows that are
        // genuinely missing. Two windows separated by fewer than this many stored records are
        // merged: one slightly redundant request costs less air time than a second round trip.
        // At most MAX_HISTORY_GAP_RANGES windows are produced, so a fragmented history cannot
        // flood the ledger. Both rules only ever widen a window — coverage is never traded away.
        private const val HISTORY_GAP_COALESCE_RECORDS = 5
        private const val MAX_HISTORY_GAP_RANGES = 8
        // Widest hole the live path will ledger on its own. Anything larger is not a dropout
        // during a live session, it is a store that needs the initial reconciliation, and
        // enqueueing it here would spend the ledger's bounded attempts on the wrong problem.
        private const val MAX_LIVE_GAP_RECORDS = 240
        internal const val HISTORY_HOLE_MAX_ATTEMPTS = 5
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
        // ...and only twice per link. The grip above was written blind because its own callback
        // was being deleted by R8; the 2026-08-01 storm logs, taken after the keep rule landed,
        // say the sensor takes the fast params back every single time: 46/46 reclaims, median
        // 5.8s (min 5.2, max 18.8), always returning to interval=308 latency=4 timeout=600. It
        // also buys nothing measurable -- 1 drop per 564s of fast params against 82 per 23963s of
        // slow ones, Poisson p~0.42. And linkUnstable (2 abnormal drops in 15min) is permanently
        // true in that RF environment, so an unbounded grip is ~133 reasserts per sensor per 4h,
        // each cutting the sensor's effective listen period from 1925ms to 15ms for ~5.8s --
        // ~128x the connection events, on firmware budgeted for a 15-day battery.
        // Counting the reclaims themselves and giving up after two was tried and dropped: every
        // STATE_CONNECTED asks for CONNECTION_PRIORITY_HIGH, so the fast-param update answering
        // that request clears any "the sensor already said no" memory on each of the ~44
        // reconnects per sensor, and a reclaim cap could never be reached.
        internal const val MAX_PRIORITY_REASSERTS_PER_CONNECTION = 2
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
        // How far above a pending claim a frame may sit and still count as a second read of the
        // same disagreement. Both starts are (arrival minute - dataNo * interval), so two frames
        // hours apart agree as long as the offset between the clocks has not changed in between:
        // unbounded, a claim left behind by a corrupt frame or by a link that dropped after one
        // frame would corroborate a frame from much later and move the confirmed anchor on one
        // frame's word. A tuning handle. The distance it bounds runs from a held live frame to the
        // next live frame weighed against that frame's claim, across reconnects too: the claim is
        // not cleared per link, and a held frame of another record takes it over (heldLiveClaim).
        // Too narrow has a price. While the confirmed anchor disagrees, a frame past the window is
        // held — neither stored nor published — and history waits on anchorDisputed. A link whose
        // next frame comes a record or two later can still settle that; links that each bring one
        // frame, further apart than this, cannot. Tune it from logs of that distance rather than
        // from hole sizes, and keep it far short of the gap a stale claim leaves.
        private const val CONFIRMED_CLAIM_MAX_GAP_RECORDS = 15
        private const val CURRENT_SAMPLE_FRESH_MS = 120_000L
        private const val CURRENT_SAMPLE_FLOOR_GRACE_MS = RECORD_INTERVAL_MS
        private const val MAX_REASONABLE_DATA_NO_AHEAD = 120
        private const val MGDL_PER_MMOLL = 18.0f
        // Consecutive continuity rejections after which the gate yields and re-baselines onto the
        // next sample. Sustained >=1.5 mmol/min steps are not physiology, but latching forever
        // would blind the driver to a genuine electrode restart, so the gate gives way rather
        // than holding a baseline the sensor has clearly left behind.
        internal const val MAX_CONSECUTIVE_CONTINUITY_REJECTS = 3

        // ---- outage instrumentation ----
        // None of the three measurements below changes what the driver does; they exist because
        // the 2026-08-01 analysis could not answer three questions from an 8995-line trace, and
        // no further reconnect work can be justified without them.

        // How long the outage probe listens for the sensor's advertisement. Long enough to cover
        // the median connectGatt->connected of that storm (22s and 33s for the two sensors), short
        // enough that a link dropping every ~2.7min on average is not scanning continuously.
        private const val OUTAGE_PROBE_TIMEOUT_MS = 30_000L
        // Android throttles an app to 5 scan starts per 30s and parks the offender for half an
        // hour. One probe per outage already bounds this, but a burst of drops can end and restart
        // an outage several times a minute, so hold a floor between probes as well — and hold it
        // per process, not per sensor: the throttle is per uid, the 2026-08-01 storm had two Ottai
        // sensors dropping together, and the startScan the platform refuses can just as easily be
        // SensorBluetooth's managed scan, which is the recovery path for every other sensor.
        internal const val OUTAGE_PROBE_MIN_GAP_MS = 60_000L
        @Volatile private var lastOutageProbeAtMsShared = 0L
        // RSSI cadence. Every few minutes, not every reading: the question it answers ("was the
        // sensor far away or was the band busy?") does not move in seconds, and the sensor screens
        // read one value.
        internal const val RSSI_POLL_INTERVAL_MS = 300_000L
        // The first sample must land early: 88 drops in 4h means the median session is minutes
        // long, and an RSSI series that only starts after five of them would be empty exactly
        // during a storm.
        private const val RSSI_FIRST_POLL_DELAY_MS = 20_000L

        /**
         * Whether an outage may be probed for the sensor's advertisement now.
         *
         * [activationScanActive] is the hard exclusion: the activation/candidate scan owns the
         * radio and the transport during setup, and a second scan racing it is exactly the
         * interference this probe must never introduce. [managedScanActive] is the same argument
         * one level up — SensorBluetooth's scan finds every sensor family, and losing its
         * startScan to this app's own throttle would cost far more than the measurement is worth.
         */
        internal fun shouldProbeOutageAdvertisement(
            alreadyProbedThisOutage: Boolean,
            activationScanActive: Boolean,
            managedScanActive: Boolean,
            nowMs: Long,
            lastProbeAtMs: Long,
        ): Boolean {
            if (alreadyProbedThisOutage || activationScanActive || managedScanActive) return false
            if (lastProbeAtMs <= 0L) return true
            val sinceMs = nowMs - lastProbeAtMs
            // A clock step backwards must not latch the probe off for good — same rule as
            // shouldReadLiveAfterHistory, and this one would otherwise be silent forever.
            return sinceMs < 0L || sinceMs >= OUTAGE_PROBE_MIN_GAP_MS
        }

        /** Whether the RSSI poll is due. Off outside STREAMING: there is no link to measure. */
        internal fun shouldPollRssi(streaming: Boolean, nowMs: Long, lastRssiReadAtMs: Long): Boolean {
            if (!streaming) return false
            if (lastRssiReadAtMs <= 0L) return true
            val sinceMs = nowMs - lastRssiReadAtMs
            return sinceMs < 0L || sinceMs >= RSSI_POLL_INTERVAL_MS
        }

        /**
         * Whether the shared scan should run at full duty for this sensor: only inside the bounded
         * wait for a fresh-activation advertisement. Judged by the arming time as well as the flag,
         * because resetActivationNegotiation drops the timeout that would clear the flag without
         * clearing it, and a stale flag must not keep the radio at full duty.
         */
        internal fun wantsLowLatencyActivationScan(awaiting: Boolean, armedAtMs: Long, nowMs: Long): Boolean {
            if (!awaiting || armedAtMs <= 0L) return false
            // A clock step backwards reads as outside the window, never as an endless one.
            return nowMs - armedAtMs in 0L until FRESH_ACTIVATION_ADVERTISEMENT_TIMEOUT_MS
        }

        /**
         * Stale-activity threshold while streaming. The poll interval is what the sensor is
         * expected to speak on, so a slow poll must widen the window it is judged against.
         */
        internal fun streamingStaleThresholdMs(livePollIntervalMs: Long): Long =
            maxOf(STREAM_ACTIVITY_STALE_MS, livePollIntervalMs * 3L + 15_000L)

        internal fun isFreshLiveSample(receivedAtMs: Long, sampleMs: Long): Boolean =
            receivedAtMs > 0L &&
                sampleMs > 0L &&
                abs(receivedAtMs - sampleMs) <= CURRENT_SAMPLE_FRESH_MS + CURRENT_SAMPLE_FLOOR_GRACE_MS

        /**
         * A sample dated further past its own arrival than [isFreshLiveSample] tolerates. No record
         * postdates the frame that carried it, so a date that far ahead means the dataNo or the
         * start it was dated from is wrong: it must not be stored, nor become the mark later live
         * samples are compared against.
         */
        internal fun isSampleAheadOfArrival(receivedAtMs: Long, sampleMs: Long): Boolean =
            receivedAtMs > 0L &&
                sampleMs - receivedAtMs > CURRENT_SAMPLE_FRESH_MS + CURRENT_SAMPLE_FLOOR_GRACE_MS

        /**
         * The last-glucose mark a sample arriving at [receivedAtMs] is compared against. A mark
         * further past that arrival than a fresh sample can be (the wall clock stepped back since
         * it was set) counts as none: kept, it would hold off live storing and publishing until the
         * clock caught up with it.
         */
        internal fun glucoseMarkBaseline(receivedAtMs: Long, lastGlucoseAtMs: Long): Long =
            if (isSampleAheadOfArrival(receivedAtMs, lastGlucoseAtMs)) 0L else lastGlucoseAtMs

        /**
         * Whether a sample moves the last-glucose mark. Only a fresh live sample does: history is
         * backfill, and letting it move the mark made it the bar later live samples had to clear
         * to be stored or published. Both halves of that rule sit here rather than at the call
         * site, so a test can hold them: history dated fresh enough to pass for a live sample
         * still does not move the mark, and a live sample that is not fresh does not either.
         */
        internal fun advancesGlucoseMark(
            live: Boolean,
            receivedAtMs: Long,
            sampleMs: Long,
            previousGlucoseAtMs: Long,
        ): Boolean = live && isFreshLiveSample(receivedAtMs, sampleMs) && sampleMs >= previousGlucoseAtMs

        /**
         * The stream anchor history may be dated and diffed against, or 0 while none can be
         * trusted — the trust rule of nativePresenceStartTimeMs, which answers the confirmed start
         * itself rather than this anchor. Unconfirmed, only an anchor a live frame of this link
         * checked against its arrival counts: the seeds without an arrival time derive from
         * effectiveActiveTimeMs(), then an unchecked anchor or the provisional — for a
         * vendor-activated sensor days after the true start. Once the start is confirmed those
         * seeds derive from it, and a live frame moves the anchor only as [monitorMayAnchor] and
         * [datesLiveByArrival] allow.
         */
        internal fun trustedStreamStartMs(
            streamStartMs: Long,
            streamStartReliable: Boolean,
            confirmedStartMs: Long,
        ): Long = if (streamStartMs > 0L && (streamStartReliable || confirmedStartMs > 0L)) streamStartMs else 0L

        /**
         * Whether a record takes the trusted stream anchor's date instead of going back through the
         * live anchor path. History does: it has no arrival time to check a date against. So does
         * the last stored record read again (a notify and the poll behind it, a stopped counter),
         * which dated by its arrival would be re-issued as a new reading — but not a record below
         * that mark: after a corrupt dataNo the mark can sit ahead of the sensor. Any other live
         * record takes it only once the start is confirmed, and only while the anchored date agrees
         * with the arrival, so that after a clock step the anchor can move instead of freezing live
         * out of freshness.
         */
        internal fun datesFromStreamAnchor(
            live: Boolean,
            dataNo: Int,
            lastDataNo: Int,
            confirmed: Boolean,
            receivedAtMs: Long,
            anchoredMs: Long,
        ): Boolean =
            !live || dataNo == lastDataNo || (confirmed && isFreshLiveSample(receivedAtMs, anchoredMs))

        /**
         * Whether a second activation-start claim corroborates the pending one. Both are an
         * observed time minus dataNo * interval, so a genuine pair from two records lands within a
         * couple of records while a corrupt dataNo lands far away. The same record read twice
         * agrees with itself by construction — a corrupt front delivered by a notify and again by
         * the poll would confirm itself — so it does not count.
         */
        internal fun startsCorroborate(
            pendingStartMs: Long,
            pendingDataNo: Int,
            startMs: Long,
            dataNo: Int,
        ): Boolean =
            pendingStartMs > 0L &&
                startMs > 0L &&
                dataNo != pendingDataNo &&
                abs(pendingStartMs - startMs) <= CONFIRMED_START_AGREEMENT_MS

        /**
         * What offerConfirmedActiveTime does with a reliable start claim [startMs] from record
         * [dataNo], given the confirmed start and the pending claim. The commit is one-way, so
         * only a claim from another record that [startsCorroborate]s the pending one commits, and
         * it commits that newer claim. A claim that disagrees replaces the pending one. No bound
         * on how far apart the two records sit: that window is datesLiveByArrival's, not this one.
         */
        internal fun confirmationStep(
            confirmedStartMs: Long,
            pendingStartMs: Long,
            pendingDataNo: Int,
            startMs: Long,
            dataNo: Int,
            nowMs: Long,
        ): ConfirmStep {
            if (startMs <= 0L || confirmedStartMs > 0L) return ConfirmStep.Ignore
            // Physically impossible starts are rejected outright rather than corroborated.
            if (startMs > nowMs || nowMs - startMs > OttaiConstants.EXTENDED_LIFETIME_MS) {
                return ConfirmStep.Implausible
            }
            if (pendingStartMs <= 0L) return ConfirmStep.Pend(startMs, dataNo)
            // The claiming record read again (a notify and the poll behind it, a retry) is not a
            // second observation. Keep the first read, the fresher of the two, and wait for another.
            if (dataNo == pendingDataNo) return ConfirmStep.Ignore
            if (startsCorroborate(pendingStartMs, pendingDataNo, startMs, dataNo)) return ConfirmStep.Commit(startMs)
            return ConfirmStep.Rearm(startMs, dataNo)
        }

        /**
         * Whether a live frame whose monitor time agrees with its arrival anchors the stream at
         * [monitorStartMs], its monitor time minus dataNo * interval. Unconfirmed it does: that
         * arrival check makes the frame a read of its own. Confirmed, monitorTimeMs is the
         * confirmed start plus the record's runtime, so a start away from [confirmedStartMs] only
         * says the record's dataNo and runtime disagree — one frame's word, which the arrival path
         * then holds ([datesLiveByArrival]).
         */
        internal fun monitorMayAnchor(confirmedStartMs: Long, monitorStartMs: Long): Boolean =
            confirmedStartMs <= 0L || abs(monitorStartMs - confirmedStartMs) <= CONFIRMED_START_AGREEMENT_MS

        /**
         * The start a live frame's monitor time claims, floor(monitor time) minus dataNo minutes,
         * or 0 when that monitor time may not anchor the stream: it lies further from the frame's
         * arrival than [CURRENT_SAMPLE_FRESH_MS], or, once confirmed, away from the confirmed start
         * ([monitorMayAnchor]). The check is what makes a monitor time that echoes an anchor or a
         * pending claim ([monitorBaseStartMs]) a read of its own.
         */
        internal fun monitorLiveStartMs(
            receivedAtMs: Long,
            monitorMs: Long,
            dataNo: Int,
            confirmedStartMs: Long,
        ): Long {
            if (monitorMs <= 0L || abs(receivedAtMs - monitorMs) > CURRENT_SAMPLE_FRESH_MS) return 0L
            val startMs = monitorMs / RECORD_INTERVAL_MS * RECORD_INTERVAL_MS - dataNo.toLong() * RECORD_INTERVAL_MS
            return if (monitorMayAnchor(confirmedStartMs, startMs)) startMs else 0L
        }

        /**
         * Whether a refused reading goes into the recent-rejected veto, which refuses a record of the
         * same dataNo offered again with the same raw current or about the same value. A non-finite
         * glucose adds nothing to it: hardRejectReason refuses it on every re-evaluation with the
         * same materials anyway. Remembered, it would go on refusing those records after the
         * materials change, and a void coefficient set or a malformed method (OttaiFormula.evaluate,
         * OttaiRegistry.parseCoefficients) yields NaN for every record: the history retries would
         * bring them back once valid materials arrive, only to be refused again.
         */
        internal fun remembersRejection(mmol: Float): Boolean = mmol.isFinite()

        /**
         * The start claim a held live frame leaves behind for the next record to be checked
         * against ([datesLiveByArrival]). A record other than the one already claiming takes the
         * claim over: the standing claim is what holds later frames out, so a frame that disagrees
         * with it has to replace it or the hold has no way to end. The same record held again
         * keeps its first claim — one record read twice is not a second observation, and
         * re-stamping it with the later arrival would let that record corroborate itself.
         */
        internal fun heldLiveClaim(
            pendingStartMs: Long,
            pendingDataNo: Int,
            arrivalStartMs: Long,
            dataNo: Int,
        ): Pair<Long, Int> =
            if (dataNo == pendingDataNo && pendingStartMs > 0L) pendingStartMs to pendingDataNo
            else arrivalStartMs to dataNo

        /**
         * Whether a live frame that neither the stream anchor nor its monitor time could date takes
         * its date from its arrival, at [arrivalStartMs] (arrival minute minus dataNo * interval).
         * Unconfirmed it does: at the start of a link nothing else dates it. Confirmed, the last
         * stored record read again does not — a stopped counter would be re-issued as "now" — and a
         * new record does only once a claim from a nearby record agrees ([startsCorroborate]): one
         * frame that disagrees with the confirmed anchor may be a clock step, sensor drift or a
         * corrupt dataNo, and nothing in the frame tells them apart. Nearby is part of the rule: a
         * claim is evidence about the records around it, not for the rest of the session, so the
         * frame has to sit within [CONFIRMED_CLAIM_MAX_GAP_RECORDS] above the claiming record —
         * and a frame below it, which is where a corrupt low dataNo lands, is no second read at
         * all. The window is not what refuses an absent claim: the pendingStartMs check inside
         * [startsCorroborate] is, and has to stay, because a freshly activated sensor's first
         * records sit inside the window of the -1 that means "no claim".
         */
        internal fun datesLiveByArrival(
            confirmed: Boolean,
            dataNo: Int,
            lastDataNo: Int,
            pendingStartMs: Long,
            pendingDataNo: Int,
            arrivalStartMs: Long,
        ): Boolean =
            !confirmed ||
                (dataNo != lastDataNo &&
                    dataNo - pendingDataNo in 1..CONFIRMED_CLAIM_MAX_GAP_RECORDS &&
                    startsCorroborate(pendingStartMs, pendingDataNo, arrivalStartMs, dataNo))

        /**
         * History always persists. Live persists only a fresh sample that is newer than the last
         * stored glucose — an unfresh live frame must not advance [lastDataNo], or backfill never
         * asks for that dataNo again.
         */
        internal fun shouldPersistGlucoseSample(
            live: Boolean,
            freshLiveSample: Boolean,
            sampleMs: Long,
            previousGlucoseAtMs: Long,
        ): Boolean = !live || (freshLiveSample && sampleMs > previousGlucoseAtMs)

        internal fun shouldAdvanceSeenDataNo(advancesDataNo: Boolean, persist: Boolean): Boolean =
            advancesDataNo && persist

        /**
         * The warmup gate's start: a confirmed activation start when there is one, otherwise the
         * later of the two activation-command instants — [commandAckedAtMs], the response to the
         * command in this process, and [commandWrittenAtMs], the write itself or the value restored
         * after a restart. The later one, because a retry after an acknowledged attempt that did not
         * start the sensor writes a newer command, and the older acknowledgement would otherwise end
         * the gate before the new ramp does. Wrong in the safe direction between the two command
         * instants: the later one only suppresses more.
         */
        internal fun warmupAnchorFor(confirmedStartMs: Long, commandAckedAtMs: Long, commandWrittenAtMs: Long): Long =
            confirmedStartMs.takeIf { it > 0L }
                ?: maxOf(commandAckedAtMs, commandWrittenAtMs).takeIf { it > 0L }
                ?: 0L

        /**
         * Whether the post-activation settling window suppresses this record: by the sample's date
         * against [anchorMs] ([OttaiConstants.isWithinWarmup]), or — whenever this app holds an
         * activation instant for the sensor at all — by the record's own counter.
         *
         * The date alone is not enough. The anchor a sample is dated from can move into another
         * clock domain mid-ramp (a clock step re-anchors the stream) while [anchorMs] stays on the
         * confirmed start, and the ramp's records then date past the window and reach publishing as
         * current glucose — on the 2026-07-29 activation they read 2.4-2.8 mmol, a false hypo.
         * [dataNo] is the sensor's own minute counter from activation, an LE16 field that no clock
         * step moves and that is never negative, so a record less than WARMUP_SUPPRESS_MS of
         * runtime in is suppressed however it ends up dated. Only ever ADDED to the date criterion,
         * never substituted for it: a record the date wrongly suppresses (a backward clock step
         * re-anchors the stream earlier) stays suppressed, which is the direction this gate is
         * allowed to be wrong in.
         *
         * Armed off [anchorMs], not off the confirmed start. A non-zero anchor is either that
         * start or an activation-command instant this app wrote for this sensor ([warmupAnchorFor]),
         * and under both the counter really is minutes since our own activation. The unconfirmed
         * window is not covered by the date either — the anchor sits on the command instant while a
         * clock step re-dates the sample, the same two-domain mismatch, for the 45 s the
         * [warmupAnchorMs] KDoc records from the 2026-07-29 run. A zero anchor means the gate is off
         * altogether for a sensor this app did not activate, and the counter stays off with it,
         * rather than suppressing a ramp this gate has never touched.
         */
        internal fun warmupSuppresses(anchorMs: Long, sampleMs: Long, dataNo: Int): Boolean =
            OttaiConstants.isWithinWarmup(anchorMs, sampleMs) ||
                (anchorMs > 0L && dataNo.toLong() * RECORD_INTERVAL_MS < OttaiConstants.WARMUP_SUPPRESS_MS)

        /**
         * Exclusive history end after a live emit. A persisted sample is already in Room, so the
         * cursor ([lastDataNo]) excludes it. An unpersisted sample must still be asked for:
         * `[previous+1, sampleDataNo+1)`.
         */
        internal fun liveBackfillEndExclusive(
            persisted: Boolean,
            lastDataNo: Int,
            sampleDataNo: Int,
        ): Int = if (persisted) lastDataNo else sampleDataNo + 1

        /** Skip range left behind when a persisted live jumps the cursor by more than one. */
        internal fun skippedLiveRange(previousLastDataNo: Int, newDataNo: Int): IntRange? {
            if (previousLastDataNo < 0 || newDataNo <= previousLastDataNo + 1) return null
            return previousLastDataNo + 1 until newDataNo
        }

        /**
         * Unfresh live is not stored and must not move [lastDataNo]. It still has to drive a
         * history fetch after the one-shot Room backfill latch, otherwise dataNo N is never asked
         * for on this connection.
         */
        internal fun shouldFetchHistoryAfterUnfreshLive(
            persisted: Boolean,
            historyAlreadyIssued: Boolean,
            previousForHistory: Int,
            liveEndExclusive: Int,
        ): Boolean {
            if (persisted || historyAlreadyIssued) return false
            if (liveEndExclusive <= 0) return false
            if (previousForHistory < 0) return true
            return liveEndExclusive - previousForHistory - 1 > 0
        }

        internal fun shouldArmHoleRetryForSkippedLive(
            previousLastDataNo: Int,
            newDataNo: Int,
        ): Boolean = skippedLiveRange(previousLastDataNo, newDataNo) != null

        /**
         * Ended-sensor live buffer: take the highest sane dataNo at or below the live ceiling.
         * A corrupt ~17k front must not become the persisted cursor for the final backfill.
         */
        internal fun endedLiveIndexRecord(
            records: List<OttaiRecord>,
            ceiling: Int,
        ): OttaiRecord? = records
            .filter { OttaiParser.isRecordSane(it) && (ceiling == Int.MAX_VALUE || it.dataNo <= ceiling) }
            .maxByOrNull { it.dataNo }

        /**
         * Ended indexing must not reuse the unbounded live ceiling. Without a confirmed start,
         * cap at the persisted high-water mark plus [MAX_REASONABLE_DATA_NO_AHEAD]. When start
         * is known, the time-based live ceiling already rejects a corrupt ~17k front and must
         * not be tightened by a stale lastDataNo (hours/days of ended samples after 1600).
         */
        internal fun endedLiveDataNoCeiling(
            authoritativeStartMs: Long,
            nowMs: Long,
            lastDataNo: Int,
        ): Int {
            val liveCeil = dataNoCeilingFor(authoritativeStartMs, nowMs, lastDataNo, live = true)
            val startUnknown = authoritativeStartMs <= 0L || authoritativeStartMs >= nowMs
            if (!startUnknown) return liveCeil
            val fromLast = if (lastDataNo > 0) lastDataNo + MAX_REASONABLE_DATA_NO_AHEAD else Int.MAX_VALUE
            return minOf(liveCeil, fromLast)
        }

        /**
         * Whether the one-shot live read after a history payload is worth its round-trip.
         *
         * The one-shot exists for a history burst that ends several minutes behind wall time, but
         * that is not how history is normally asked for: requestRoomBackfillAfterLive issues it
         * about a second after a live frame, which in the 2026-08-01 logs was 100% of the requests.
         * All 26 re-reads returned records the app already held — 24 a byte-identical ciphertext,
         * the other 2 a fresh ciphertext carrying a dataNo a live notify had brought in 0-1 s
         * earlier — for an ATT round-trip, an AES-CBC decrypt and a ~200 KB appendTemperatureHistory
         * rebuild each. Below a record interval the sensor has nothing newer to give.
         */
        internal fun shouldReadLiveAfterHistory(receivedAtMs: Long, lastLiveFrameAtMs: Long): Boolean {
            if (lastLiveFrameAtMs <= 0L) return true
            val sinceLiveMs = receivedAtMs - lastLiveFrameAtMs
            // A clock step backwards must not latch the one-shot off: this is the only read that
            // catches up a history burst which ended behind wall time.
            return sinceLiveMs < 0L || sinceLiveMs >= RECORD_INTERVAL_MS
        }

        /**
         * Adjacency window for the continuity gate: is [dataNo]/[sampleMs] close enough behind
         * the previously evaluated sample for a one-minute excursion comparison to mean anything?
         *
         * Two records or 135 s, whichever the caller can establish. The dataNo test comes first
         * because it survives a missing/derived timestamp; the time test covers a replay whose
         * counter has been reset.
         */
        internal fun isAdjacentSample(
            previousDataNo: Int,
            previousSampleMs: Long,
            dataNo: Int,
            sampleMs: Long,
        ): Boolean {
            if (previousDataNo < 0) return false
            if (dataNo - previousDataNo in 1..2) return true
            if (previousSampleMs <= 0L || sampleMs <= previousSampleMs) return false
            return (sampleMs - previousSampleMs) in 1..(2 * RECORD_INTERVAL_MS + 15_000L)
        }

        internal fun isPersistedDataNoAheadOfLive(previousDataNo: Int, liveDataNo: Int): Boolean =
            previousDataNo >= 0 &&
                liveDataNo >= 0 &&
                previousDataNo > liveDataNo + MAX_REASONABLE_DATA_NO_AHEAD

        internal fun previousDataNoForHistory(previousDataNo: Int, liveDataNo: Int): Int =
            if (isPersistedDataNoAheadOfLive(previousDataNo, liveDataNo)) -1 else previousDataNo

        /**
         * The watchdog retry budget after a history frame lands for the in-flight chunk.
         *
         * Refunded only when the frame carries the window FURTHER than every earlier frame for
         * the same chunk ([chunkBestDataNo]), not merely when it carries something. A chunk
         * whose tail cannot be decoded re-delivers the same records on every retry, and
         * refunding on that never let the bound be reached: the 2026-08-11 Syai trace shows
         * "retry 1/3" on [37,50) re-issued every 13 s for the rest of the connection. With the
         * budget spent the window goes to the hole ledger and the chain moves on.
         */
        internal fun historyRetriesAfterFrame(
            retries: Int,
            frameMaxDataNo: Int,
            chunkBestDataNo: Int,
        ): Int = if (frameMaxDataNo > chunkBestDataNo) 0 else retries

        /**
         * Retry budget after one history payload. A covered window starts the next chunk at 0
         * even when this frame's max is not a new high-water mark: the record that finished the
         * window may be a hole in the middle, and the tail was already counted.
         */
        internal fun historyRetryCountAfterAbsorb(
            outcome: HistoryChunkAbsorb,
            retries: Int,
            frameMaxDataNo: Int,
            chunkBestDataNo: Int,
        ): Int = when (outcome) {
            HistoryChunkAbsorb.STALE -> retries
            HistoryChunkAbsorb.INCOMPLETE -> historyRetriesAfterFrame(retries, frameMaxDataNo, chunkBestDataNo)
            HistoryChunkAbsorb.COMPLETED -> 0
        }

        /**
         * The covered chunk may reset the retry budget when it still owns the window, and also
         * when a failed watchdog write already retired that window to idle. A different window
         * now in flight keeps its own count.
         */
        internal fun coveredChunkResetsRetryCount(
            activeStart: Int,
            activeEndExclusive: Int,
            payloadStart: Int,
            payloadEndExclusive: Int,
        ): Boolean =
            (activeStart == payloadStart && activeEndExclusive == payloadEndExclusive) ||
                activeEndExclusive <= 0

        /**
         * What the history watchdog does with the in-flight window `[start, endExclusive)` after
         * [retries] re-issues: nothing when no window is in flight, skip a malformed one, re-issue
         * it while the budget lasts, then give it up to the hole ledger.
         */
        internal fun historyWatchdogAction(start: Int, endExclusive: Int, retries: Int): HistoryWatchdogAction =
            when {
                endExclusive <= 0 -> HistoryWatchdogAction.NONE
                start < 0 || endExclusive <= start -> HistoryWatchdogAction.SKIP
                retries < HISTORY_MAX_RETRIES -> HistoryWatchdogAction.RETRY
                else -> HistoryWatchdogAction.GIVE_UP
            }

        /**
         * Whether a history request keeps the chunk's progress marker ([historyRetriesAfterFrame]):
         * only a re-issue of the identical window does. Any other window starts with nothing
         * delivered.
         */
        internal fun keepsChunkBest(activeStart: Int, activeEndExclusive: Int, start: Int, endExclusive: Int): Boolean =
            start == activeStart && endExclusive == activeEndExclusive

        /**
         * Whether a history payload delivered its window — whether that window may leave the hole
         * ledger. Judged on what was STORED, not on what decoded: [undatedSkips] counts the records
         * emitReading skipped for want of a usable date (no anchor at all, or a date further ahead
         * than its own arrival after a clock step). Those are skipped silently and the sensor still
         * holds them, so one of them means the window has not arrived.
         *
         * Records the value filters REJECTED (warmup, continuity, the hard limits) are deliberately
         * NOT counted: they leave through rejectReading, they would be refused again on every
         * refetch, and counting them would re-ask for the dataNo 0..9 ramp for as long as the
         * connection lasts — this path does not call noteHoleFailure, so the attempt cap would
         * never retire it either — taking the records around them that did store with it.
         *
         * Known and NOT closed here: a payload decoded before materials.method arrives stores
         * nothing either, and counts as delivered — that skip sits in the emit loop above
         * emitReading and is not counted.
         */
        internal fun payloadDelivered(undatedSkips: Int): Boolean = undatedSkips == 0

        /**
         * True when every dataNo in `[start, endExclusive)` has been seen across the notifies
         * for this chunk. A later notify whose max reaches the end does not cover a lost middle,
         * and trimming the whole window then would retire records that never arrived.
         */
        internal fun historyWindowFullyCovered(
            start: Int,
            endExclusive: Int,
            dataNos: Set<Int>,
        ): Boolean {
            if (endExclusive <= start) return false
            for (dataNo in start until endExclusive) {
                if (dataNo !in dataNos) return false
            }
            return true
        }

        /**
         * Record [dataNos] against the window this payload was counted for.
         *
         * [activeStart] and [activeEndExclusive] are the window in flight now.
         * [payloadStart] and [payloadEndExclusive] are the window this payload read.
         * When they differ, [seen] belongs to the newer request: adding these dataNos or
         * clearing the set would drop its coverage or finish a window that is still open.
         */
        internal fun absorbHistoryChunkPayload(
            activeStart: Int,
            activeEndExclusive: Int,
            payloadStart: Int,
            payloadEndExclusive: Int,
            seen: MutableSet<Int>,
            dataNos: Iterable<Int>,
        ): HistoryChunkAbsorb {
            if (activeStart != payloadStart || activeEndExclusive != payloadEndExclusive) {
                return HistoryChunkAbsorb.STALE
            }
            for (dataNo in dataNos) {
                if (dataNo in payloadStart until payloadEndExclusive) seen.add(dataNo)
            }
            if (!historyWindowFullyCovered(payloadStart, payloadEndExclusive, seen)) {
                return HistoryChunkAbsorb.INCOMPLETE
            }
            seen.clear()
            return HistoryChunkAbsorb.COMPLETED
        }

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
        /**
         * The records a live sample says are missing, or null when nothing is.
         *
         * A live frame that lands on `liveDataNo` when the app holds `previousDataNo` is the
         * sensor telling us, without being asked, that everything between them is gone from
         * our side. That is a bounded, self-describing hole and the ledger exists to carry
         * exactly this kind — but before this the only route to the ledger ran through
         * requestRoomBackfillAfterLive, whose one-shot latches for the whole connection. On a
         * 2026-08-30 trace the latch closed at 02:10 and the link then stayed up 11.5 hours,
         * so a hole that opened at 12:10 was never re-requested at all.
         *
         * Bounded twice over: [ceiling] rejects a jump to a dataNo the sensor cannot have
         * reached, and [maxRecords] refuses a span too large to be a live-path hole — that is
         * the initial reconciliation's job, not this one's.
         */
        internal fun liveGapRange(
            previousDataNo: Int,
            liveDataNo: Int,
            ceiling: Int = Int.MAX_VALUE,
            maxRecords: Int = MAX_LIVE_GAP_RECORDS,
        ): MissingRange? {
            // A first-ever sample has nothing to be missing behind it, and a live dataNo past
            // the ceiling is not a position the sensor can be in.
            if (previousDataNo < 0 || liveDataNo <= 0 || liveDataNo > ceiling) return null
            val missing = liveDataNo - previousDataNo - 1
            if (missing <= 0 || missing > maxRecords) return null
            return MissingRange(previousDataNo + 1, liveDataNo)
        }

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

        /**
         * Which of records `0 until liveDataNo` the local store holds, from its stored timestamps
         * dated off [startMs]: each timestamp goes to the nearest record minute. The division
         * truncates toward zero, so a timestamp up to 90 s before [startMs] also lands on record 0;
         * one past the range marks nothing. The input of [missingRanges] for the Room diff.
         */
        internal fun presentFromTimestamps(timestamps: LongArray, startMs: Long, liveDataNo: Int): BooleanArray {
            val present = BooleanArray(liveDataNo)
            for (timestamp in timestamps) {
                val dataNo = ((timestamp - startMs + RECORD_INTERVAL_MS / 2L) / RECORD_INTERVAL_MS).toInt()
                if (dataNo in present.indices) present[dataNo] = true
            }
            return present
        }

        /**
         * Put `[start, endExclusive)` on the hole ledger [holes] (kept sorted by start). Returns
         * null when a hole already covers it (nothing changed), else the holes the
         * [MAX_HISTORY_HOLES] cap dropped, oldest first — their records are lost.
         */
        internal fun ledgerAdd(holes: MutableList<HistoryHole>, start: Int, endExclusive: Int): List<HistoryHole>? {
            if (holes.any { it.start <= start && it.endExclusive >= endExclusive }) return null // covered
            holes += HistoryHole(start, endExclusive, 0)
            holes.sortBy { it.start }
            val dropped = ArrayList<HistoryHole>()
            while (holes.size > MAX_HISTORY_HOLES) dropped += holes.removeAt(0)
            return dropped
        }

        /** Data for `[start, endExclusive)` arrived: shrink [holes] by it. Returns whether any hole changed. */
        internal fun ledgerTrim(holes: MutableList<HistoryHole>, start: Int, endExclusive: Int): Boolean {
            var changed = false
            val iterator = holes.listIterator()
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
            return changed
        }

        /**
         * A request for `[start, endExclusive)` failed to deliver: count it against every hole it
         * overlaps, or ledger it with one attempt. Returns the holes that reached
         * [HISTORY_HOLE_MAX_ATTEMPTS] and were retired.
         */
        internal fun ledgerFail(holes: MutableList<HistoryHole>, start: Int, endExclusive: Int): List<HistoryHole> {
            // Bump every overlapping hole, not just an exact match: failures arrive per issued
            // CHUNK, so a multi-chunk hole must absorb its chunks' failures or the attempt cap
            // never retires it (a permanently-empty overshoot range would then retry forever).
            var overlapped = false
            for (h in holes) {
                if (h.start < endExclusive && start < h.endExclusive) {
                    h.attempts++
                    overlapped = true
                }
            }
            if (!overlapped) {
                // Deliberately not capped at MAX_HISTORY_HOLES the way addHistoryHole is. That
                // cap drops the OLDEST window and loses its records; here every entry already
                // carries a failure and is retired by HISTORY_HOLE_MAX_ATTEMPTS, so the ledger
                // drains on its own. Applying the size cap would discard a window that still has
                // retries left in favour of one that is already on its way out.
                holes += HistoryHole(start, endExclusive, 1)
                holes.sortBy { it.start }
            }
            val retired = ArrayList<HistoryHole>()
            holes.removeAll { h -> (h.attempts >= HISTORY_HOLE_MAX_ATTEMPTS).also { if (it) retired += h } }
            return retired
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
         * The count [shouldDistrustCeiling] judges, after one payload that offered [offered]
         * records and kept [kept] under [ceiling]. A payload with nothing to judge, or one that
         * met no bound, leaves it alone; one that kept anything ends the run; a whole payload
         * rejected extends it.
         */
        internal fun ceilingFullDropsAfter(current: Int, offered: Int, kept: Int, ceiling: Int): Int = when {
            offered <= 0 || ceiling == Int.MAX_VALUE -> current
            kept > 0 -> 0
            else -> current + 1
        }

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

        /**
         * Signature verification is advisory on the normal known-address path because the
         * recovered verifier is not authoritative enough to abort an otherwise valid handshake.
         * Candidate discovery must preserve that same behaviour for the sensor's persisted
         * address, while still rejecting an unverified name-only neighbour.
         */
        internal fun isKnownActivationAddress(
            candidateAddress: String?,
            homeAddress: String?,
            recordAddress: String?,
        ): Boolean {
            val candidate = OttaiConstants.normalizeBleAddress(candidateAddress, allowPlain = false)
                ?: return false
            val known = OttaiConstants.normalizeBleAddress(homeAddress, allowPlain = false)
                ?: OttaiConstants.normalizeBleAddress(recordAddress, allowPlain = false)
                ?: return false
            return candidate.equals(known, ignoreCase = true)
        }

        internal fun shouldHoldFastParams(
            intervalUnits: Int,
            latency: Int,
            unstable: Boolean,
            nowMs: Long,
            lastReassertMs: Long,
            reassertsThisConnection: Int,
        ): Boolean =
            unstable &&
                reassertsThisConnection < MAX_PRIORITY_REASSERTS_PER_CONNECTION &&
                (intervalUnits >= SLOW_CONN_INTERVAL_UNITS || latency >= SLOW_CONN_LATENCY) &&
                nowMs - lastReassertMs >= PRIORITY_REASSERT_MIN_GAP_MS

        internal fun connectingStaleThresholdMs(stallStreak: Int): Long =
            SETUP_ACTIVITY_STALE_MS shl stallStreak.coerceIn(0, MAX_CONNECT_STALL_BACKOFF_SHIFTS)

        /**
         * How long the ordinary disconnect path waits before re-arming the connect.
         *
         * A supervision timeout says the peer stopped answering, not that it went away. In the
         * 2026-08-01 storm all 88 drops were status=8, and 15 of those outages ended within 10s
         * of close() — the sensor was there and catchable while the driver sat out its 3s
         * default. Worth ~38s across both sensors over the window even if the re-arm were instant
         * (0.7% of the downtime) and zero recovered readings — this takes half of that, no more:
         * the pre-teardown hold is only 129s/2520s and 120s/2799s of
         * the two downtime budgets, the other ~95% being spent after the connect is armed,
         * waiting for an advertisement to get through (connectGatt->connected p50 22s/33s, max
         * 374s/387s). Cheap, so worth taking; not a fix for the outages.
         *
         * 1500ms and not the 250ms recoverGattAndReconnect uses: that path fires from the
         * watchdog with no callback in flight, whereas this one has just run gatt.close() on the
         * BT callback thread with the disconnect event still being delivered, and a connectGatt()
         * issued before unregisterApp()/registerApp() has settled is the classic way to earn a
         * status=133 that costs a whole extra outage. On MIUI that cycle churned clientIf
         * 163,167,171,179,183,191...243 in 4h. Nothing measures where the floor actually is, so
         * take the half of the 3s that the evidence justifies and leave the rest — and if a 133
         * does arrive right after one of these re-arms, [fastReArmBounced] gives the whole idea up
         * for the rest of the outage.
         *
         * Note for anyone reading the CONNECTING backoff ladder: this also moves that ladder's
         * origin 1.5s earlier per status=8 outage, because connectDevice() stamps
         * lastBleActivityAtMs and arms the watchdog. The rungs (90/180/360s) are unchanged.
         *
         * Every other status deliberately keeps the 3s default for an isolated drop:
         *  - 19 (GATT_CONN_TERMINATE_PEER_USER) is a live peer closing the link itself, and a
         *    fast re-arm against that is how a connect/terminate loop gets built. 3s works —
         *    drop at 1785606352, reconnected 1785606359.
         *  - 22 (GATT_CONN_TERMINATE_LOCAL_HOST) is our own teardown, likewise.
         *  - 133 never occurred on this path: all five in the logs were ATT reads
         *    ("read err 00002aa7 ... phase=STREAMING").
         *  - 147 was not in the 2026-08-01 dataset at all — a run of them, seen against a
         *    handoff-triggered reconnect where the peripheral kept refusing every attempt for
         *    over two minutes, is what [consecutiveFailures] backs off against below.
         *
         * [consecutiveFailures] is failed connects since the last STATE_CONNECTED, reset there and
         * incremented on this path only — it does not touch the evidence-based 3s/1.5s choice
         * above for the first [FAILURE_STREAK_BACKOFF_THRESHOLD] of them, and never overrides the
         * supervision-timeout fast re-arm, which stays a deliberate exception regardless of streak
         * length.
         */
        internal fun reconnectDelayAfterDisconnectMs(
            status: Int,
            fastReArmAllowed: Boolean,
            consecutiveFailures: Int = 0,
        ): Long {
            if (status == GATT_CONN_TIMEOUT && fastReArmAllowed) return SUPERVISION_TIMEOUT_RECONNECT_DELAY_MS
            if (consecutiveFailures < FAILURE_STREAK_BACKOFF_THRESHOLD) return RECONNECT_DELAY_MS
            val doublings = consecutiveFailures - FAILURE_STREAK_BACKOFF_THRESHOLD + 1
            return (RECONNECT_DELAY_MS shl doublings.coerceAtMost(30)).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        }

        /**
         * Whether this disconnect looks like the shortened re-arm above bouncing off the stack's
         * own client registration rather than off the sensor.
         *
         * A 133 landing within a couple of seconds of a re-armed connect is the documented shape
         * of connecting inside the post-close() clientIf window; it has never been seen on this
         * driver's disconnect path, but it has also never been asked for a connect this soon after
         * close(). One is enough — the shortened delay is worth 0.7% of the downtime, so it is not
         * worth a single extra bounce, let alone a loop of them.
         */
        internal fun fastReArmBounced(status: Int, nowMs: Long, fastReArmAtMs: Long): Boolean =
            status == GATT_ERROR_STATUS &&
                fastReArmAtMs > 0L &&
                (nowMs - fastReArmAtMs) in 0L..FAST_REARM_BOUNCE_WINDOW_MS

        internal fun acceptedMaxActiveToCommit(
            commandStatus: Int,
            activationCommandAcknowledged: Boolean,
            pendingDurationMs: Long,
        ): Long =
            pendingDurationMs.takeIf {
                commandStatus == 3 && activationCommandAcknowledged && it > 0L
            } ?: 0L

        internal fun interpretGattWrite(
            characteristicPresent: Boolean,
            writeAccepted: Boolean?,
        ): GattWriteIssue = when {
            !characteristicPresent -> GattWriteIssue.Missing
            writeAccepted == true -> GattWriteIssue.Issued
            else -> GattWriteIssue.Rejected
        }

        /** Missing maxActive char: firmware omit, skip to destruction. Queue reject must not. */
        internal fun maxActiveAdvancesOnQueueResult(issue: GattWriteIssue): Boolean =
            issue is GattWriteIssue.Missing

        internal fun maxActiveFailsClosedOnQueueResult(issue: GattWriteIssue): Boolean =
            issue is GattWriteIssue.Rejected

        internal fun shouldStampWarmupPrefOnActivateIssue(
            issue: GattWriteIssue,
            commandNeedsActivation: Boolean,
        ): Boolean = issue is GattWriteIssue.Issued && commandNeedsActivation

        /**
         * Official start for the card, countdown and age: cloud, then a reliable stream
         * anchor, then this session's GATT-ACK of 0x03. Never cgm-info provisional,
         * never an unreliable streamStart, never the issue-time warmup PREF.
         */
        internal fun officialStartMs(
            cloudActiveTimeMs: Long,
            streamStartMs: Long,
            streamStartReliable: Boolean,
            activationCommandSentAtMs: Long,
        ): Long =
            cloudActiveTimeMs.takeIf { it > 0L }
                ?: streamStartMs.takeIf { it > 0L && streamStartReliable }
                ?: activationCommandSentAtMs.takeIf { it > 0L }
                ?: 0L

        /**
         * The start [OttaiParser.toReading] builds a record's monitor time from: [officialStartMs],
         * without this app's activation-command instant when the parser synthesized the record's
         * runtime (9-byte layout, chosen exactly as [OttaiParser.frameRecords] chooses it:
         * dataNo * 60, the START of the record's minute).
         *
         * On the 2026-09-22 activation the command was acknowledged 2 s into a minute and record 0
         * arrived 62 s later, at the end of its minute. Command instant plus the start of the minute
         * still passed the monitor-live arrival check, so the anchor was floored to the start of the
         * command's minute, before the command was written, and committed one-way; every reading was
         * then stamped 64-66 s before it arrived. Without the instant the first frame goes to the
         * arrival path, as it already does after a restart (the instant is not restored) and as it
         * did once that sensor was re-added: anchored one minute after the floored start, its
         * readings 4-6 s early.
         *
         * Nothing else is withheld. A cloud or committed start and a checked anchor are grid origins
         * already (stamp = start + dataNo * interval), and moving them would re-date a running
         * sensor; a real runtime (8-byte layout) is the sensor's own count and keeps the instant.
         *
         * [pendingStartMs] takes the withheld instant's place. It is the start claim a live frame
         * checked against its own arrival, the anchor as it stood before a reconnect made the
         * link check it again. Without it, the post-auth read (its phase within the record's
         * minute is random) went to the arrival path, and whenever record 0's second plus the
         * read's delay crossed a minute it claimed one minute late. That claim still corroborated
         * the pending one and was committed for the sensor's life. Built on the claim, the monitor
         * time echoes it and must pass the same arrival check as the next frame without a
         * reconnect.
         */
        internal fun monitorBaseStartMs(
            payload: ByteArray,
            deviceVersion: String,
            learnedRecordSize: Int?,
            cloudActiveTimeMs: Long,
            streamStartMs: Long,
            streamStartReliable: Boolean,
            activationCommandSentAtMs: Long,
            pendingStartMs: Long,
        ): Long {
            val runtimeSynthesized =
                OttaiParser.chooseRecordSize(payload, deviceVersion, learnedRecordSize) ==
                    OttaiParser.BLE_RECORD_SIZE_E12
            return officialStartMs(
                cloudActiveTimeMs = cloudActiveTimeMs,
                streamStartMs = streamStartMs,
                streamStartReliable = streamStartReliable,
                activationCommandSentAtMs = if (runtimeSynthesized) pendingStartMs else activationCommandSentAtMs,
            )
        }

        // ---- activation payloads and the maxActive readback ----
        // The bytes writeRtc, writeMaxActiveTime and writeDestructionTime hand to writeChar, built
        // here so a test can hold them. Their tests are regression pins, not a byte comparison with
        // an official-app capture, which a change to any of these commands still needs.

        private fun longToBytesLE(v: Long): ByteArray = ByteArray(8) { ((v ushr (it * 8)) and 0xFF).toByte() }

        /** RTC (CHAR_CURRENT_TIME): the wall clock in whole seconds, LE4. */
        internal fun rtcPayload(nowMs: Long): ByteArray = OttaiBleAuth.intToBytesLE((nowMs / 1000L).toInt())

        /**
         * maxActive (CHAR_MAX_ACTIVE_TIME): AES-ECB(zeroPad16(LE8(seconds))) under the session key,
         * or null when that key cannot encrypt.
         */
        internal fun maxActivePayload(expireMs: Long, sessionKeyHex: String): ByteArray? =
            OttaiCrypto.encryptPayload(longToBytesLE(expireMs / 1000L), sessionKeyHex)

        /**
         * Destruction (CHAR_DESTRUCTIVE), plaintext: LE8(retain seconds) || 0x04, where a retain of
         * zero or less falls back to [OttaiConstants.DEFAULT_RETAIN_TIME_MS].
         */
        internal fun destructionPayload(retainTimeMs: Long): ByteArray {
            val retainMs = retainTimeMs.takeIf { it > 0L } ?: OttaiConstants.DEFAULT_RETAIN_TIME_MS
            val secs = retainMs / 1000L
            return longToBytesLE(secs) + byteArrayOf(0x04)
        }

        // b8fd9848 holds the activated maxActive duration. The write format is
        // AES-ECB(zeroPad16(secondsLE)); a read returns the same cipher, so decrypt and read
        // the 8-byte LE seconds. Fall back to a plaintext LE read if a firmware returns it raw.
        internal fun decodeMaxActiveSeconds(value: ByteArray, sessionKeyHex: String): Long? {
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

        /** A maxActive readback worth adopting: a plausible activation window, 10 to 45 days. */
        internal fun plausibleMaxActiveSeconds(secs: Long): Boolean =
            secs >= 10L * 86_400L && secs <= 45L * 86_400L
    }

    internal sealed class GattWriteIssue {
        data object Missing : GattWriteIssue()
        data object Rejected : GattWriteIssue()
        data object Issued : GattWriteIssue()
    }

    /** [confirmationStep]'s verdict on one activation-start claim. */
    internal sealed class ConfirmStep {
        /** Nothing to do: no claim, a start already confirmed, or the pending record read again. */
        data object Ignore : ConfirmStep()
        /** A start in the future or older than the longest lifetime: dropped, and logged. */
        data object Implausible : ConfirmStep()
        /** The first claim: held until a claim from another record agrees with it. */
        data class Pend(val startMs: Long, val dataNo: Int) : ConfirmStep()
        /** Corroborated: commit this start, for good. */
        data class Commit(val startMs: Long) : ConfirmStep()
        /** Disagrees with the pending claim: replaces it. */
        data class Rearm(val startMs: Long, val dataNo: Int) : ConfirmStep()
    }

    internal enum class HistoryWatchdogAction { NONE, SKIP, RETRY, GIVE_UP }

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
    // Any native call on the shell id revives a finished shell (ensureDirectStreamShell ->
    // reactivateFromData). After terminal teardown nothing may write: a reading in flight when the
    // user removed the sensor would bring back the shell OttaiRegistry.removeSensor just finished,
    // and the Libre2 ghost with it. So the released check and the write are one step, under the
    // lock the finish takes (OttaiRegistry.unlessReleased). A write can also be the call that
    // creates the shell, so it is sized first (ensureNativeShellCapacity).
    private val nativeGlucoseMirror = OttaiNativeGlucoseMirror(
        writeNative = { timestampSec, glucose, temperatureC, sensorId ->
            OttaiRegistry.unlessReleased({ released }, false) {
                ensureNativeShellCapacity(sensorId)
                Natives.addGlucoseStreamWithTemp(timestampSec, glucose, temperatureC, sensorId)
            }
        },
        wakeNightscout = { source, timestampMs ->
            NightscoutUploadWake.afterLiveNativeWrite(source, timestampMs)
        },
        writeNativeBatch = { timestampsSec, glucose, temperaturesC, sensorId ->
            OttaiRegistry.unlessReleased({ released }, 0) {
                ensureNativeShellCapacity(sensorId)
                Natives.addGlucoseStreamBatchWithTemp(timestampsSec, glucose, temperaturesC, sensorId)
            }
        },
    )
    // Recent abnormal-disconnect timestamps (status!=0), pruned to UNSTABLE_LINK_WINDOW_MS;
    // feeds the fast-params hold and is only touched under its own lock (binder threads).
    private val abnormalDropAtMs = ArrayDeque<Long>()
    @Volatile private var lastPriorityReassertAtMs = 0L
    // Read-modify-written only from onConnectionParamsUpdated and reset only from
    // STATE_CONNECTED, both of which the stack delivers on the one binder thread that owns this
    // GATT — unlike abnormalDropAtMs above, which several threads reach.
    @Volatile private var priorityReassertCount = 0
    @Volatile private var connectStallStreak = 0
    // Failed connects since the last STATE_CONNECTED; reset there, incremented only on the
    // ordinary disconnect path. Feeds reconnectDelayAfterDisconnectMs's backoff.
    @Volatile private var consecutiveConnectFailures = 0
    @Volatile private var liveReadRetryCount = 0
    @Volatile private var lastUserReconnectAtMs = 0L
    // When the connect a shortened post-supervision-timeout re-arm scheduled is due to fire (not
    // when it was scheduled — the bounce this watches for happens at the connect), and whether
    // the shortened delay has been withdrawn for this outage. See reconnectDelayAfterDisconnectMs.
    @Volatile private var fastReArmAtMs = 0L
    @Volatile private var fastReArmWithdrawn = false

    // ---- outage instrumentation (measurement only; never drives the state machine) ----
    private val advertisementProbe = OttaiAdvertisementProbe(
        context = Applic.getContext(),
        handler = handler,
        onFinished = ::finishOutageAdvertisementProbe,
    )
    @Volatile private var outageStartedAtMs = 0L
    @Volatile private var probedThisOutage = false
    @Volatile private var lastRssiReadAtMs = 0L

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
    @Volatile private var lastAuthDevHex: String = ""
    // Fire /cgmAuth/verify at most once at a time; the V2 reconnect loop would otherwise pile up
    // several concurrent verify requests and time them all out.
    @Volatile private var verifyInFlight: Boolean = false
    private val verifyLock = Any()
    private val verifyWaiters = ArrayList<(OttaiCloudClient.V3AuthMaterial?) -> Unit>()
    @Volatile private var lastVerifyMaterial: OttaiCloudClient.V3AuthMaterial? = null
    @Volatile private var lastVerifyAuthFlagHex: String? = null
    // Server write-back material from a successful /cgmAuth/verify (hex decoded). When present,
    // the auth writes carry these instead of the locally computed V2 appParam/sign.
    @Volatile private var serverAuthHostBytes: ByteArray? = null
    @Volatile private var serverAuthFlagBytes: ByteArray? = null
    // One bindV3 attempt per connection episode, after the server material was written back.
    private var v3BindAttemptedThisEpisode = false
    private val v3CredentialBootstrapLock = Any()
    @Volatile private var v3CredentialBootstrapCompletion:
        ((OttaiRegistry.DeviceMaterials?, OttaiCloudClient.CloudFailure?) -> Unit)? = null
    private val v3CredentialBootstrapTimeoutRunnable = Runnable {
        finishV3CredentialBootstrap(
            null,
            OttaiCloudClient.CloudFailure("Timed out while fetching sensor credentials"),
        )
    }
    /** True while a CN cloud session exists — the only profile that serves /cgmAuth/verify. */
    private fun cnActiveAuthSessionAvailable(): Boolean {
        val ctx = Applic.app ?: return false
        return OttaiRegistry.loadSessionProfile(ctx) == OttaiRegistry.SessionProfile.CN_PHONE &&
            OttaiRegistry.loadAccessToken(ctx).isNotBlank()
    }

    private fun v3CredentialBootstrapActive(): Boolean {
        val ctx = Applic.app ?: return false
        val id = SerialNumber ?: return false
        return ottaiAuthEntryMode(
            hasAuthKeys = authKeys != null,
            bootstrapPending = OttaiRegistry.isV3CredentialBootstrapPending(ctx, id),
            cnSessionAvailable = cnActiveAuthSessionAvailable(),
            validatedDeviceVersion = OttaiRegistry.loadLastValidatedDeviceVersion(ctx, id),
        ) == OttaiAuthEntryMode.V3_CREDENTIAL_BOOTSTRAP
    }

    /**
     * Own this manager as a wizard-local credential transaction. It is deliberately absent from
     * [SensorBluetooth.gattcallbacks], so a fresh V3 transmitter cannot appear in the Sensors list
     * until bindV3 has returned and persisted usable keyA material.
     */
    internal fun beginV3CredentialBootstrap(
        onComplete: (OttaiRegistry.DeviceMaterials?, OttaiCloudClient.CloudFailure?) -> Unit,
    ): Boolean {
        synchronized(v3CredentialBootstrapLock) {
            if (v3CredentialBootstrapCompletion != null) return false
            v3CredentialBootstrapCompletion = onComplete
        }
        handler.postDelayed(v3CredentialBootstrapTimeoutRunnable, V3_CREDENTIAL_BOOTSTRAP_TIMEOUT_MS)
        if (connectDevice(0L)) return true
        finishV3CredentialBootstrap(
            null,
            OttaiCloudClient.CloudFailure("Could not start the sensor credential connection"),
        )
        return false
    }

    internal fun cancelV3CredentialBootstrap() {
        synchronized(v3CredentialBootstrapLock) {
            v3CredentialBootstrapCompletion = null
        }
        handler.removeCallbacks(v3CredentialBootstrapTimeoutRunnable)
        SerialNumber?.let { id ->
            Applic.app?.let { OttaiRegistry.setV3CredentialBootstrapPending(it, id, false) }
        }
        stop = true
        clearGattTransport("V3 credential bootstrap cancelled", markSignalLoss = false)
        close()
    }

    private fun finishV3CredentialBootstrap(
        fetched: OttaiRegistry.DeviceMaterials?,
        failure: OttaiCloudClient.CloudFailure?,
    ) {
        val completion = synchronized(v3CredentialBootstrapLock) {
            val current = v3CredentialBootstrapCompletion ?: return
            v3CredentialBootstrapCompletion = null
            current
        }
        handler.removeCallbacks(v3CredentialBootstrapTimeoutRunnable)
        SerialNumber?.let { id ->
            Applic.app?.let { OttaiRegistry.setV3CredentialBootstrapPending(it, id, false) }
        }
        stop = true
        clearGattTransport("V3 credential bootstrap complete", markSignalLoss = false)
        close()
        Handler(Looper.getMainLooper()).post { completion(fetched, failure) }
    }
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
    // When the current candidate scan was armed. For the first few seconds only this sensor's own
    // address is admitted; see OttaiConstants.isActivationExactOnlyWindowOpen.
    @Volatile private var activationDiscoveryStartedAtMs = 0L
    // The arming whose first scan hit is already in the trace; one line per arming says how soon
    // the sensor was heard. Heard, not acted on: a hit that lands while the shared scan restarts
    // for full duty reaches onScanResult but not checkdevice (processScanResult drops it on
    // !mScanning), and the sensor's next advertisement is the one that connects. A hit that is
    // acted on stops the scan once every sensor is found, so no rate can be measured here.
    @Volatile private var activationScanHitLoggedArmedAtMs = 0L
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
    @Volatile private var activationInFlight = false
    @Volatile private var commandByteSeenThisAttempt = false
    @Volatile private var activateCommandIssued = false
    @Volatile private var notifyEnableIndex = 0
    @Volatile private var discoveryStarted = false
    @Volatile private var activationCommandSentAtMs = 0L
    // The instant under PREF_ACTIVATION_COMMAND_AT: as an earlier process persisted it, or as
    // writeActivateCmd stamps it before the acknowledgement, which can be lost. Read only by
    // warmupAnchorMs: the field above also steers the activation flow (the disconnect handler, the
    // requestActivation guard, "Ready to activate"), so it waits for that acknowledgement and stays
    // per-process.
    @Volatile private var restoredActivationCommandAtMs = 0L
    @Volatile private var pendingAcceptedMaxActiveMs = 0L
    @Volatile private var activationCommandAcknowledged = false
    // maxActive duration (ms) the firmware accepted at activation or read back: the sensor's
    // lifetime, which the firmware enforces exactly. Drives the reported end
    // (OttaiConstants.expectedLifetimeMs) and the native sensor record
    // (applyActivatedWearToNative). 0 = unknown.
    @Volatile private var activatedMaxActiveMs = 0L
    @Volatile private var provisionalActiveTimeMs = 0L
    @Volatile private var commandStatus = -1
    @Volatile private var livePollIntervalMs = 60_000L
    @Volatile private var lastHistoryRequestAtMs = 0L
    @Volatile private var roomBackfillChecked = false
    // The sensor's BLE record layout (8 or 9), 0 until a payload big enough to prove one has
    // been seen. A minute live notify is 24 bytes — one 9-byte record plus padding, which is
    // also two 8-byte records — so it cannot tell the layouts apart and must not be allowed to
    // choose. See OttaiParser.decisiveRecordSize.
    @Volatile private var learnedRecordSize = 0
    // One log line per run of payloads whose own inference disagrees with the held layout.
    @Volatile private var recordSizeDisagreementLogged = false
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
    private val historyChunkSeenDataNos = HashSet<Int>()
    // The BLE binder thread (history notify → continueHistoryAfterPayload) and the driver
    // handler (watchdog, chunk issue, teardown) both update this set and the active window.
    // A plain HashSet loses entries or throws when those overlap.
    private val historyChunkLock = Any()
    @Volatile private var historyRetryCount = 0
    // Furthest dataNo delivered for the window currently in flight; -1 for a window not yet
    // heard from. Guards the retry budget against a chunk that keeps re-delivering its head
    // (see historyRetriesAfterFrame). Reset in issueHistoryRequest when the window changes.
    @Volatile private var historyChunkBestDataNo = -1
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
    internal data class HistoryHole(var start: Int, var endExclusive: Int, var attempts: Int)

    internal enum class HistoryChunkAbsorb {
        /** This window is still missing a dataNo. The stall timer stays armed. */
        INCOMPLETE,
        /** Every dataNo in this window arrived. The caller may retire it. */
        COMPLETED,
        /** A newer request owns the in-flight window. Do not touch its seen-set. */
        STALE,
    }

    /** A `[start, endExclusive)` window of records the local store does not have. */
    internal data class MissingRange(val start: Int, val endExclusive: Int)

    /**
     * What one [requestRoomBackfillAfterLive] call did. [issued] is all the two backstop callers
     * need; [diffOwnsRecovery] exists for the live caller.
     *
     * The live path answers a negative backfill result by calling requestHistoryAfterLive, and
     * that function's behaviour for an unusable previous dataNo is to ask for [0, live) — the
     * exact full pull the diff exists to eliminate, reached by a second route. It fires not only
     * when the diff cannot run but also when it runs fine and merely fails to get its first GATT
     * write out, which under RF trouble is routine. [diffOwnsRecovery] tells the caller the diff
     * has taken responsibility so that fallback stays shut.
     *
     * Suppressing it there is safe: the diff ledgers every window before issuing anything, the
     * ledger's retry driver owns recovery. If the diff itself cannot run yet, the separate
     * [historyDiffRetryPending] flag forces a later live sample back through the diff instead of
     * taking the cheap incremental branch and incorrectly latching history complete.
     *
     * This is returned rather than published through a field on purpose. As a field it was
     * written at the top of the call and read by the caller *after* the call returned, so the
     * driver's HandlerThread and the BLE binder thread — both of which reach this path — could
     * interleave a second call into that window and answer the first caller with the second
     * call's verdict.
     */
    private data class BackfillOutcome(val issued: Boolean, val diffOwnsRecovery: Boolean) {
        companion object {
            val NOT_ISSUED = BackfillOutcome(issued = false, diffOwnsRecovery = false)
        }
    }

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
    // Adjacency anchor for the continuity gate. Unlike lastAccepted* this also advances on a
    // sample the gate itself rejected. Anchoring adjacency on the last ACCEPTED sample meant two
    // consecutive rejections pushed the next sample outside the 2-record / 135 s window, so the
    // adjacency test went false, the excursion test was skipped entirely, and the third sample
    // went out unchecked — then became the baseline the rest of the ramp inherited. Observed on the
    // 2026-07-29 activation: dataNo 1 and 2 rejected at 2.40 mmol, dataNo 3 published at
    // 2.80 mmol (50 mg/dL) in the middle of a warmup ramp that ran on to 6.10 mmol.
    @Volatile private var lastEvaluatedDataNo = -1
    @Volatile private var lastEvaluatedSampleMs = 0L
    // Safety valve for the anchor above: the comparison baseline only moves on an ACCEPTED
    // sample, so a genuine step change (or a real electrode restart) would otherwise be refused
    // for the rest of the session. After this many consecutive continuity rejections the next
    // sample is admitted and re-baselines the gate.
    @Volatile private var consecutiveContinuityRejects = 0
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
    // Whether streamStartTimeMs was checked against a live frame's arrival on this link. Until the
    // start is confirmed only such an anchor dates history or the Room diff
    // (trustedStreamStartMs), and seedStreamTimeAnchor does not let a hint without that check
    // replace it.
    @Volatile private var streamStartReliable = false
    // The pending start claim from a live frame and the record it came from, held until a claim
    // from another record corroborates it: to commit the start (offerConfirmedActiveTime), or,
    // once it is confirmed, to move the stream anchor (datesLiveByArrival). Not cleared per link
    // on purpose: frames from two short links can still pair up. For a pair to move a confirmed
    // anchor, the later frame has to sit within CONFIRMED_CLAIM_MAX_GAP_RECORDS above the claiming
    // record (see there for what that window costs); the commit (offerConfirmedActiveTime) has no
    // such bound.
    // It is also what a synthesized runtime's monitor time is built on while a new link has not
    // checked the anchor yet (monitorBaseStartMs): the claim is that anchor as the last link
    // checked it, and the post-auth read dated by its arrival instead came out a minute late
    // whenever its phase crossed a minute.
    @Volatile private var pendingConfirmedStartMs = 0L
    @Volatile private var pendingConfirmedDataNo = -1
    // Set when a NEW record of a confirmed sensor could be dated by neither the stream anchor, nor
    // its monitor time, nor its arrival: this link's live frames and the confirmed start disagree,
    // so anything dated from that start — the initial backfill, the Room diff, a ledgered hole —
    // would be written with the disagreement baked in, and a stored row keeps the date it got.
    // Cleared by a positive fact: a live frame whose resolved date agrees with its own arrival
    // (emitReading). Teardown and a new link leave it alone; neither settles anything. Held in
    // memory only, so a restart starts undisputed and the first live frame checks the start again.
    @Volatile private var anchorDisputed = false
    // Records of the payload being decoded that emitReading skipped for want of a usable date.
    // Zeroed at the top of every emit loop and read by continueHistoryAfterPayload in the same
    // call — onCharacteristicChanged runs both with no handler hop — so it always describes the
    // payload in hand. Rejections are not counted here: see payloadDelivered.
    @Volatile private var undatedSkips = 0
    @Volatile private var lastBleActivityAtMs = 0L
    // When the sensor last pushed a live frame on its own. The poll below is a safety net for
    // notifications stopping, not a second data path, so it stands down while they are flowing.
    @Volatile private var lastLiveNotifyAtMs = 0L
    // When a live frame last decoded, from EITHER source. The post-history one-shot consults this,
    // and lastLiveNotifyAtMs cannot serve it: that one is written only from onCharacteristicChanged,
    // and in the 2026-08-01 logs the driver was on the polled read path — at 1785605688 the last
    // notify was 389 s old, so a notify-based guard would have been inert on exactly the path it is
    // meant to close.
    @Volatile private var lastLiveFrameAtMs = 0L
    private val recentlyRejectedSamples = object : LinkedHashMap<Int, RejectedSample>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, RejectedSample>?): Boolean = size > 64
    }

    override var viewMode: Int = 0

    private val livePollRunnable = Runnable {
        if (stop || phase != Phase.STREAMING || sessionKeyHex.isBlank() || commandStatus >= 4) return@Runnable
        val gatt = mBluetoothGatt ?: return@Runnable
        // Stand down while notifications are arriving on schedule. The poll timer is restarted by
        // every accepted live sample, so it comes due at the same instant as the next notify and
        // then re-reads what has just been delivered: over the 8 h after the 2026-07-29
        // activation, 216 of 219 polled reads returned records a notify had already brought in
        // milliseconds earlier — roughly 650 redundant encrypted ATT reads a day.
        val sinceNotifyMs = System.currentTimeMillis() - lastLiveNotifyAtMs
        if (lastLiveNotifyAtMs > 0L && sinceNotifyMs < livePollIntervalMs) {
            scheduleLivePoll()
            return@Runnable
        }
        readLiveGlucose(gatt, "poll")
        scheduleLivePoll()
    }

    private val postHistoryLiveRunnable = Runnable {
        if (stop || phase != Phase.STREAMING || sessionKeyHex.isBlank() || commandStatus >= 4) return@Runnable
        val gatt = mBluetoothGatt ?: return@Runnable
        readLiveGlucose(gatt, "post-history")
        scheduleLivePoll()
    }

    // Whether the sensor was out of range or the band was busy is not decidable from anything the
    // app records today: there is no RSSI anywhere in the 2026-08-01 trace. Reads are only issued
    // while streaming, and a read that cannot start is not retried — this is instrumentation, and
    // a missing sample costs nothing.
    private val rssiPollRunnable = Runnable {
        if (stop) return@Runnable
        val gatt = mBluetoothGatt
        if (gatt == null || !shouldPollRssi(phase == Phase.STREAMING, System.currentTimeMillis(), lastRssiReadAtMs)) {
            scheduleRssiPoll()
            return@Runnable
        }
        val started = runCatching { gatt.readRemoteRssi() }.getOrDefault(false)
        // Mark the attempt, not the answer: a read that never comes back must still not be
        // retried before the next slot, or a jammed link turns this into a busy loop.
        lastRssiReadAtMs = System.currentTimeMillis()
        if (!started) logi(TAG) { "rssi read could not start" }
        scheduleRssiPoll()
    }

    @Volatile private var activationConfirmAttempt = 0

    /**
     * Read the command byte back after the activation writes until it reports 3, or the attempts
     * run out. Self-rescheduling rather than a fixed delay, because a read that could not start
     * produces no callback to hang the next step off.
     */
    private val activationConfirmRunnable = object : Runnable {
        override fun run() {
            if (stop || commandStatus == 3) return
            val gatt = mBluetoothGatt ?: return
            if (activationConfirmAttempt >= POST_ACTIVATION_CONFIRM_MAX_ATTEMPTS) {
                Log.w(TAG, "activation confirmation read never landed after " +
                    "$activationConfirmAttempt attempts; awaiting the next poll or reconnect")
                return
            }
            activationConfirmAttempt++
            runCatching { readChar(gatt, OttaiConstants.SERVICE_CGM, OttaiConstants.CHAR_COMMAND) }
            handler.postDelayed(this, POST_ACTIVATION_CONFIRM_RETRY_MS)
        }
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
        val issued = requestRoomBackfillAfterLive(endExclusive, -1).issued
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

    private val startActivationAfterStatusRunnable = Runnable {
        // requestForceActivation only bypasses the already-started (cloud/stream) guard.
        // Issued leftover 0–2 is a separate gate and ignores force; only Advanced may rewrite.
        if (stop || mBluetoothGatt == null || phase != Phase.STREAMING || sessionKeyHex.isBlank()) {
            activationInFlight = false
            return@Runnable
        }
        val started = runCatching { requestForceActivation() }.getOrDefault(false)
        val liveGatt = mBluetoothGatt != null && phase == Phase.STREAMING && sessionKeyHex.isNotBlank()
        val mayEnter = OttaiConstants.mayEnterActivationWrites(
            commandStatus,
            activateCommandIssued,
            advancedActivateRequested(),
        )
        if (OttaiConstants.staleActivationStartShouldFailClosed(started, liveGatt, mayEnter)) {
            activationInFlight = false
            failActivation("could not start after sensor reported status=$commandStatus")
        } else if (!started) {
            activationInFlight = false
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
        repairSecondsUnitNativeStart(id)
        provisionalActiveTimeMs = if (materials.activeTimeMs > 0L) {
            OttaiRegistry.saveProvisionalActiveTime(context, id, 0L)
            0L
        } else {
            OttaiRegistry.loadProvisionalActiveTime(context, id)
        }
        authKeys = materials.authKeys
        activatedMaxActiveMs = OttaiRegistry.loadAcceptedMaxActive(context, id)
        restoredActivationCommandAtMs = OttaiRegistry.loadActivationCommandAt(context, id)
        activateCommandIssued = OttaiRegistry.loadActivateCommandIssued(context, id)
        lastDataNo = OttaiRegistry.loadLastDataNo(context, id)
        learnedRecordSize = OttaiRegistry.loadRecordSize(context, id)
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
            // Seed the adjacency anchor from the same record. Leaving it at -1 would disable the
            // gate for the first sample after every restart, which is exactly when the restored
            // baseline is there to be used.
            lastEvaluatedDataNo = it.dataNo
            lastEvaluatedSampleMs = it.sampleMs
        }
        // Auth V2 signs the cloud id bytes, not necessarily the Android BLE address.
        macBytes = runCatching { OttaiCrypto.hexToBytes(OttaiConstants.canonicalSensorId(id)) }.getOrDefault(ByteArray(0))
        ensureNativePresenceShell("restore")
    }

    /**
     * Builds between 39745902 and this fix passed a V3 epoch-seconds value through the ms field,
     * creating a native shell near January 1970. That start makes every real sample fall outside
     * the stream mapping. Rebase only that impossible signature; no plausible sensor can predate
     * 2000, and rebase deliberately clears the unusable zero-era stream window.
     */
    private fun repairSecondsUnitNativeStart(id: String) {
        // Also runs on a live, registered manager (OttaiRegistry.connectSensor -> restoreFromPersistence),
        // and its shell calls revive a finished shell like any mirror write.
        OttaiRegistry.unlessReleased({ released }, Unit) {
            val correctedStartSec = materials.activeTimeMs / 1_000L
            if (correctedStartSec < 946_684_800L) return
            runCatching {
                // restoreFromPersistence loads the accepted lifetime only after this, so the size rests on
                // the cloud rating and the floor; on a restart the generic getSensorData has normally
                // mapped the shell already, so reordering the restore would change nothing.
                val minimumRecords = OttaiConstants.nativeShellRecords(activatedMaxActiveMs, materials.activeExpireTimeMs)
                val sensorPtr = Natives.ensureSensorShellWithCapacity(id, correctedStartSec, minimumRecords)
                    .takeIf { it != 0L }
                    ?: Natives.ensureSensorShell(id, correctedStartSec)
                if (sensorPtr == 0L) return@runCatching
                Natives.setSensorManagedFamily(id, ManagedSensorUiFamily.OTTAI.nativeCode)
                val nativeStartSec = Natives.getSensorStartmsecFromSensorptr(sensorPtr) / 1_000L
                if (nativeStartSec in 1L until 946_684_800L) {
                    Log.w(TAG, "repairing seconds-unit native start for $id")
                    Natives.rebaseDirectStreamWindow(id, correctedStartSec)
                }
            }.onFailure { Log.stack(TAG, "repair seconds-unit native start", it) }
        }
    }

    private val reconnectRunnable = Runnable {
        if (stop) return@Runnable
        connectDevice(0)
    }
    private fun requiresNfcActivationWake(): Boolean =
        OttaiConstants.requiresNfcActivationWake(OttaiRegistry.loadApiBase(Applic.app))

    private fun armNfcActivationWake(reason: String) {
        if (!requiresNfcActivationWake()) return
        SerialNumber?.takeIf { it.isNotBlank() }?.let { sensorId ->
            Log.i(TAG, "$reason; arming NFC wake")
            OttaiNfc.armForActivationRetry(sensorId)
            Applic.app?.let { OttaiNfcWakeReminder.show(it, sensorId) }
        }
    }

    private val pendingActivationNfcPromptRunnable = Runnable {
        if (SerialNumber.isNullOrBlank()) return@Runnable
        if (stop ||
            !awaitingFreshActivationAdvertisement ||
            activateRequestedFor?.let { matchesManagedSensorId(it) } != true ||
            !requiresNfcActivationWake()
        ) {
            return@Runnable
        }
        armNfcActivationWake("pending setup activation still has no advertisement")
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
            phase == Phase.STREAMING -> streamingStaleThresholdMs(livePollIntervalMs)
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
        handler.removeCallbacks(rssiPollRunnable)
        handler.removeCallbacks(pendingHistoryChunkRunnable)
        handler.removeCallbacks(endedHistoryBackfillRunnable)
        handler.removeCallbacks(initialHistoryRunnable)
        handler.removeCallbacks(connectionWatchdogRunnable)
        handler.removeCallbacks(pendingActivationNfcPromptRunnable)
        handler.removeCallbacks(serviceDiscoveryRunnable)
        handler.removeCallbacks(serviceDiscoveryTimeoutRunnable)
        handler.removeCallbacks(startActivationAfterStatusRunnable)
        advertisementProbe.stop()
        pendingServiceDiscoveryGatt = null
        clearPendingHistoryRange()
        svcDeviceInfo = null
        svcCgm = null
        svcAuth = null
        sessionKeyHex = ""
        // Session-scoped like the key: a live frame from the link being torn down must not answer
        // the post-history one-shot's freshness question about the next one, and readrssi's 9999
        // sentinel is what the sensor screens read as "no live link".
        lastLiveFrameAtMs = 0L
        readrssi = 9999
        authStep = AuthStep.NONE
        actStep = ActStep.NONE
        if (!preserveActivationNegotiation) resetActivationNegotiation()
        awaitingFreshActivationAdvertisement = false
        notifyEnableIndex = 0
        discoveryStarted = false
        pendingActivation = false
        activationInFlight = false
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
            commandByteSeenThisAttempt,
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
                commandByteSeenThisAttempt,
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

    // Set only on terminal teardown (free()/discard() call onTerminalFree), never by setPause:
    // a paused manager (user soft-disconnect, ownership release to the watch, clone block) is
    // resumed later and must keep feeding native, or Room and native drift apart for good.
    @Volatile private var released = false

    override fun onTerminalFree() {
        released = true
        super.onTerminalFree()
    }

    override fun close() {
        advertisementProbe.stop()
        resetServerActiveAuthState()
        if (stop) {
            // Permanent shutdown: free() sets stop=true before calling close(), so
            // quit the HandlerThread here — otherwise it outlives the sensor object.
            // Transient close() calls (stop=false) keep the thread alive for reconnect.
            handler.removeCallbacksAndMessages(null)
            runCatching { handlerThread.quitSafely() }
        }
        super.close()
    }

    /**
     * Server verify material is bound to one specific challenge and connection episode; never
     * let it leak into a reconnect against a fresh challenge or a different sensor.
     */
    private fun resetServerActiveAuthState() {
        serverAuthHostBytes = null
        serverAuthFlagBytes = null
        lastVerifyMaterial = null
        lastVerifyAuthFlagHex = null
        v3BindAttemptedThisEpisode = false
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

    // BluetoothAdapter.getDefaultAdapter(): the fallback when no BluetoothManager is reachable; minSdk 26
    @Suppress("DEPRECATION")
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
        activationDiscoveryStartedAtMs = System.currentTimeMillis()
        activationCandidateProbeActive = false
        clearDeferredActivationCandidateCgmInfo()
        mActiveDeviceAddress = address
        awaitingFreshActivationAdvertisement = true
        constatstatusstr = appString(R.string.looking_for_transmitters, "Looking for nearby transmitters...")
        // Posted before the scan is asked for: scanStarter can still throw (it walks the shared
        // callback list without a lock), and a wait without its timeout is never abandoned.
        handler.removeCallbacks(freshActivationAdvertisementTimeoutRunnable)
        handler.postDelayed(
            freshActivationAdvertisementTimeoutRunnable,
            FRESH_ACTIVATION_ADVERTISEMENT_TIMEOUT_MS,
        )
        // scanStarter refuses a second start while mScanning or scanstart is set, and it does
        // not rebuild filters for a callback added after the scan began. The setup panel's own
        // scanner leaves that flag set (and can be the scanner the platform is actually
        // delivering to). Skipping here keeps this wait on "Looking for nearby transmitters"
        // until the timeout, because no advertisement reaches this callback. Stop first so the
        // start below is the one that hears them.
        val blue = SensorBluetooth.blueone
        if (blue != null && SensorBluetooth.scanActiveOrPending()) {
            Log.i(TAG, "restarting managed scan for activation advertisement")
            blue.stopScan(false)
        }
        SensorBluetooth.blueone?.scanStarter(0L)
        if (activateRequestedFor?.let { matchesManagedSensorId(it) } == true) {
            armNfcActivationWake("setup activation is waiting for its first advertisement")
        }
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

    fun isSetupActivationFailed(): Boolean = activationFailed

    fun noteNewUserActivationGesture(advanced: Boolean) {
        commandByteSeenThisAttempt = false
        activationFailed = false
        activationInFlight = false
        handler.removeCallbacks(startActivationAfterStatusRunnable)
        if (!advanced && advancedActivateRequestedFor?.let { matchesManagedSensorId(it) } == true) {
            advancedActivateRequestedFor = null
        }
    }

    private fun advancedActivateRequested(): Boolean =
        advancedActivateRequestedFor?.let { matchesManagedSensorId(it) } == true

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

    // The shared scan is otherwise SCAN_MODE_LOW_POWER (about 10% listening). The same phone hears
    // an advertising Ottai at that duty within seconds, so a long wait here more likely means a
    // fresh sensor that advertises late or sparsely; full duty is a cheap hedge for that case,
    // asked for only inside the bounded wait. Volatile reads only: see the base-class note.
    override fun wantsLowLatencyScan(): Boolean =
        !stop && wantsLowLatencyActivationScan(
            awaitingFreshActivationAdvertisement,
            activationDiscoveryStartedAtMs,
            System.currentTimeMillis(),
        )

    override fun onScanResult(result: ScanResult) {
        super.onScanResult(result)
        val armedAt = activationDiscoveryStartedAtMs
        if (!activationCandidateDiscoveryPending || armedAt <= 0L) return
        if (activationScanHitLoggedArmedAtMs == armedAt) return
        activationScanHitLoggedArmedAtMs = armedAt
        logi(TAG) {
            "activation scan first hit address=${runCatching { result.device?.address }.getOrNull()} " +
                "rssi=${result.rssi} afterArmMs=${System.currentTimeMillis() - armedAt}"
        }
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
            exactOnlyWindowOpen = OttaiConstants.isActivationExactOnlyWindowOpen(
                activationDiscoveryStartedAtMs,
                System.currentTimeMillis(),
            ),
        )
        if (!selected) return false
        if (!scannedAddress.equals(expectedAddress, ignoreCase = true)) {
            // Not necessarily NFC: this branch is reached by the name-only fallback too, which
            // admits ANY advertisement containing "ottai". Saying "NFC-woken" invented a user
            // action that had not happened and hid the real reason a stranger was being probed.
            Log.i(TAG, "probing Ottai activation candidate address=$scannedAddress " +
                "expected=$expectedAddress (name-only match)")
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
        noteFirstGattCallback("onConnectionStateChange", gatt)
        super.onConnectionStateChange(gatt, status, newState)
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
                consecutiveConnectFailures = 0
                liveReadRetryCount = 0
                // The outage is over. Stop the probe rather than letting it run out its window:
                // the link landing IS an advertisement getting through, and a probe that is only
                // ever read out on timeout would leave every fast recovery out of the sample.
                if (advertisementProbe.isActive) {
                    val outageSec = ((connectTime - outageStartedAtMs).coerceAtLeast(0L)) / 1000L
                    logi(TAG) { "advertisement probe ended by connect afterOutageSec=$outageSec" }
                    advertisementProbe.stop()
                }
                probedThisOutage = false
                priorityReassertCount = 0
                fastReArmWithdrawn = false
                fastReArmAtMs = 0L
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
                activationInFlight = false
                handler.removeCallbacks(startActivationAfterStatusRunnable)
                notifyEnableIndex = 0
                discoveryStarted = false
                roomBackfillChecked = false
                // This link checks the stream anchor again. Until one of its live frames agrees with
                // it, an unconfirmed start dates no history (trustedStreamStartMs) and builds no
                // monitor time from it (officialStartMs ignores an unreliable anchor): the first
                // frame's monitor time is built on the pending start claim for a synthesized runtime
                // and on this app's command instant for a real one (monitorBaseStartMs), and taken
                // only if its arrival agrees; otherwise the frame is dated by its arrival. The value
                // stays for a confirmed start, which still dates from it. The pending start claim
                // stays too — see pendingConfirmedStartMs.
                streamStartReliable = false
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
                val resumeActivation = OttaiConstants.shouldReconnectToResumeActivation(
                    activateCommandIssued,
                    activationNegotiationActive,
                    activationRetryPending,
                    maxActiveCandidatesMs.isNotEmpty(),
                    activationInFlight,
                )
                val stoppedBeforeActivationCommand = OttaiConstants.autoFailClosedBeforeActivationAck(
                    OttaiConstants.commandNeedsActivation(commandStatus),
                    activateCommandIssued,
                    activationCommandSentAtMs,
                    actStep == ActStep.COMMAND,
                )
                activationInFlight = false
                handler.removeCallbacks(startActivationAfterStatusRunnable)
                phase = Phase.IDLE
                handler.removeCallbacks(livePollRunnable)
                handler.removeCallbacks(postHistoryLiveRunnable)
                handler.removeCallbacks(rssiPollRunnable)
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
                // Same session scope as the key above: the next link asks its own freshness
                // question, and the sensor screens must not keep rendering the dead link's RSSI
                // for the whole outage.
                lastLiveFrameAtMs = 0L
                readrssi = 9999
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
                } else if (OttaiConstants.shouldScanForActivationCandidateOn147(
                        resumeActivation,
                        activationRetryPending,
                        status,
                    )
                ) {
                    beginActivationCandidateDiscovery(
                        "direct reconnect timed out with status=147",
                    )
                } else if (resumeActivation) {
                    mActiveDeviceAddress = addressAfterCandidate(activationRetryAddress ?: disconnectedAddress)
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
                        commandByteSeenThisAttempt,
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
                    val now = System.currentTimeMillis()
                    if (fastReArmBounced(status, now, fastReArmAtMs)) {
                        fastReArmWithdrawn = true
                        Log.w(TAG, "status=$status ${now - fastReArmAtMs}ms after a shortened " +
                            "re-arm connected; back to the ${RECONNECT_DELAY_MS}ms delay for this outage")
                    }
                    consecutiveConnectFailures += 1
                    val delayMs = reconnectDelayAfterDisconnectMs(
                        status,
                        !fastReArmWithdrawn,
                        consecutiveConnectFailures,
                    )
                    if (delayMs > RECONNECT_DELAY_MS) {
                        Log.w(TAG, "status=$status failure #$consecutiveConnectFailures in a row; " +
                            "backing off to ${delayMs}ms")
                    }
                    fastReArmAtMs =
                        if (delayMs == SUPERVISION_TIMEOUT_RECONNECT_DELAY_MS) now + delayMs else 0L
                    scheduleReconnect("gatt disconnect", delayMs)
                }
                // Last, so the guard sees whatever the branches above decided: an activation scan
                // armed there owns the radio and this must stand aside. The reconnect is already
                // scheduled either way — the probe only watches.
                if (status != 0) startOutageAdvertisementProbe(status)
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

    /**
     * Listen once, at the start of an outage, for the sensor's own advertisement.
     *
     * Observe-only by construction: it scans the registry address — never a candidate one probe
     * may have retargeted us at — and its result is written to the log and nowhere else. The
     * driver's recovery is untouched; the reconnect it was going to make is already armed.
     */
    private fun startOutageAdvertisementProbe(status: Int) {
        if (stop) return
        val explicitActivationRequest =
            activateRequestedFor?.let { matchesManagedSensorId(it) } == true
        val now = System.currentTimeMillis()
        if (!shouldProbeOutageAdvertisement(
                alreadyProbedThisOutage = probedThisOutage,
                // activationNegotiationActive covers the lifetime-retry branch too: that one
                // reconnects without arming either scanning sub-state, and the maxActive
                // candidate walk is the last path that should be sharing the radio.
                activationScanActive = activationNegotiationActive ||
                    activationCandidateDiscoveryPending ||
                    awaitingFreshActivationAdvertisement ||
                    explicitActivationRequest,
                managedScanActive = SensorBluetooth.scanActiveOrPending(),
                nowMs = now,
                lastProbeAtMs = lastOutageProbeAtMsShared,
            )
        ) {
            return
        }
        val address = ownRecordAddress() ?: return
        probedThisOutage = true
        outageStartedAtMs = now
        lastOutageProbeAtMsShared = now
        val started = advertisementProbe.start(address, OUTAGE_PROBE_TIMEOUT_MS)
        logi(TAG) { "advertisement probe started=$started after status=$status address=$address" }
    }

    private fun finishOutageAdvertisementProbe(rssi: Int?) {
        val afterOutageSec = ((System.currentTimeMillis() - outageStartedAtMs).coerceAtLeast(0L)) / 1000L
        if (rssi == null) {
            logi(TAG) { "advertisement not seen within ${OUTAGE_PROBE_TIMEOUT_MS / 1000L}s of the drop" }
        } else {
            logi(TAG) { "advertisement seen afterOutageSec=$afterOutageSec rssi=$rssi" }
        }
    }

    override fun onConnectionParamsUpdated(
        gatt: BluetoothGatt,
        interval: Int,
        latency: Int,
        timeout: Int,
        status: Int,
    ) {
        if (stop || status != 0 || gatt !== mBluetoothGatt) return
        // This line has always been unconditional; what changed is that it is now reachable. R8
        // deleted onConnectionUpdated from every release build ever shipped, so the ~136 lines per
        // sensor per 4h of link-parameter history it writes existed nowhere at all. logi() reaches
        // trace.log in release builds (only android.util.Log is stripped there), which is the only
        // place an outage can be read back from a user's device.
        logi(TAG) { "conn params interval=$interval latency=$latency timeout=$timeout" }
        val now = System.currentTimeMillis()
        if (!shouldHoldFastParams(
                interval,
                latency,
                linkUnstable(now),
                now,
                lastPriorityReassertAtMs,
                priorityReassertCount,
            )
        ) {
            return
        }
        lastPriorityReassertAtMs = now
        priorityReassertCount++
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
            if (!OttaiConstants.mayEnterActivationWrites(
                    commandStatus,
                    activateCommandIssued,
                    advancedActivateRequested(),
                )
            ) {
                Log.w(TAG, "activation cancelled after rediscovery — commandStatus=$commandStatus " +
                    "issued=$activateCommandIssued")
                activationInFlight = false
                if (commandStatus == 3) {
                    resetActivationNegotiation()
                } else if (!activateCommandIssued) {
                    failActivation("command status $commandStatus does not need activation")
                }
                return
            }
            Log.i(TAG, "post-auth re-discovery complete — starting activation")
            startActivationWrites(gatt)
            return
        }
        authKeys = materials.authKeys
        val ctx = Applic.app
        val id = SerialNumber
        when (ottaiAuthEntryMode(
            hasAuthKeys = authKeys != null,
            bootstrapPending = ctx != null && id != null &&
                OttaiRegistry.isV3CredentialBootstrapPending(ctx, id),
            cnSessionAvailable = cnActiveAuthSessionAvailable(),
            validatedDeviceVersion = if (ctx != null && id != null) {
                OttaiRegistry.loadLastValidatedDeviceVersion(ctx, id)
            } else {
                null
            },
        )) {
            OttaiAuthEntryMode.STORED_MATERIAL_AUTH -> {
                phase = Phase.ENABLING_NOTIFY
                notifyEnableIndex = 0
                enableNextNotification(gatt)
            }
            OttaiAuthEntryMode.V3_CREDENTIAL_BOOTSTRAP -> {
                Log.i(TAG, "AUTHWIRE starting V3 credential bootstrap")
                startAuth(gatt)
            }
            OttaiAuthEntryMode.BLOCKED -> {
                Log.w(TAG, "no auth keys and no authorized V3 bootstrap — disconnecting")
                clearGattTransport("missing Ottai credentials", markSignalLoss = false)
            }
        }
    }

    // legacy BLE API: required at minSdk 26; the API 33 overloads are not a drop-in replacement
    @Suppress("DEPRECATION")
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

    /**
     * Server-mediated V3 Active_Auth: POST the sensor-read authDev/authFlag to /cgmAuth/verify
     * and hand [onResult] the returned write-back material on the main handler. The caller gates
     * its auth writes on this — the official client writes the SERVER's authHost/authFlag to
     * 1756ef6e / 785022c6, not locally computed values. Single-flight; concurrent callers queue,
     * and a duplicate request for the same challenge can reuse its accepted result.
     */
    private fun requestCgmAuthVerify(
        authDevHex: String,
        authFlagHex: String,
        onResult: (OttaiCloudClient.V3AuthMaterial?) -> Unit,
    ) {
        fun finish(r: OttaiCloudClient.V3AuthMaterial?) = handler.post { onResult(r) }
        if (authDevHex.isBlank() || authFlagHex.isBlank()) { finish(null); return }
        // A zero authFlag is the broken/unactivated case; the server rejects it as "shaInfo empty".
        if (authFlagHex.all { it == '0' }) { Log.i(TAG, "AUTHWIRE verify skipped: authFlag all-zero"); finish(null); return }
        val app = Applic.app ?: run { finish(null); return }
        val mac = OttaiCrypto.bytesToHex(macBytes)
        if (mac.isBlank()) { finish(null); return }
        lastVerifyMaterial?.takeIf { lastVerifyAuthFlagHex == authFlagHex }?.let {
            Log.i(TAG, "AUTHWIRE verify reused cached material for this challenge")
            finish(it); return
        }
        synchronized(verifyLock) {
            if (verifyInFlight) {
                Log.i(TAG, "AUTHWIRE verify queued behind in-flight request")
                verifyWaiters.add(onResult)
                return
            }
            verifyInFlight = true
        }
        Thread {
            var delivered = false
            fun deliver(r: OttaiCloudClient.V3AuthMaterial?) {
                if (delivered) return
                delivered = true
                handler.post { onResult(r) }
                val waiters: List<(OttaiCloudClient.V3AuthMaterial?) -> Unit>
                synchronized(verifyLock) {
                    waiters = ArrayList(verifyWaiters)
                    verifyWaiters.clear()
                    verifyInFlight = false
                }
                waiters.forEach { w -> handler.post { w(r) } }
            }
            try {
                val result = runCatching {
                    OttaiCloudClient.cgmAuthVerify(app, mac, authDevHex, authFlagHex)
                }.onFailure {
                    Log.w(TAG, "cgmAuth/verify failed: ${it.message}")
                }.getOrNull()
                if (result != null && result.ok) {
                    lastVerifyMaterial = result
                    lastVerifyAuthFlagHex = authFlagHex
                }
                deliver(result)
            } finally {
                synchronized(verifyLock) { verifyInFlight = false }
                deliver(null)
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun startAuth(gatt: BluetoothGatt) {
        phase = Phase.AUTH
        // Every connection presents a fresh challenge: stale verify material must not survive.
        resetServerActiveAuthState()
        authStep = AuthStep.READ_DEVICE_TIME
        readChar(gatt, OttaiConstants.SERVICE_DEVICE_INFO, OttaiConstants.CHAR_CURRENT_TIME)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onCharacteristicRead(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
        handleCharacteristicRead(gatt, ch, ch.value ?: ByteArray(0), status)
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        handleCharacteristicRead(gatt, ch, value, status)
    }

    private fun handleCharacteristicRead(
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        if (gatt !== mBluetoothGatt) {
            Log.w(TAG, "onCharacteristicRead: stale callback, ignoring")
            return
        }
        noteGattActivity()
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "read err ${ch.uuid.toString().take(8)} status=$status phase=$phase")
            // A failed maxActive readback must never tear down a healthy stream: the stored
            // lifetime stands until the next status 3 reads it again.
            if (ch.uuid == OttaiConstants.CHAR_MAX_ACTIVE_TIME) return
            if (phase == Phase.STREAMING || phase == Phase.AUTH) recoverGattAndReconnect("read failed status=$status")
            return
        }
        when (ch.uuid) {
            OttaiConstants.CHAR_CURRENT_TIME -> {
                deviceTimeBytes = value.copyOf()
                Log.i(TAG, "AUTHWIRE read currentTime(2a2b) len=${value.size}")
                authStep = AuthStep.READ_DEVICE_PARAM
                readChar(gatt, OttaiConstants.SERVICE_AUTH, OttaiConstants.CHAR_AUTH_DEVICE_PARAM)
            }
            OttaiConstants.CHAR_AUTH_DEVICE_PARAM -> {
                // authDev in the current CN V3 flow: the value posted to /cgmAuth/verify.
                Log.i(TAG, "AUTHWIRE read authDev(86805092) len=${value.size}")
                lastAuthDevHex = OttaiCrypto.bytesToHex(value)
                parseDeviceAuthParam(value)
                authStep = AuthStep.READ_DEVICE_SIGN
                readChar(gatt, OttaiConstants.SERVICE_AUTH, OttaiConstants.CHAR_AUTH_SIGN)
            }
            OttaiConstants.CHAR_AUTH_SIGN -> {
                Log.i(TAG, "AUTHWIRE read authSign(785022c6) len=${value.size}")
                val authFlagHex = OttaiCrypto.bytesToHex(value)
                if (!verifyDeviceSign(value) && activationCandidateProbeActive) {
                    if (isKnownActivationAddress(
                            candidateAddress = gatt.device?.address,
                            homeAddress = activationCandidateHomeAddress,
                            recordAddress = ownRecordAddress(),
                        )
                    ) {
                        continueKnownAddressCandidate(gatt)
                    } else {
                        rejectActivationCandidate(gatt)
                        return
                    }
                }
                // Fresh V3 credential bootstrap: the writes must carry the SERVER's answer, so
                // hold them until /cgmAuth/verify returns. Once bindV3 has persisted keyA, normal
                // reconnects use the existing locally-computed auth path.
                if (v3CredentialBootstrapActive()) {
                    Log.i(TAG, "AUTHWIRE holding auth writes for /cgmAuth/verify")
                    requestCgmAuthVerify(lastAuthDevHex, authFlagHex) { material ->
                        if (phase != Phase.AUTH) return@requestCgmAuthVerify
                        val host = material?.authHost?.let { runCatching { OttaiCrypto.hexToBytes(it) }.getOrNull() }
                        val flag = material?.authFlag?.let { runCatching { OttaiCrypto.hexToBytes(it) }.getOrNull() }
                        if (host != null && flag != null && host.isNotEmpty() && flag.isNotEmpty()) {
                            serverAuthHostBytes = host
                            serverAuthFlagBytes = flag
                            Log.i(TAG, "AUTHWIRE server material ready; writing server authHost/authFlag")
                        } else {
                            Log.w(TAG, "AUTHWIRE V3 credential bootstrap failed: no server material")
                            finishV3CredentialBootstrap(
                                null,
                                OttaiCloudClient.lastFailure
                                    ?: OttaiCloudClient.CloudFailure("Server returned no active-auth material"),
                            )
                            return@requestCgmAuthVerify
                        }
                        writeAppParam(gatt)
                    }
                    return
                }
                writeAppParam(gatt)
            }
            OttaiConstants.CHAR_MAX_ACTIVE_TIME -> handleMaxActiveRead(value)
            OttaiConstants.CHAR_CGM_INFO_NOTIFY -> handleCgmInfo(value, source = "read")
            OttaiConstants.CHAR_GLUCOSE_LIVE -> handleGlucosePayload(value, live = true, source = "read")
            OttaiConstants.CHAR_COMMAND -> {
                val status = if (value.isNotEmpty()) value[0].toInt() and 0xFF else -1
                Log.i(TAG, "cmd/activation status=$status (official: 3=activated, <3=needs activation) len=${value.size}")
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
            val advancedActivate = advancedActivateRequested()
            val activationBusy = pendingActivation || activationInFlight ||
                (actStep != ActStep.NONE && actStep != ActStep.DONE)
            val resumeNegotiation = OttaiConstants.shouldResumeLifetimeNegotiation(
                activateCommandIssued,
                activationNegotiationActive,
                activationRetryPending,
                activationBusy,
            )
            commandByteSeenThisAttempt = true
            if (activateCommandIssued && !advancedActivate) {
                Log.i(TAG, "sensor still needs activation status=$status; 0x03 already issued — no lifetime rewrite")
            } else if (resumeNegotiation) {
                activationInFlight = true
                Log.i(TAG, "sensor still needs activation status=$status; resuming lifetime " +
                    "attempt=${maxActiveCandidateIndex + 1}/${maxActiveCandidatesMs.size} after fresh auth")
                handler.removeCallbacks(startActivationAfterStatusRunnable)
                handler.postDelayed(startActivationAfterStatusRunnable, 250L)
            } else if (OttaiConstants.shouldScheduleFirstUseActivation(
                    status,
                    explicitRequest,
                    activateCommandIssued,
                    advancedActivate,
                    activationInFlight,
                    activationBusy,
                )
            ) {
                activationInFlight = true
                Log.i(TAG, "sensor command status=$status and user requested activation; " +
                    "starting official activation sequence")
                handler.removeCallbacks(startActivationAfterStatusRunnable)
                handler.postDelayed(startActivationAfterStatusRunnable, 250L)
            } else if (explicitRequest || advancedActivate || activationBusy || activationInFlight) {
                Log.i(TAG, "sensor still needs activation status=$status " +
                    "(requested=$explicitRequest advanced=$advancedActivate inFlight=$activationInFlight busy=$activationBusy)")
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
            if (advancedActivateRequestedFor?.let { matchesManagedSensorId(it) } == true) {
                advancedActivateRequestedFor = null
            }
            handler.removeCallbacks(pendingActivationNfcPromptRunnable)
            SerialNumber?.let { sensorId ->
                OttaiNfc.disarmActivationRetry(sensorId)
                Applic.app?.let { OttaiNfcWakeReminder.cancel(it, sensorId) }
            }
            handler.removeCallbacks(endedHistoryBackfillRunnable)
            handler.removeCallbacks(activationConfirmRunnable)
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
        // The lifetime is what the firmware holds, so read maxActive back off the sensor on every
        // entry into status 3 — once per connection. A stored value may only be the ACK of this
        // app's own write (markMaxActiveAccepted), not the sensor's memory: an activation that
        // failed after that ACK and was finished by another app leaves a different value behind.
        // adoptActivatedMaxActive keeps an equal value and corrects a different one; a failed read
        // is swallowed in onCharacteristicRead so it never destabilizes the stream.
        handler.postDelayed({
            runCatching { readChar(gatt, OttaiConstants.SERVICE_DEVICE_INFO, OttaiConstants.CHAR_MAX_ACTIVE_TIME) }
        }, 1_200L)
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
            Log.w(TAG, "device index oob index=$deviceParamIndex keyCount=${keys.size}")
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

    private fun continueKnownAddressCandidate(gatt: BluetoothGatt) {
        val address = OttaiConstants.normalizeBleAddress(gatt.device?.address, allowPlain = false)
        activationRetryAddress = address ?: activationRetryAddress
        activationCandidateProbeActive = false
        activationCandidateDiscoveryPending = false
        activationCandidateHomeAddress = null
        awaitingFreshActivationAdvertisement = false
        handler.removeCallbacks(freshActivationAdvertisementTimeoutRunnable)
        rejectedActivationCandidateAddresses.clear()
        mActiveDeviceAddress = address ?: mActiveDeviceAddress
        SerialNumber?.let { sensorId ->
            Applic.app?.let { OttaiNfcWakeReminder.cancel(it, sensorId) }
            OttaiNfc.disarmActivationRetry(sensorId)
        }
        Log.w(TAG, "known-address candidate signature unverified; continuing normal auth address=$address")
        replayDeferredActivationCandidateCgmInfo()
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

    /**
     * Once the server's authHost/authFlag were written back and accepted, the server-side Active
     * Auth state for this MAC is complete and bindV3 can return the real materials. Runs once per
     * connection episode; the wizard watches persistence and exposes the normal JSON export once
     * this finishes.
     */
    private fun scheduleV3BindAfterActiveAuth() {
        if (v3BindAttemptedThisEpisode) return
        v3BindAttemptedThisEpisode = true
        handler.postDelayed({
            val ctx = Applic.app ?: return@postDelayed
            val id = SerialNumber ?: return@postDelayed
            if (OttaiRegistry.loadMaterials(ctx, id).authKeys != null) {
                Log.i(TAG, "bindV3 skipped: materials already saved")
                return@postDelayed
            }
            val version = OttaiRegistry.loadLastValidatedDeviceVersion(ctx, id)
            if (version.isNullOrBlank()) {
                Log.w(TAG, "bindV3 skipped: no validated deviceVersion yet")
                return@postDelayed
            }
            Thread {
                try {
                    val resp = runCatching {
                        OttaiCloudClient.bindV3(ctx, id, version)
                    }.onFailure { Log.w(TAG, "bindV3 failed: ${it.message}") }.getOrNull()
                    if (resp != null && resp.keyA.isNotBlank()) {
                        val mats = OttaiCloudClient.toMaterials(ctx, id, resp)
                        if (mats?.authKeys != null && OttaiRegistry.saveMaterials(ctx, id, mats)) {
                            Log.i(TAG, "bindV3 ok; persisted materials for $id")
                            handler.post {
                                materials = mats
                                authKeys = mats.authKeys
                                finishV3CredentialBootstrap(mats, null)
                            }
                        } else {
                            Log.w(TAG, "bindV3 returned keyA but material decrypt/persist failed")
                            finishV3CredentialBootstrap(
                                null,
                                OttaiCloudClient.CloudFailure(
                                    "bindV3 returned credentials but they could not be saved",
                                ),
                            )
                        }
                    } else {
                        Log.w(TAG, "bindV3 not accepted: ${OttaiCloudClient.lastError}")
                        finishV3CredentialBootstrap(
                            null,
                            OttaiCloudClient.lastFailure
                                ?: OttaiCloudClient.CloudFailure("bindV3 did not return credentials"),
                        )
                    }
                } finally {
                    UiRefreshBus.requestStatusRefresh()
                }
            }.also { it.isDaemon = true }.start()
        }, 2_500L)
    }

    private fun writeAppParam(gatt: BluetoothGatt) {
        // A fresh V3 sensor has no keyA yet. Its bootstrap writes the server response directly;
        // local ECDH is only possible after bindV3 has returned and persisted the auth keys.
        val host = serverAuthHostBytes
        if (host != null && host.isNotEmpty()) {
            Log.i(TAG, "AUTHWIRE write authHost-server(1756ef6e) len=${host.size}")
            authStep = AuthStep.WRITE_APP_PARAM
            writeChar(gatt, OttaiConstants.SERVICE_AUTH, OttaiConstants.CHAR_AUTH_APP_PARAM, host)
            return
        }
        val keys = authKeys ?: run {
            Log.w(TAG, "cannot write local auth parameter without stored auth keys")
            return
        }
        val kp = OttaiBleAuth.generateKeyPair()
        appPrivate = kp.private as ECPrivateKey
        val (x, y) = OttaiBleAuth.publicCoords(kp.public as ECPublicKey)
        appPubX = x; appPubY = y
        appTime3 = OttaiBleAuth.appTime3(deviceTimeBytes)
        appIndex = (0 until keys.size).random()
        val param = OttaiBleAuth.appAuthParameter(appIndex, appTime3, appPubX, appPubY)
        Log.i(TAG, "AUTHWIRE write appParam(1756ef6e) idx=$appIndex time3Len=${appTime3.size} len=${param.size}")
        authStep = AuthStep.WRITE_APP_PARAM
        writeChar(gatt, OttaiConstants.SERVICE_AUTH, OttaiConstants.CHAR_AUTH_APP_PARAM, param)
    }

    @Suppress("DEPRECATION")
    override fun onCharacteristicWrite(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
        if (gatt !== mBluetoothGatt) {
            Log.w(TAG, "onCharacteristicWrite: stale callback, ignoring")
            return
        }
        noteGattActivity()
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "write err ${ch.uuid.toString().take(8)} status=$status actStep=$actStep")
            if (actStep == ActStep.MAX_ACTIVE &&
                ch.uuid == OttaiConstants.CHAR_MAX_ACTIVE_TIME &&
                OttaiConstants.shouldRetryMaxActiveOnWriteError(status) &&
                retryMaxActiveTime(gatt, status)
            ) {
                return
            }
            if (OttaiConstants.writeErrorShouldFailActivation(
                    actStep != ActStep.NONE && actStep != ActStep.DONE,
                    actStep == ActStep.COMMAND,
                    status,
                )
            ) {
                failActivation(
                    "aborted after $actStep write error (status=$status); " +
                        "sensor rejected the advertised characteristic",
                )
            } else if (actStep != ActStep.NONE && actStep != ActStep.DONE) {
                Log.w(TAG, "write status=$status before Issued — resume on disconnect")
            }
            return
        }
        when {
            ch.uuid == OttaiConstants.CHAR_HISTORY_REQUEST -> {
                Log.i(TAG, "history request write accepted")
            }
            authStep == AuthStep.WRITE_APP_PARAM && ch.uuid == OttaiConstants.CHAR_AUTH_APP_PARAM -> {
                val serverFlag = serverAuthFlagBytes
                if (serverFlag != null && serverFlag.isNotEmpty()) {
                    // V3: the sensor expects the SERVER's shaInfo here. A locally computed V2
                    // signature is rejected by current CN firmware with write status 1.
                    Log.i(TAG, "AUTHWIRE write authFlag-server(785022c6) len=${serverFlag.size}")
                    authStep = AuthStep.WRITE_APP_SIGN
                    writeChar(gatt, OttaiConstants.SERVICE_AUTH, OttaiConstants.CHAR_AUTH_SIGN, serverFlag)
                    return
                }
                val keys = authKeys ?: return
                val authKeyHex = OttaiCrypto.bytesToHex(keys[appIndex])
                val sign = OttaiBleAuth.authSignHex(authKeyHex, OttaiCrypto.bytesToHex(macBytes), appPubX, appPubY, appTime3)
                // Locally-computed V2 signature for pre-V3 sensors.
                Log.i(TAG, "AUTHWIRE write authSign(785022c6) idx=$appIndex len=${sign.size}")
                authStep = AuthStep.WRITE_APP_SIGN
                writeChar(gatt, OttaiConstants.SERVICE_AUTH, OttaiConstants.CHAR_AUTH_SIGN, sign)
            }
            authStep == AuthStep.WRITE_APP_SIGN && ch.uuid == OttaiConstants.CHAR_AUTH_SIGN -> {
                lastBleActivityAtMs = System.currentTimeMillis()
                if (serverAuthFlagBytes != null) {
                    // Server-mediated V3 path: both server values were accepted by the sensor.
                    // There is no local ECDH to derive a session from — clear the material,
                    // report state, and let the cloud bind + streaming probe take over.
                    serverAuthHostBytes = null
                    serverAuthFlagBytes = null
                    authStep = AuthStep.DONE
                    phase = Phase.STREAMING
                    sessionKeyHex = ""
                    Log.i(TAG, "AUTHWIRE server active-auth write-back complete; fetching credentials")
                    UiRefreshBus.requestStatusRefresh()
                    scheduleV3BindAfterActiveAuth()
                    return
                }
                deriveSession()
                authStep = AuthStep.DONE
                lastBleActivityAtMs = System.currentTimeMillis()
                if (sessionKeyHex.isBlank()) {
                    // Derivation produced no usable key (a shared X whose top byte is zero and
                    // whose next byte is below 0x80 — 1 handshake in 512). Everything downstream
                    // is gated on this, so the old fall-through left the link in STREAMING with
                    // nothing to do — and STREAMING selects the 180 s stale threshold instead of
                    // the 90 s setup one, so the watchdog would not have intervened for another
                    // three minutes. On 2026-07-29 the app sat silent for 59 s until the SENSOR
                    // gave up (status=19), which cost 73 s of a six-minute activation. Drop the
                    // link ourselves and re-authenticate: the failure is per-handshake, so the
                    // retry succeeds immediately.
                    Log.w(TAG, "auth complete; session=FAILED — no session key derived, reconnecting")
                    UiRefreshBus.requestStatusRefresh()
                    recoverGattAndReconnect("session key derivation failed")
                    return
                }
                phase = Phase.STREAMING
                Log.i(TAG, "auth complete; session=ok")
                lastRssiReadAtMs = 0L
                scheduleRssiPoll(RSSI_FIRST_POLL_DELAY_MS)
                run {
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
                readChar(gatt, OttaiConstants.SERVICE_CGM, OttaiConstants.CHAR_COMMAND)
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
        // Length only — never the key itself. trace.log is user-exportable and gets attached to
        // bug reports, and this AES session key decrypts every live/history frame in the capture.
        // The length is what actually matters diagnostically: a derivation that drops a leading
        // zero byte yields a short (or empty) key, which is how the 1-in-512 auth failure was
        // found in the first place.
        Log.i(TAG, "session key derived len=${sessionKeyHex.length}")
    }

    // ---- streaming ----

    // legacy BluetoothGattCallback overload: the platform still invokes it below API 33
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        if (gatt !== mBluetoothGatt) return
        val value = ch.value ?: return
        noteGattActivity()
        when (ch.uuid) {
            OttaiConstants.CHAR_GLUCOSE_LIVE -> {
                lastLiveNotifyAtMs = System.currentTimeMillis()
                handleGlucosePayload(value, live = true, source = "notify")
            }
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
        val secs = decodeMaxActiveSeconds(value, sessionKeyHex)
        if (secs == null) {
            Log.w(TAG, "maxActive read: undecodable len=${value.size} hex=${OttaiCrypto.bytesToHex(value).take(48)}")
            return
        }
        val days = secs / 86_400L
        Log.i(TAG, "maxActive read: ${secs}s (~${days}d)")
        // Accept only a plausible activation window; guards a wrong read format / stray bytes.
        if (!plausibleMaxActiveSeconds(secs)) {
            Log.w(TAG, "maxActive read out of plausible range — ignoring")
            return
        }
        adoptActivatedMaxActive(secs * 1000L, "sensor-readback")
    }

    /**
     * Adopt [ms] as this sensor's provisioned lifetime — in memory, in prefs and in the native
     * shell — and report whether anything changed. Idempotent.
     *
     * Every path that learns the real lifetime funnels through here so none of them can persist
     * a value the others do not see.
     */
    private fun adoptActivatedMaxActive(ms: Long, source: String): Boolean {
        if (ms <= 0L || ms == activatedMaxActiveMs) return false
        activatedMaxActiveMs = ms
        val id = SerialNumber.orEmpty()
        Applic.app?.let { ctx -> if (id.isNotBlank()) OttaiRegistry.saveAcceptedMaxActive(ctx, id, ms) }
        applyActivatedWearToNative(id)
        UiRefreshBus.requestStatusRefresh()
        Log.i(TAG, "activated lifetime = ${ms / 86_400_000L}d source=$source")
        return true
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
        officialStartMs(
            cloudActiveTimeMs = materials.activeTimeMs,
            streamStartMs = streamStartTimeMs,
            streamStartReliable = streamStartReliable,
            activationCommandSentAtMs = activationCommandSentAtMs,
        )

    private fun warmupRemainingMs(now: Long = System.currentTimeMillis()): Long {
        val start = warmupAnchorMs()
        if (start <= 0L) return -1L
        return (start + OttaiConstants.WARMUP_SUPPRESS_MS - now).coerceAtLeast(0L)
    }

    /**
     * Start instant the warmup gate is allowed to trust — deliberately narrower than
     * [effectiveActiveTimeMs].
     *
     * It refuses provisionalActiveTimeMs and streamStartTimeMs because both can be derived from
     * the cgm-info 0x0477 sliding window, which for a sensor the vendor app activated days ago
     * always reads "just now"; warming up on those would blank ten minutes of good readings on
     * every fresh connect. The fallback is the instant this app wrote the activation command,
     * which is the only trustworthy start available in the first ~80 s after our own
     * activation — materials.activeTimeMs is not confirmed until the live anchor corroborates it
     * (on the 2026-07-29 run the command went out at 17:18:15 and the start was confirmed at
     * 17:19:00, by which point four readings had already been published). That instant is kept
     * under a key of its own, not the provisional slot cgm-info also writes, so a restart inside
     * the settling window does not switch the gate off.
     */
    private fun warmupAnchorMs(): Long =
        warmupAnchorFor(materials.activeTimeMs, activationCommandSentAtMs, restoredActivationCommandAtMs)

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
     * so a single corrupt frame must not be able to set it. Require two reliable anchors from
     * different records that agree (startsCorroborate): a genuine pair lands within a record or
     * two while a corrupt dataNo lands far away, and one record read twice proves nothing.
     */
    private fun offerConfirmedActiveTime(startMs: Long, dataNo: Int) {
        val now = System.currentTimeMillis()
        val pending = pendingConfirmedStartMs
        when (val step = confirmationStep(materials.activeTimeMs, pending, pendingConfirmedDataNo, startMs, dataNo, now)) {
            ConfirmStep.Ignore -> Unit
            ConfirmStep.Implausible ->
                Log.w(TAG, "implausible activation start=${startMs / 1000L} ignored (now=${now / 1000L})")
            is ConfirmStep.Pend -> {
                pendingConfirmedStartMs = step.startMs
                pendingConfirmedDataNo = step.dataNo
                Log.i(TAG, "activation start candidate=${step.startMs / 1000L} dataNo=${step.dataNo} awaiting corroboration")
            }
            is ConfirmStep.Commit -> commitConfirmedActiveTime(step.startMs)
            is ConfirmStep.Rearm -> {
                Log.w(TAG, "activation start candidates disagree: ${pending / 1000L} vs ${step.startMs / 1000L}; " +
                    "re-arming corroboration")
                pendingConfirmedStartMs = step.startMs
                pendingConfirmedDataNo = step.dataNo
            }
        }
    }

    private fun commitConfirmedActiveTime(startMs: Long) {
        if (startMs <= 0L || materials.activeTimeMs > 0L) return
        val id = SerialNumber.orEmpty()
        if (id.isBlank()) return
        materials = materials.copy(activeTimeMs = startMs)
        provisionalActiveTimeMs = 0L
        // The claim that led here has been spent. Left standing, it stays half of a pair for the
        // rest of the process: the next frame that disagrees with the anchor just committed would
        // be dated by it (datesLiveByArrival) instead of being held for a second read.
        pendingConfirmedStartMs = 0L
        pendingConfirmedDataNo = -1
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

    /** The learned layout to frame with, or null while nothing has proved one. */
    private fun heldRecordSize(): Int? = learnedRecordSize.takeIf { it > 0 }

    /**
     * Adopt the record layout when a payload is big enough to prove it, and say so when a
     * payload would have chosen differently.
     *
     * The diagnostic is the point of the second half: the 2026-08-30 trace could show that
     * 112 live frames framed as 8-byte and lost their sample, but not *why* the inference
     * flipped, because the payloads are encrypted and only the record count reaches the log.
     * Printing the evidence counts on disagreement makes the next trace answer that directly.
     * Logged once per run of disagreement so a persistently odd sensor cannot flood the log.
     */
    private fun learnRecordSize(payload: ByteArray) {
        val id = SerialNumber ?: return
        // The version string may only seed a width nothing has proved yet, and only when that
        // width fits this frame. Once the content has settled a width, a short live frame
        // (which proves nothing) must not flip it back.
        val decisive = OttaiParser.recordSizeToLearn(payload, materials.deviceVersion, learnedRecordSize)
        if (decisive != null) {
            recordSizeDisagreementLogged = false
            if (decisive != learnedRecordSize) {
                val previous = learnedRecordSize
                learnedRecordSize = decisive
                Applic.app?.let { OttaiRegistry.saveRecordSize(it, id, decisive) }
                Log.i(TAG, "record size learned=$decisive previous=$previous len=${payload.size}")
            }
            return
        }
        val held = learnedRecordSize.takeIf { it > 0 } ?: return
        val wouldChoose = OttaiParser.chooseRecordSize(payload, materials.deviceVersion)
        if (wouldChoose == held) {
            recordSizeDisagreementLogged = false
            return
        }
        if (recordSizeDisagreementLogged) return
        recordSizeDisagreementLogged = true
        val (nine, eight) = OttaiParser.recordSizeEvidence(payload)
        Log.i(
            TAG,
            "record size held=$held payloadWould=$wouldChoose nine=$nine eight=$eight len=${payload.size}",
        )
    }

    /**
     * A history response that yielded nothing usable is still a response: don't let it stall the
     * chunk chain — treat the in-flight window as yielding nothing and advance to the next pending
     * chunk. Record the miss first: a detected-gap window stays on the hole ledger until its data
     * arrives or the attempt cap retires it (truly-empty overshoot ranges).
     */
    private fun abandonHistoryWindow() {
        if (activeHistoryEndExclusive <= 0) return
        val missedStart = activeHistoryStart
        val missedEnd = activeHistoryEndExclusive
        cancelHistoryWatchdog()
        historyRetryCount = 0
        noteHoleFailure(missedStart, missedEnd)
        advanceHistoryChunkChain(missedStart, missedEnd)
    }

    private fun handleGlucosePayload(cipher: ByteArray, live: Boolean, source: String) {
        if (sessionKeyHex.isBlank()) { Log.w(TAG, "payload before session key"); return }
        if (live && commandStatus >= 4) {
            handleEndedLiveBuffer(cipher, source)
            return
        }
        val receivedAtMs = System.currentTimeMillis()
        val kind = if (live) "live" else "history"
        // Lazy: hex-encoding the cipher on every notification is pure waste when tracing is off.
        logi(TAG) { "$kind $source cipher len=${cipher.size} hex=${OttaiCrypto.bytesToHex(cipher).take(96)}" }
        val payload = OttaiCrypto.decryptPayload(cipher, sessionKeyHex)
        if (payload == null) {
            Log.w(TAG, "$kind $source decrypt failed len=${cipher.size} blockMod=${cipher.size % 16}")
            return
        }
        learnRecordSize(payload)
        // After learnRecordSize: the layout decides whether the record's runtime is its own.
        val activeMs = monitorBaseStartMs(
            payload, materials.deviceVersion, heldRecordSize(),
            materials.activeTimeMs, streamStartTimeMs, streamStartReliable, activationCommandSentAtMs,
            pendingConfirmedStartMs,
        )
        val records = OttaiParser.frameRecords(payload, materials.deviceVersion, heldRecordSize())
        if (records.isEmpty()) {
            Log.w(TAG, "$kind $source no records payloadLen=${payload.size} hex=${OttaiCrypto.bytesToHex(payload).take(160)}")
            if (live) {
                handler.postDelayed({ requestRecentHistory("empty-live") }, 1_800L)
            } else {
                abandonHistoryWindow()
            }
            return
        }
        logi(TAG) { "$kind $source decrypted payloadLen=${payload.size} records=${records.size} front=${OttaiParser.frontDataNo(payload)}" }
        val readings = if (live) {
            listOf(OttaiParser.toReading(records.last(), materials.method, materials.coefficients, activeMs))
        } else {
            records.map { OttaiParser.toReading(it, materials.method, materials.coefficients, activeMs) }
        }
        // A history frame decoded at the wrong record width is almost entirely rejects, and the
        // few records that slip through are coincidental grid matches that look like glucose.
        // Judge the frame whole before any of it can be stored or mirrored.
        if (OttaiOutputFilter.discardsWholeFrame(live, readings)) {
            Log.w(TAG, "$kind $source misframed: ${readings.size} records at width ${heldRecordSize() ?: "auto"}, discarding frame")
            abandonHistoryWindow()
            return
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
        // A live frame that survived the ceiling means the app holds the sensor's current
        // position, whether or not the sample inside it turns out to be one Room already has —
        // which is exactly what the post-history one-shot needs to know, so it is stamped here
        // rather than after the emit. Below the ceiling filter, though: a frame whose records were
        // all dropped as corrupt says nothing about where the sensor is, and under the known
        // dataNoCeiling poison state that is every live frame there will ever be.
        if (live && plausible.isNotEmpty()) lastLiveFrameAtMs = receivedAtMs
        val emittedReadings = ArrayList<EmittedReading>(plausible.size)
        undatedSkips = 0
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
                val sampleDataNo = emittedReadings.maxOf { it.dataNo }
                val persisted = emittedReadings.any { it.persist }
                val liveEndExclusive = liveBackfillEndExclusive(persisted, lastDataNo, sampleDataNo)
                val previousForHistory = previousDataNoForHistory(
                    previousDataNo,
                    if (persisted) lastDataNo else sampleDataNo,
                )
                if (previousForHistory != previousDataNo) {
                    Log.w(TAG, "ignore ahead previous dataNo for history previous=$previousDataNo live=$liveEndExclusive")
                }
                // A live sample arrived — the live path owns the backfill now; drop the
                // independent backstop so the two don't both issue (which would wipe and restart
                // the in-flight chunk chain via clearPendingHistoryRange).
                handler.removeCallbacks(initialHistoryRunnable)
                ledgerLiveGap(previousForHistory, liveEndExclusive)
                val backfill = requestRoomBackfillAfterLive(
                    liveEndExclusive,
                    previousForHistory,
                    observedNewest = persisted,
                )
                if (shouldFetchHistoryAfterUnfreshLive(
                        persisted,
                        backfill.issued || backfill.diffOwnsRecovery,
                        previousForHistory,
                        liveEndExclusive,
                    )
                ) {
                    val holeStart = if (previousForHistory < 0) 0 else previousForHistory + 1
                    addHistoryHole(holeStart, liveEndExclusive)
                    if (!requestHistoryAfterLive(previousForHistory, liveEndExclusive)) {
                        scheduleHoleRetry()
                    }
                } else if (!backfill.issued && !backfill.diffOwnsRecovery && !roomBackfillChecked) {
                    val missingBeforeLive = liveEndExclusive - previousForHistory - 1
                    if (previousForHistory < 0 || missingBeforeLive > 0) {
                        handler.postDelayed({ requestHistoryAfterLive(previousForHistory, liveEndExclusive) }, 1_500L)
                    }
                }
            } else {
                if (!continueHistoryAfterPayload(plausible)) {
                    // History can arrive in a long burst and still end several minutes behind
                    // wall time. Ask live immediately afterwards on a one-shot that cgm-info
                    // cadence updates cannot cancel — unless live is already current, which the
                    // room-backfill caller makes the common case.
                    if (shouldReadLiveAfterHistory(receivedAtMs, lastLiveFrameAtMs)) {
                        handler.removeCallbacks(postHistoryLiveRunnable)
                        handler.postDelayed(postHistoryLiveRunnable, 1_000L)
                    }
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
            handler.removeCallbacks(endedHistoryBackfillRunnable)
            handler.postDelayed(endedHistoryBackfillRunnable, 4_500L)
            return
        }
        val ceiling = endedLiveDataNoCeiling(
            materials.activeTimeMs,
            System.currentTimeMillis(),
            lastDataNo,
        )
        val latest = if (ceiling == Int.MAX_VALUE && lastDataNo <= 0) {
            Log.w(TAG, "ended live $source has no dataNo ceiling; not indexing")
            null
        } else {
            endedLiveIndexRecord(
                OttaiParser.frameRecords(payload, materials.deviceVersion, heldRecordSize())
                    .map(OttaiParser::parseRecord),
                ceiling,
            )
        }
        if (latest == null) {
            Log.w(TAG, "ended live $source has no sane records")
        } else {
            repairAheadLastDataNoIfNeeded(latest.dataNo, live = true)
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
        }
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
            undatedSkips++
            return null
        }
        // Nothing is measured after it arrives. A date further ahead than the live freshness margin
        // comes from a wrong anchor, a clock step or a corrupt dataNo, and once stored it cannot be
        // taken back. Skipped rather than rejected: the rejected-sample veto would refuse this
        // record again once a corrected anchor could date it.
        if (isSampleAheadOfArrival(receivedAtMs, sampleMs)) {
            Log.w(TAG, "${if (live) "live" else "history"} sample ${(sampleMs - receivedAtMs) / 1000L}s " +
                "ahead of arrival — skipping emit dataNo=${r.record.dataNo}")
            undatedSkips++
            return null
        }
        // This frame's date agrees with its own arrival, so whatever produced it — the anchor, a
        // re-anchor, the confirmed start — agrees with the clock history would be dated against.
        // That settles the disagreement recorded in resolveSampleTimeMs. Ahead of the warmup and
        // continuity gates on purpose: those judge the glucose value, and a frame they drop has
        // still shown where the sensor's counter sits in time.
        if (live && isFreshLiveSample(receivedAtMs, sampleMs)) anchorDisputed = false
        // Post-activation settling. The sensor streams from dataNo 0 but the electrode has not
        // equilibrated, so these records are decoded and logged and then dropped — they must not
        // reach current publishing, Room, the native journal or any exchange consumer. Gated on
        // the sample rather than on the clock so a later history replay of the same records
        // reaches the same verdict instead of re-admitting what the live path refused — and, while
        // this app holds an activation instant for the sensor, on the record's own counter, which no
        // clock step moves.
        if (warmupSuppresses(warmupAnchorMs(), sampleMs, r.record.dataNo)) {
            return rejectReading(r, mmol, live, "warmup")
        }
        continuityRejectReason(r, mmol, sampleMs, live, batch, batchIndex)?.let { reason ->
            noteContinuityRejection(r.record.dataNo, sampleMs)
            return rejectReading(r, mmol, live, reason)
        }
        val previousGlucoseAtMs = glucoseMarkBaseline(receivedAtMs, lastGlucoseAtMs)
        val freshLiveSample = live && isFreshLiveSample(receivedAtMs, sampleMs)
        if (advancesGlucoseMark(live, receivedAtMs, sampleMs, previousGlucoseAtMs)) {
            lastGlucoseAtMs = sampleMs
            lastGlucoseMmol = mmol
            lastGlucoseMgdl = mgdl
            lastRawCurrent = r.record.rawCurrent.toFloat()
        }
        rememberAcceptedReading(r, mmol, sampleMs)
        val shouldPersist = shouldPersistGlucoseSample(live, freshLiveSample, sampleMs, previousGlucoseAtMs)
        if (shouldAdvanceSeenDataNo(advancesDataNo, shouldPersist)) {
            skippedLiveRange(lastDataNo, r.record.dataNo)?.let { range ->
                addHistoryHole(range.first, range.last + 1)
                scheduleHoleRetry()
            }
            noteSeenDataNo(r.record.dataNo)
        }
        Log.i(TAG, "BG dataNo=${r.record.dataNo} mmol=%.2f mgdl=%.0f raw=%d T=%.1f".format(
            mmol, mgdl, r.record.rawCurrent, r.record.temperatureC))
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
        if (remembersRejection(mmol)) rememberRejectedReading(r, mmol)
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
        //   - streamStartTimeMs can rest on a single live frame, or on one no frame of this link
        //     has checked yet; one whose dataNo reads low puts it near "now", with the same result.
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
        val before = consecutiveCeilingFullDrops
        val after = ceilingFullDropsAfter(before, offered, kept, ceiling)
        if (after == before) return
        consecutiveCeilingFullDrops = after
        if (after == 0) return
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
        noteContinuityEvaluated(r.record.dataNo, sampleMs)
        // The excursion baseline only ever moves forward, for the same reason the adjacency
        // anchor above does. History is accepted on this path too, and a backfill can be
        // arbitrarily old: on 2026-08-07 a hole retry for [0,1) returned dataNo=0 — 7.70 mmol,
        // raw 19091, the sensor's first minute two weeks earlier — which replaced a 5.00 mmol
        // baseline. Every live sample after it then read as a 2.7 mmol one-minute excursion, and
        // 20:02 through 20:04 was refused until the yield valve re-baselined the gate.
        //
        // Freezing the baseline costs history nothing: continuity-prev is already unreachable for
        // an old record (its adjacency anchor cannot move back either), and the backward-looking
        // checks — continuity-next and historyIsolatedSpikeReason — want a baseline that is
        // NEWER than the record under test, which is exactly what this preserves.
        if (lastAcceptedDataNo >= 0 && r.record.dataNo < lastAcceptedDataNo) return
        lastAcceptedDataNo = r.record.dataNo
        lastAcceptedSampleMs = sampleMs
        lastAcceptedMmol = mmol
        lastAcceptedRawCurrent = r.record.rawCurrent
        consecutiveContinuityRejects = 0
        Applic.app?.let {
            OttaiRegistry.saveContinuityBaseline(it, SerialNumber.orEmpty(), r.record.dataNo, sampleMs, mmol, r.record.rawCurrent)
        }
    }

    /**
     * Move the continuity gate's adjacency anchor onto a sample it just refused, and count the
     * refusal towards [MAX_CONSECUTIVE_CONTINUITY_REJECTS].
     *
     * Only continuity refusals land here. Hard rejects (corrupt temperature, impossible glucose)
     * must NOT move the anchor: those frames carry garbage dataNos — 34044 and 47902 appear in
     * the 2026-07-29 trace between legitimate readings in the twenty-thousands — and adopting one
     * would push every following sample out of the adjacency window, which is the very failure
     * this anchor exists to prevent.
     */
    private fun noteContinuityRejection(dataNo: Int, sampleMs: Long) {
        consecutiveContinuityRejects++
        noteContinuityEvaluated(dataNo, sampleMs)
    }

    private fun noteContinuityEvaluated(dataNo: Int, sampleMs: Long) {
        // History replay walks backwards through records the live path already passed; letting it
        // drag the anchor back would re-open the same skip-the-gate hole from the other side.
        if (dataNo < lastEvaluatedDataNo) return
        lastEvaluatedDataNo = dataNo
        lastEvaluatedSampleMs = sampleMs
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
        // The comparison baseline only advances on an ACCEPTED sample, so once the sensor
        // genuinely steps to a new level — an electrode restart, a real discontinuity — the gate
        // would refuse every sample for the rest of the session. Yield after a bounded run and
        // let the next one re-baseline. Sustained >=1.5 mmol/min steps are not physiology, so
        // reaching this is already a sign the baseline is the thing that is wrong.
        if (consecutiveContinuityRejects >= MAX_CONSECUTIVE_CONTINUITY_REJECTS) {
            Log.w(TAG, "continuity gate yielding after $consecutiveContinuityRejects consecutive " +
                "rejections; re-baselining on dataNo=${r.record.dataNo} mmol=%.2f raw=%d".format(
                    mmol, r.record.rawCurrent))
            return null
        }
        if (isAdjacentToLastEvaluated(r.record.dataNo, sampleMs) &&
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

    /**
     * Whether this sample sits close enough behind the last sample the gate looked at for a
     * one-minute excursion test to be meaningful.
     *
     * Anchored on the last EVALUATED sample, not the last accepted one. Anchoring on the accepted
     * sample let two consecutive rejections carry the anchor out of range — dataNo gap 3 and a
     * 180 s time gap after refusing two records — so the gate silently stopped testing and passed
     * the third bad sample straight through.
     */
    private fun isAdjacentToLastEvaluated(dataNo: Int, sampleMs: Long): Boolean =
        isAdjacentSample(lastEvaluatedDataNo, lastEvaluatedSampleMs, dataNo, sampleMs)

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
            val neighborsAgree = abs(previous.mmol - next.mmol) < OttaiOutputFilter.SINGLE_SAMPLE_DELTA_MMOL
            if (neighborsAgree &&
                OttaiOutputFilter.isOneMinuteRawExcursion(candidateMmol, candidate.record.rawCurrent, previous.mmol, previous.rawCurrent) &&
                OttaiOutputFilter.isOneMinuteRawExcursion(candidateMmol, candidate.record.rawCurrent, next.mmol, next.rawCurrent)
            ) {
                return "history-isolated prev=${previous.dataNo} next=${next.dataNo}"
            }
        }

        if (!previousAdjacent && nextAdjacent && candidate.record.dataNo <= 5 &&
            OttaiOutputFilter.isOneMinuteRawExcursion(candidateMmol, candidate.record.rawCurrent, next.mmol, next.rawCurrent)
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
        // Tell the watch's ownership claim that this process decoded a live
        // reading over its own connection. Without this the claim never leaves
        // "requesting", so after a handoff the watch reads the sensor while the
        // phone, never hearing otherwise, keeps its own connection open too.
        if (live) {
            toPersist.maxByOrNull { it.sampleMs }
                ?.let { markLocalReadingAccepted(it.sampleMs) }
        }
        if (live && toPersist.size == 1) {
            val reading = toPersist.single()
            HistorySyncAccess.storeCurrentReadingAsync(reading.sampleMs, reading.mgdl, 0f, 0f, id)
        } else {
            val timestamps = LongArray(toPersist.size) { index -> toPersist[index].sampleMs }
            val values = FloatArray(toPersist.size) { index -> toPersist[index].mgdl }
            // Ottai rawCurrent is an electrode/current diagnostic, not raw glucose mg/dL.
            val rawValues = FloatArray(toPersist.size) { 0f }
            HistorySyncAccess.storeSensorHistoryBatchAsync(id, timestamps, values, rawValues)
            mirrorHistoryIntoNative(id, toPersist)
        }
        if (live) readings.lastOrNull { it.publishCurrent }?.let {
            mirrorLiveReadingIntoNative(id, it)
        }
    }

    /**
     * Room is not enough: Nightscout and the rest of the exchange paths read the native poll
     * stream, so backfill that only lands in Room is invisible to them. Every other managed
     * driver mirrors its batch; Ottai did not, which is why a sensor's stored history never
     * reached Nightscout no matter how long it had been running.
     */
    private fun mirrorHistoryIntoNative(id: String, readings: List<EmittedReading>) {
        if (readings.isEmpty()) return
        runCatching {
            ensureNativePresenceShell("history-mirror")
            val stored = nativeGlucoseMirror.mirrorHistory(
                id,
                LongArray(readings.size) { readings[it].sampleMs },
                FloatArray(readings.size) { readings[it].mgdl },
                FloatArray(readings.size) { readings[it].temperatureC },
            )
            if (stored > 0) {
                applyActivatedWearToNative(id)
                Natives.wakebackup()
                Log.i(TAG, "mirrored $stored/${readings.size} history readings into native")
            }
        }.onFailure { Log.stack(TAG, "mirrorHistoryIntoNative", it) }
    }

    private fun mirrorLiveReadingIntoNative(
        id: String,
        reading: EmittedReading,
    ) {
        runCatching {
            ensureNativePresenceShell("glucose-mirror")
            val stored = nativeGlucoseMirror.mirrorLive(
                id,
                reading.sampleMs,
                reading.mgdl,
                reading.temperatureC,
            )
            if (stored) {
                applyActivatedWearToNative(id)
                Natives.wakebackup()
                // The shell is sized and the start is known by the time a live write lands, so
                // this is the first safe moment to close whatever native is missing.
                handler.post { reconcileNativeFromRoom(id) }
            }
        }.onFailure { Log.stack(TAG, "mirrorLiveReadingIntoNative", it) }
    }

    // Once per sensor per process. See reconcileNativeFromRoom.
    @Volatile private var nativeReconciledFor: String? = null

    /**
     * Fill native poll storage from Room.
     *
     * Room is the authoritative local store and native polls are the transport cache the
     * exchange paths read, so anything Room holds and native does not is invisible to
     * Nightscout. Re-reading it from the sensor cannot recover it either:
     * [requestRoomBackfillAfterLive] diffs against Room, so a window Room already covers is
     * reported complete and never requested. That is exactly the shape of a gap left by a
     * stretch where the app was connected and storing to Room while native writes were being
     * refused — nothing else will ever close it.
     *
     * Writes landing on a poll slot that already holds a value are no-ops that cannot move the
     * Nightscout cursor (the cursor only rewinds on empty->filled), so a run with nothing
     * missing costs some writes and produces no resend.
     */
    private fun reconcileNativeFromRoom(id: String) {
        if (nativeReconciledFor == id) return
        val startMs = nativePresenceStartTimeMs()
        if (startMs <= 0L) return
        nativeReconciledFor = id
        runCatching {
            val history = HistoryRepositoryAccess.getHistoryForSensor(id, startMs, false)
            if (history == null) {
                // "Could not ask" must not be recorded as "done" — retry on the next reading.
                nativeReconciledFor = null
                return@runCatching
            }
            val usable = history.filter {
                it.timestamp > 0L && it.value.isFinite() && it.value > 0f
            }
            if (usable.isEmpty()) return@runCatching
            ensureNativePresenceShell("room-reconcile")
            val written = nativeGlucoseMirror.mirrorHistory(
                id,
                LongArray(usable.size) { usable[it].timestamp },
                FloatArray(usable.size) { usable[it].value },
                FloatArray(usable.size) { 0f },
            )
            Log.i(TAG, "reconciled native from Room for $id: wrote $written/${usable.size}")
        }.onFailure {
            nativeReconciledFor = null
            Log.stack(TAG, "reconcileNativeFromRoom", it)
        }
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
        markLocalReadingAccepted(reading.sampleMs)
        SuperGattCallback.processExternalCurrentReading(id, reading.displayValue, 0f, reading.sampleMs, SENSOR_GEN)
        Log.i(TAG, "current publish sec=${reading.sampleMs / 1000L} display=%.2f mgdl=%.1f".format(reading.displayValue, reading.mgdl))
    }

    private fun resolveSampleTimeMs(
        r: OttaiReading,
        live: Boolean,
        receivedAtMs: Long,
    ): Long {
        // History has no arrival of its own to check a date against, and while anchorDisputed
        // stands, live frames contradict the anchor or confirmed start it would be dated from: no
        // date, so emitReading stores none of it. That covers a chunk already in flight when the
        // flag was raised; continueHistoryAfterPayload then leaves a ledgered window on the hole
        // ledger.
        if (!live && anchorDisputed) return 0L
        // Which records take the anchor's date and which go back through the live path:
        // datesFromStreamAnchor. The anchor must be one history may be dated from
        // (trustedStreamStartMs).
        //
        // A new live record is deliberately NOT held to a reliable-but-unconfirmed anchor: the
        // commit in offerConfirmedActiveTime() needs a SECOND reliable anchor to corroborate the
        // first, and seedStreamTimeAnchor() below is the only place one is offered.
        // Short-circuiting as soon as a reliable anchor existed made that gate unreachable for the
        // life of the driver, so the confirmed start was never written — and with
        // materials.activeTimeMs stuck at 0 the dataNo ceiling never gained a
        // bound at all, for exactly the vendor-activated sensors it was rewritten to protect.
        // While the start is reliable-but-unconfirmed, new live records must keep flowing through
        // the anchor path.
        val confirmed = materials.activeTimeMs > 0L
        val anchorMs = trustedStreamStartMs(streamStartTimeMs, streamStartReliable, materials.activeTimeMs)
        if (anchorMs > 0L) {
            val anchoredMs = anchorMs + r.record.dataNo.toLong() * RECORD_INTERVAL_MS
            if (datesFromStreamAnchor(live, r.record.dataNo, lastDataNo, confirmed, receivedAtMs, anchoredMs)) {
                return anchoredMs
            }
        }

        if (live && receivedAtMs > 0L && r.record.dataNo >= 0) {
            // Unconfirmed, monitorTimeMs is the reliable stream anchor plus runtime, or, while this
            // link has not checked that anchor yet, the pending start claim (synthesized runtime)
            // or this app's activation-command instant (real runtime): monitorBaseStartMs. The
            // start derived from it echoes that anchor or claim. It is still a read of its own: taken only
            // when this frame's arrival agrees with it, and startsCorroborate refuses a second claim
            // from the same record. Re-dating each frame by its arrival minute instead can put two
            // consecutive records on one minute and shift the grid in the middle of a backfill.
            // Confirmed, it may anchor only onto the confirmed start (monitorMayAnchor).
            val monitorMs = r.monitorTimeMs
            if (monitorLiveStartMs(receivedAtMs, monitorMs, r.record.dataNo, materials.activeTimeMs) > 0L) {
                return seedStreamTimeAnchor(r.record.dataNo, monitorMs, "monitor-live", reliable = true)
            }
            if (monitorMs > 0L) {
                val deltaSec = kotlin.math.abs(receivedAtMs - monitorMs) / 1000L
                Log.w(TAG, "ignore stale live monitor timestamp dataNo=${r.record.dataNo} monitor=${monitorMs / 1000L} live=${receivedAtMs / 1000L} delta=${deltaSec}s")
            }
            val arrivalStartMs = floorToRecordMinute(receivedAtMs) - r.record.dataNo.toLong() * RECORD_INTERVAL_MS
            if (datesLiveByArrival(
                    confirmed, r.record.dataNo, lastDataNo,
                    pendingConfirmedStartMs, pendingConfirmedDataNo, arrivalStartMs,
                )
            ) {
                return seedStreamTimeAnchor(r.record.dataNo, receivedAtMs, "live", reliable = true)
            }
            // Confirmed: a new record is held, not dated, until a frame of another record agrees
            // with its start; a later stored live frame above it ledgers it as skipped and history
            // brings it back. The last stored record read again falls to its confirmed start below.
            if (r.record.dataNo != lastDataNo) {
                val claim = heldLiveClaim(
                    pendingConfirmedStartMs, pendingConfirmedDataNo, arrivalStartMs, r.record.dataNo,
                )
                pendingConfirmedStartMs = claim.first
                pendingConfirmedDataNo = claim.second
                // A NEW record of a confirmed sensor that the anchor, its monitor time and its
                // arrival all declined to date: this link's live frames and the confirmed start
                // disagree, and history dated from that start would be stored with the error in
                // it. Recorded here and not for the last stored record read again: a stopped
                // counter explains that frame's stale monitor and arrival as well as a wrong start
                // does — which is why dating it is refused in the first place — and once the
                // counter has been stopped for longer than the freshness margin, a flag raised on
                // such a frame has nothing left that could clear it.
                //
                // A chain already running would carry on past the requestHistoryRange gate
                // (requestPendingHistoryChunk, the watchdog retry), so it is dropped here. The
                // ledgered windows lose nothing by that: room-backfill, post-live and hole-retry
                // windows go on the hole ledger before they are issued, and dropping the chain
                // neither delivers nor fails them. The retry driver armed here asks for them once
                // the disagreement is settled. Manual and empty-live windows are not ledgered and
                // are lost.
                if (!anchorDisputed) {
                    clearPendingHistoryRange()
                    scheduleHoleRetry()
                }
                anchorDisputed = true
                Log.w(TAG, "live dataNo=${r.record.dataNo} disagrees with the confirmed anchor; " +
                    "held until another record agrees")
                return 0L
            }
        }

        // The monitor hint derives from monitorBaseStartMs, the active hint from
        // effectiveActiveTimeMs(); confirmed, both are the confirmed start. Unconfirmed, neither was
        // checked against this frame's arrival: each is this link's anchor or the pending start
        // claim echoed back, or this app's activation-command instant plus runtime (the 2026-09-22
        // too-early date). No date is better than an unchecked one: emitReading skips the record,
        // as nativePresenceStartTimeMs refuses the command instant.
        if (!confirmed) return 0L
        r.monitorTimeMs.takeIf { it > 0L }?.let { monitorMs ->
            // monitorTimeMs is activeTimeMs + runtime (OttaiParser), here from the confirmed start.
            // Unlike "monitor-live" there is no arrival check here, so the derived start must NOT be
            // offered as a corroborating anchor (reliable).
            return seedStreamTimeAnchor(r.record.dataNo, monitorMs, "monitor")
        }
        val activeMs = effectiveActiveTimeMs()
        if (activeMs > 0L) {
            return seedStreamTimeAnchor(r.record.dataNo, activeMs + r.record.runtimeSec * 1000L, "active")
        }
        return r.monitorTimeMs
    }

    // reliable = the hint was checked against this live frame's arrival: the wall clock itself
    // ("live"), or a monitor time within CURRENT_SAMPLE_FRESH_MS of it ("monitor-live"). start =
    // hint - dataNo*interval is then offered for the one-way commit (offerConfirmedActiveTime), so
    // the dashboard and native record stop showing the cgm-info "just now" provisional. Hints with
    // no arrival check (monitor/active/initial-history/ended-live) must NOT set reliable.
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
            if (reliable) offerConfirmedActiveTime(start, dataNo)
            if (effectiveActiveTimeMs() <= 0L) ensureNativePresenceShell("stream-anchor-$reason")
        }
        return sampleMs
    }

    private fun floorToRecordMinute(timestampMs: Long): Long =
        (timestampMs / RECORD_INTERVAL_MS) * RECORD_INTERVAL_MS

    /**
     * A shell created without a capacity floor is sized for the 15-day rating, and an Ottai
     * routinely runs to 28-30. Past day 15 the native poll index leaves the mapping and every
     * live write is refused, which silently cuts Nightscout off — the exact regression left
     * behind when runtime stream resizing was removed. Size it through [ensureNativeShellCapacity]
     * up front; geometry may only be chosen at creation, so once that has latched a plain
     * [ensureSensorShell] applies the start without asking native, on every reading, for a size it
     * can no longer give (pollStorageSize picks the negotiated duration up on the next
     * construction).
     */
    // This runs on every mirrored reading, so the capacity complaint below is reported once per
    // sensor and lifetime rather than once a minute. The lifetime is part of the key because the
    // readback on status 3 can correct a restored one upward (an activation finished by another
    // app), and the longer one must be judged again.
    @Volatile private var nativeCapacityCheckedFor: String? = null

    private fun ensureNativePresenceShell(reason: String) {
        OttaiRegistry.unlessReleased({ released }, Unit) {
            val id = SerialNumber ?: return
            val startMs = nativePresenceStartTimeMs()
            if (id.isBlank() || startMs <= 0L) return
            runCatching {
                val startSec = (startMs / 1000L).coerceAtLeast(1L)
                if (!ensureNativeShellCapacity(id, startSec)) {
                    Natives.ensureSensorShell(id, startSec)
                }
                Natives.setSensorManagedFamily(id, ManagedSensorUiFamily.OTTAI.nativeCode)
                applyActivatedWearToNative(id)
                // Judge the mapping against the lifetime itself, not the spare day a new shell is asked
                // for: a 28-day shell rebuilt from wearduration2 at 40320 passes, though with no spare a
                // start offset of a minute can still cost its final record. Until the lifetime is known
                // there is nothing to judge; a later reading checks it.
                val lifetimeRecords = OttaiConstants.nativeLifetimeRecords(activatedMaxActiveMs)
                val checkKey = "$id/$lifetimeRecords"
                if (lifetimeRecords > 0 && nativeCapacityCheckedFor != checkKey) {
                    if (!Natives.hasSensorStreamCapacity(id, lifetimeRecords)) {
                        Log.e(TAG, "native poll capacity below $lifetimeRecords for $id ($reason); " +
                            "live readings past the mapped window will not reach Nightscout")
                    }
                    nativeCapacityCheckedFor = checkKey
                }
            }.onFailure { Log.stack(TAG, "ensureNativePresenceShell($reason)", it) }
        }
    }

    /** Accepted maxActive in whole days (rounded), or 0 if unknown. */
    private fun activatedLifetimeDays(): Int {
        val ms = activatedMaxActiveMs
        return if (ms <= 0L) 0 else ((ms + 43_200_000L) / 86_400_000L).toInt()
    }

    /**
     * Push the accepted lifetime to the native sensor record (main-graph end) — the same value
     * getOfficialEndMs() reports. Never the cloud rating: native getmaxtime() takes the longer of
     * the shell geometry and wearduration2, and past it checkinfo() can mark the shell finished,
     * so a shorter cloud value could retire a sensor the firmware still runs.
     */
    private fun applyActivatedWearToNative(id: String) {
        OttaiRegistry.unlessReleased({ released }, Unit) {
            val days = activatedLifetimeDays()
            if (days <= 0 || id.isBlank()) return
            val startMs = nativePresenceStartTimeMs()
            if (!OttaiConstants.wearUpdateMayTouchNativeShell(startMs, nativeShellSizedFor == id)) {
                Log.i(TAG, "wear days deferred — no native start yet, shell would pin maxActive-accept time")
                return
            }
            if (nativeShellSizedFor != id) {
                ensureNativeShellCapacity(id, (startMs / 1000L).coerceAtLeast(1L))
            }
            runCatching { Natives.setSensorWearDays(id, days) }
                .onFailure { Log.stack(TAG, "setSensorWearDays", it) }
        }
    }

    // Per process, by id. It latches once native returns a shell, whether created at the requested
    // size or already there smaller: an open mapping is never resized (Sensoren::ensureDirectStreamShell
    // only logs the shortfall, on every call), so asking again would buy nothing. Do not wait for
    // hasSensorStreamCapacity here, or a short shell is re-requested on every reading. A failed call
    // (native not loaded yet, a JNI throw) latches nothing and the next caller retries.
    @Volatile private var nativeShellSizedFor: String? = null

    /**
     * Any native call on an unknown id creates the shell, and setSensorWearDays and the stream
     * writes create it at the 15-day default geometry. On 2026-09-22 the activation-accept
     * setSensorWearDays created a 28-day sensor's shell at 21600 records two minutes before
     * [ensureNativePresenceShell] asked for 43200; from day 15 every live write would have been
     * refused until the app restarted. Run this right before those calls so whichever lands first
     * sizes the shell ([OttaiConstants.nativeShellRecords]). Returns true when native returned a
     * shell.
     *
     * Start 0 offers no start, yet a shell created here still gets one: initInfoFile stamps the
     * creation instant, and native only ever lowers a start afterwards, so a later
     * [ensureNativePresenceShell] can move it earlier but never later. Keep this call immediately
     * before the calls it pre-empts and never earlier: an earlier creation pins an earlier native
     * start and adds its minutes to every record's poll index.
     */
    private fun ensureNativeShellCapacity(id: String, startSec: Long = 0L): Boolean {
        if (id.isBlank() || nativeShellSizedFor == id) return false
        return runCatching {
            val minimumRecords = OttaiConstants.nativeShellRecords(activatedMaxActiveMs, materials.activeExpireTimeMs)
            val shell = Natives.ensureSensorShellWithCapacity(id, startSec, minimumRecords)
            if (shell != 0L) {
                nativeShellSizedFor = id
            }
            shell != 0L
        }.onFailure { Log.stack(TAG, "ensureNativeShellCapacity", it) }.getOrDefault(false)
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
        val advanced = advancedActivateRequested()
        if (!OttaiConstants.commandNeedsActivation(commandStatus)) {
            Log.w(TAG, "activation refused — commandStatus=$commandStatus")
            return false
        }
        if (activateCommandIssued && !advanced) {
            Log.i(TAG, "activation request ignored — 0x03 already issued")
            activationInFlight = false
            return true
        }
        if (OttaiConstants.shouldIgnoreAlreadyStartedActivation(
                forceActivation = force,
                advancedActivate = advanced,
                hasOfficialStart = effectiveActiveTimeMs() > 0L || activationCommandSentAtMs > 0L,
            )
        ) {
            Log.i(TAG, "activation request ignored — command already sent/start time known")
            activationInFlight = false
            return true
        }
        if (force || advanced) Log.i(TAG, "FORCE/Advanced activation (bypassing already-started guard)")
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
        activationInFlight = false
        constatstatusstr = appString(R.string.ottai_status_activation_failed, "Activation failed")
        UiRefreshBus.requestStatusRefresh()
    }

    private fun startActivationWrites(gatt: BluetoothGatt) {
        val advanced = advancedActivateRequested()
        if (!OttaiConstants.mayEnterActivationWrites(commandStatus, activateCommandIssued, advanced)) {
            Log.w(TAG, "activation writes refused — commandStatus=$commandStatus issued=$activateCommandIssued")
            activationInFlight = false
            if (commandStatus == 3) {
                resetActivationNegotiation()
            } else if (!activateCommandIssued) {
                failActivation("command status $commandStatus does not need activation")
            }
            return
        }
        if (OttaiConstants.shouldSkipToPostLifetimeWrites(activateCommandIssued, activatedMaxActiveMs > 0L)) {
            Log.i(
                TAG,
                "maxActive already accepted (${activatedMaxActiveMs / 1000L}s) — " +
                    "resuming at destruction, not rewriting the lifetime",
            )
            activationRetryPending = false
            activationFailed = false
            actStep = ActStep.DESTRUCTION
            writeDestructionTime(gatt)
            return
        }
        if (OttaiConstants.shouldBeginActivationNegotiation(
                activationNegotiationActive,
                maxActiveCandidatesMs.isEmpty(),
            )
        ) {
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
            // The warmup gate's start after a restart. Only for a sensor that reported it needed
            // activation: a forced re-activation of a running one (status 3) is not its start.
            if (OttaiConstants.commandNeedsActivation(commandStatus)) {
                OttaiRegistry.saveActivationCommandAt(ctx, id, now)
            }
            if (materials.activeTimeMs <= 0L) {
                setProvisionalActiveTime(now, "activation-command")
            }
        }
        Log.i(TAG, "activation command sent; sensor accepted activation writes")
        // Confirm the activation took, and issue nothing else. Android allows one outstanding
        // GATT operation per connection, and the old fixed-delay chain (cgm-info at +1 s, a live
        // read at +2 s, the confirmation at +3 s) raced itself: on 2026-07-29 the confirmation
        // came back started=false while the live read was still in flight, and status=3 was only
        // seen after that read died with status=133 and forced a reconnect. The live read was
        // worthless anyway — two seconds after activation the sensor has nothing to give
        // ("live read no records payloadLen=8"). Once status=3 lands, handleCommandStatus ->
        // startStreamingAfterCommandStatus() does the cgm-info read, the first live read and the
        // poll schedule, in an order that does not collide.
        activationConfirmAttempt = 0
        handler.removeCallbacks(activationConfirmRunnable)
        handler.postDelayed(activationConfirmRunnable, POST_ACTIVATION_CONFIRM_DELAY_MS)
        UiRefreshBus.requestStatusRefresh()
    }

    private fun writeRtc(gatt: BluetoothGatt) {
        when (writeChar(
            gatt,
            OttaiConstants.SERVICE_DEVICE_INFO,
            OttaiConstants.CHAR_CURRENT_TIME,
            rtcPayload(System.currentTimeMillis()),
        )) {
            GattWriteIssue.Issued -> Unit
            GattWriteIssue.Missing,
            GattWriteIssue.Rejected -> failActivation("RTC write was not accepted")
        }
    }

    private fun writeMaxActiveTime(gatt: BluetoothGatt) {
        // First-use writes the app lifetime ladder (30d down to 15d), then the cloud
        // rating if it is distinct. Official cloud default is 14–15 days; hardware
        // accepts a longer maxActive, so the longer values go first. Payload shape is
        // still official: p.U(p.w0(duration/1000), sessionKey) — AES/ECB one block.
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
        val payload = maxActivePayload(expireMs, sessionKeyHex)
            ?: run {
                failActivation("maxActive encryption failed")
                return
            }
        when (writeChar(gatt, OttaiConstants.SERVICE_DEVICE_INFO, OttaiConstants.CHAR_MAX_ACTIVE_TIME, payload)) {
            GattWriteIssue.Missing -> {
                Log.w(TAG, "maxActive char absent — skipping to destruction")
                advanceActivation(gatt)
            }
            GattWriteIssue.Rejected -> failActivation("maxActive write was not accepted")
            GattWriteIssue.Issued -> Unit
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
        armNfcActivationWake("maxActive was rejected before the next activation attempt")
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
        activationDiscoveryStartedAtMs = System.currentTimeMillis()
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
        armNfcActivationWake("activation retry is waiting for a fresh advertisement")
        SensorBluetooth.blueone?.scanStarter(250L)
        UiRefreshBus.requestStatusRefresh()
    }

    private fun markMaxActiveAccepted() {
        val acceptedMs = maxActiveAttemptMs
        if (acceptedMs <= 0L) return
        activationRetryPending = false
        pendingAcceptedMaxActiveMs = acceptedMs
        Log.i(TAG, "maxActive accepted duration=${acceptedMs / 1000L}s; staged until status=3")
        // Write through now rather than only at status=3. The firmware has already ACKed the
        // value, so it is a fact about the sensor whether or not the activate command that
        // follows lands. Deferring it lost the negotiated lifetime on 2026-07-29: a status=133
        // read error between the activate write and the confirmation tore the link down,
        // clearGattTransport cleared the staged state, and commitStagedActivationLifetime then
        // committed nothing — the 28 days survived only because the maxActive readback, then an
        // optional probe, happened to succeed one second later. If activation ultimately fails, this is what
        // this app wrote, not necessarily what the sensor ends up holding; the readback on the next
        // status 3 (startStreamingAfterCommandStatus) corrects it.
        adoptActivatedMaxActive(acceptedMs, "activation-accept")
        UiRefreshBus.requestStatusRefresh()
    }

    private fun commitStagedActivationLifetime(status: Int) {
        val acceptedMs = acceptedMaxActiveToCommit(
            status,
            activationCommandAcknowledged,
            pendingAcceptedMaxActiveMs,
        )
        if (acceptedMs <= 0L) return
        adoptActivatedMaxActive(acceptedMs, "activation-commit")
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
        val payload = destructionPayload(materials.retainTimeMs)
        when (writeChar(gatt, OttaiConstants.SERVICE_DESTRUCTIVE, OttaiConstants.CHAR_DESTRUCTIVE, payload)) {
            GattWriteIssue.Issued -> Unit
            GattWriteIssue.Missing,
            GattWriteIssue.Rejected -> failActivation("destruction write was not accepted")
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
        val issue = writeChar(gatt, OttaiConstants.SERVICE_CGM, OttaiConstants.CHAR_COMMAND, cmd)
        when (issue) {
            GattWriteIssue.Missing,
            GattWriteIssue.Rejected -> {
                failActivation("activation command write was not accepted")
                return
            }
            GattWriteIssue.Issued -> {
                activateCommandIssued = true
                activationInFlight = false
                if (activateRequestedFor?.let { matchesManagedSensorId(it) } == true) {
                    activateRequestedFor = null
                }
                if (advancedActivateRequestedFor?.let { matchesManagedSensorId(it) } == true) {
                    advancedActivateRequestedFor = null
                }
                val id = SerialNumber.orEmpty()
                val ctx = Applic.app
                if (ctx != null && id.isNotBlank()) {
                    OttaiRegistry.saveActivateCommandIssued(ctx, id, true)
                }
            }
        }
        // Warmup PREF / restoredActivationCommandAtMs may stamp only once writeCharacteristic
        // accepted the 0x03 (Issued). Hardware can still drop the ATT ACK with status 133
        // while the sensor starts; this stamp keeps the settling ramp unpublished.
        // markActivationCommandSent / activationCommandSentAtMs stay on GATT ACK only.
        // Do not retry 0x03 on lost ACK.
        if (shouldStampWarmupPrefOnActivateIssue(issue, OttaiConstants.commandNeedsActivation(commandStatus))) {
            val now = System.currentTimeMillis()
            restoredActivationCommandAtMs = now
            val id = SerialNumber.orEmpty()
            val ctx = Applic.app
            if (ctx != null && id.isNotBlank()) OttaiRegistry.saveActivationCommandAt(ctx, id, now)
        }
    }

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
        // Nothing to fetch yet on a sensor we have only just started. Come back when it is old
        // enough to have history rather than spending the bounded attempts on empty live reads.
        val sensorAgeMs = warmupAnchorMs().takeIf { it > 0L }?.let { System.currentTimeMillis() - it } ?: -1L
        if (sensorAgeMs in 0 until INITIAL_HISTORY_MIN_SENSOR_AGE_MS) {
            val waitMs = INITIAL_HISTORY_MIN_SENSOR_AGE_MS - sensorAgeMs
            Log.i(TAG, "initial history backfill deferred ${waitMs / 1000L}s — sensor is ${sensorAgeMs / 1000L}s old")
            handler.removeCallbacks(initialHistoryRunnable)
            handler.postDelayed(initialHistoryRunnable, waitMs)
            return
        }
        val estimate = estimatedNewestDataNo()
        val newest = maxOf(lastDataNo, estimate)
        // No trusted start is no basis either: history fetched now could not be dated, and nor is
        // a start this link's live frames have contradicted (anchorDisputed) — the seed below would
        // hand that start's error to the whole backfill. The live read taken instead brings the
        // dataNo and the anchor. Read again, the held record cannot pair with its own claim
        // (datesLiveByArrival): unless the clocks come back into agreement, a disagreement
        // waits for a frame of a later record, a minute or more on and often past these
        // attempts; the live path takes the backfill over once a live frame is emitted.
        if (newest <= 0 || nativePresenceStartTimeMs() <= 0L || anchorDisputed) {
            // No basis yet (activation time unknown and no sample). Nudge a live read to learn
            // the current dataNo, then retry a bounded number of times.
            if (initialHistoryAttempt < INITIAL_HISTORY_MAX_ATTEMPTS) {
                initialHistoryAttempt++
                mBluetoothGatt?.let { g -> runCatching { readLiveGlucose(g, "initial-history-probe") } }
                handler.postDelayed(initialHistoryRunnable, INITIAL_HISTORY_RETRY_MS)
            } else {
                Log.w(TAG, "initial history backfill gave up after $initialHistoryAttempt attempts — " +
                    "newest=$newest trustedStart=${nativePresenceStartTimeMs() > 0L} disputed=$anchorDisputed")
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
        ).issued
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
    ): BackfillOutcome {
        if (roomBackfillChecked || liveDataNo <= 0) return BackfillOutcome.NOT_ISSUED
        val id = SerialNumber ?: return BackfillOutcome.NOT_ISSUED
        // The one-shot is consumed only by an actually-issued request or a trusted "nothing
        // missing" verdict — an early-out on a no-op (stale basis, missing anchor, failed
        // issue) must leave it clear so the live path can still do the real check.
        if (!shouldDiffStoredHistory(previousDataNo, historyDiffRetryPending)) {
            val missingBeforeLive = liveDataNo - previousDataNo - 1
            if (missingBeforeLive <= 0) {
                if (observedNewest) roomBackfillChecked = true
                Log.i(TAG, "skip history reason=room-backfill previous=$previousDataNo live=$liveDataNo observed=$observedNewest")
                return BackfillOutcome.NOT_ISSUED
            }
            return BackfillOutcome(
                issued = requestHistoryRange("room-backfill", previousDataNo + 1, missingBeforeLive)
                    .also { if (it) roomBackfillChecked = true },
                diffOwnsRecovery = false,
            )
        }
        // From here on the diff owns recovery for this payload — see BackfillOutcome. Stored rows
        // are mapped back to dataNo through this anchor, so it has to be one history is dated from:
        // an untrusted one marks records present that were never requested, and later sessions do
        // not diff again.
        val startMs = trustedStreamStartMs(streamStartTimeMs, streamStartReliable, materials.activeTimeMs)
            .takeIf { it > 0L && !anchorDisputed } ?: run {
            historyDiffRetryPending = true
            Log.w(TAG, "history diff has no trusted stream anchor — deferring backfill live=$liveDataNo")
            return BackfillOutcome(issued = false, diffOwnsRecovery = true)
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
            return BackfillOutcome(issued = false, diffOwnsRecovery = true)
        }
        historyDiffRetryPending = false
        if (existing.isEmpty()) {
            return BackfillOutcome(
                issued = requestHistoryRange("room-backfill", 0, liveDataNo)
                    .also { if (it) roomBackfillChecked = true },
                diffOwnsRecovery = true,
            )
        }
        val gaps = missingRanges(presentFromTimestamps(existing, startMs, liveDataNo))
        if (gaps.isEmpty()) {
            roomBackfillChecked = true // verified complete against Room = trusted
            return BackfillOutcome(issued = false, diffOwnsRecovery = true)
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
        return BackfillOutcome(issued = issued, diffOwnsRecovery = true)
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
        // History has no arrival time of its own. Asked for without a trusted start (the rule
        // nativePresenceStartTimeMs applies) its records could only be skipped on arrival, and an
        // arrived window leaves the hole ledger as if it had been stored. The ledger's driver keeps
        // ticking meanwhile, so its windows are asked for once the link has a trusted start. A
        // start this link's live frames have contradicted (anchorDisputed) is refused too: while it
        // stands resolveSampleTimeMs gives history no date, so asking for those records now could
        // not store any of them.
        if (nativePresenceStartTimeMs() <= 0L || anchorDisputed) {
            val why = if (anchorDisputed) "live frames disagree with the confirmed start" else "no trusted start"
            Log.i(TAG, "history request deferred reason=$reason start=$start count=$count — $why")
            scheduleHoleRetry(livePollIntervalMs)
            return false
        }
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
        if (issued is GattWriteIssue.Issued) {
            lastHistoryRequestAtMs = now
            // A watchdog retry re-issues the identical window and must keep its progress marker;
            // any other window is a fresh one and starts with nothing delivered.
            synchronized(historyChunkLock) {
                if (!keepsChunkBest(activeHistoryStart, activeHistoryEndExclusive, start, start + count)) {
                    historyChunkBestDataNo = -1
                    historyChunkSeenDataNos.clear()
                }
                activeHistoryStart = start
                activeHistoryEndExclusive = start + count
            }
            armHistoryWatchdog()
            Log.i(TAG, "request history reason=$reason start=$start count=$count payload=${OttaiCrypto.bytesToHex(payload)}")
        }
        return issued is GattWriteIssue.Issued
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
        // While anchorDisputed stands, resolveSampleTimeMs gives history no date, so this payload
        // stored nothing; counted as delivered below, its window would leave the hole ledger
        // without its records. Raising the flag dropped the running chain, but a request the
        // handler thread issued at that same moment can outlive that teardown, so it is dropped
        // here, on the first payload that brings records. The flag is written on the BLE callback
        // thread that runs this too, so the value read here is the one this payload's records
        // were refused a date on.
        if (anchorDisputed) {
            clearPendingHistoryRange()
            scheduleHoleRetry()
            return false
        }
        val activeEndExclusive = activeHistoryEndExclusive
        val windowStart = activeHistoryStart
        if (activeEndExclusive <= 0) return pendingHistoryReason != null
        // Records of this window were skipped for want of a usable date — no anchor, or dated
        // ahead of their own arrival after a clock step — so the window did not store what it
        // decoded, and the delivery accounting below counts DECODED records, which cannot tell the
        // difference: it would trim the window off the hole ledger with none of those records in
        // Room, and with roomBackfillChecked latched for the session they would never be asked for
        // again. Handled like the empty response in handleGlucosePayload: this window yielded
        // nothing, the chain moves on to the next chunk, and the ledger keeps the window for the
        // retry driver. Deliberately NO noteHoleFailure — the records exist and the wrong date is
        // this side's, so spending an attempt would retire the window and lose exactly what this
        // protects. Below the early return on purpose: a late frame answering no window in flight
        // (a slow tail after advanceHistoryChunkChain) must not tear down a healthy chain, and
        // unlike anchorDisputed this condition does not stop requestHistoryRange from issuing.
        if (!payloadDelivered(undatedSkips)) {
            Log.w(TAG, "history payload left $undatedSkips record(s) undated — window stays on the hole ledger")
            if (!advanceHistoryChunkChain(windowStart, activeEndExclusive)) {
                return activeHistoryEndExclusive > 0 || pendingHistoryReason != null
            }
            cancelHistoryWatchdog()
            historyRetryCount = 0
            return false
        }
        // Callers pass the ceiling-filtered plausible list: only plausible progress resets the
        // stall budget or completes the window, so a corrupt frame can neither starve the
        // watchdog bound nor mark the chunk done. "Progress" means further than this chunk has
        // ever reached — a frame that only repeats records already delivered leaves the budget
        // alone, so a window whose tail never decodes runs out of retries instead of forever.
        val maxDataNo = readings.maxOfOrNull { it.record.dataNo } ?: return false
        val dataNos = readings.map { it.record.dataNo }
        val absorb = synchronized(historyChunkLock) {
            val outcome = absorbHistoryChunkPayload(
                activeHistoryStart,
                activeHistoryEndExclusive,
                windowStart,
                activeEndExclusive,
                historyChunkSeenDataNos,
                dataNos,
            )
            when (outcome) {
                HistoryChunkAbsorb.STALE -> Unit
                HistoryChunkAbsorb.INCOMPLETE -> {
                    historyRetryCount = historyRetryCountAfterAbsorb(
                        outcome,
                        historyRetryCount,
                        maxDataNo,
                        historyChunkBestDataNo,
                    )
                    if (maxDataNo > historyChunkBestDataNo) historyChunkBestDataNo = maxDataNo
                }
                HistoryChunkAbsorb.COMPLETED -> {
                    if (maxDataNo > historyChunkBestDataNo) historyChunkBestDataNo = maxDataNo
                }
            }
            outcome
        }
        if (absorb == HistoryChunkAbsorb.STALE) {
            // A newer request already owns the in-flight window. Leave its seen-set and bounds.
            return activeHistoryEndExclusive > 0 || pendingHistoryReason != null
        }
        if (absorb == HistoryChunkAbsorb.INCOMPLETE) {
            // A notify that reaches the chunk end while a middle dataNo never arrived must not
            // retire the window. The stall timer retries; the attempt cap is what gives up.
            armHistoryWatchdog()
            return true
        }
        // Every dataNo in the window arrived — the only event that shrinks the ledger.
        // Trim and cancel while the window is still in flight. Zeroing first lets a hole
        // retry treat the chain as idle and put the window back, and it lets a watchdog
        // retry arm a timer that this cancel would then remove.
        trimHistoryHoles(windowStart, activeEndExclusive)
        cancelHistoryWatchdog()
        synchronized(historyChunkLock) {
            val resetRetryCount = coveredChunkResetsRetryCount(
                activeHistoryStart,
                activeHistoryEndExclusive,
                windowStart,
                activeEndExclusive,
            )
            if (activeHistoryStart == windowStart && activeHistoryEndExclusive == activeEndExclusive) {
                activeHistoryEndExclusive = -1
                activeHistoryStart = -1
            }
            if (resetRetryCount) {
                historyRetryCount = historyRetryCountAfterAbsorb(
                    HistoryChunkAbsorb.COMPLETED,
                    historyRetryCount,
                    maxDataNo,
                    historyChunkBestDataNo,
                )
            }
        }
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

    /**
     * Set the in-flight chunk to "done" and either fire the next pending chunk or finish.
     * A no-op when the in-flight window is no longer [expectedStart, expectedEnd): a history
     * notify and the watchdog overlap, and retiring here would clear the newer chunk.
     */
    private fun advanceHistoryChunkChain(expectedStart: Int, expectedEnd: Int): Boolean {
        val retired = synchronized(historyChunkLock) {
            if (activeHistoryStart != expectedStart || activeHistoryEndExclusive != expectedEnd) {
                false
            } else {
                historyChunkSeenDataNos.clear()
                activeHistoryEndExclusive = -1
                activeHistoryStart = -1
                true
            }
        }
        if (!retired) return false
        if (pendingHistoryReason == null) {
            scheduleHoleRetry() // chain drained — let the ledger pick up any recorded misses
            return true
        }
        handler.removeCallbacks(pendingHistoryChunkRunnable)
        handler.postDelayed(pendingHistoryChunkRunnable, HISTORY_CHUNK_DELAY_MS)
        return true
    }

    private fun checkHistoryWatchdog() {
        if (stop || phase != Phase.STREAMING || sessionKeyHex.isBlank()) return
        val end = activeHistoryEndExclusive
        val start = activeHistoryStart
        when (historyWatchdogAction(start, end, historyRetryCount)) {
            HistoryWatchdogAction.NONE -> Unit // nothing in flight
            HistoryWatchdogAction.SKIP -> {
                if (advanceHistoryChunkChain(start, end)) historyRetryCount = 0
            }
            HistoryWatchdogAction.RETRY -> {
                historyRetryCount++
                Log.w(TAG, "history page watchdog: no progress for chunk [$start,$end) — retry $historyRetryCount/$HISTORY_MAX_RETRIES")
                // Re-issue the same window (issueHistoryRequest re-arms the watchdog on success).
                if (!issueHistoryRequest("watchdog-retry", start, end - start, bypassCooldown = true)) {
                    if (advanceHistoryChunkChain(start, end)) noteHoleFailure(start, end)
                }
            }
            HistoryWatchdogAction.GIVE_UP -> {
                if (advanceHistoryChunkChain(start, end)) {
                    Log.e(TAG, "history page watchdog: chunk [$start,$end) failed after $historyRetryCount retries — " +
                        "recorded in the hole ledger for a later retry")
                    historyRetryCount = 0
                    noteHoleFailure(start, end)
                }
            }
        }
    }

    /** Percent of the running backfill, or -1 when nothing is being fetched. */
    private fun historyBackfillPercentNow(): Int =
        historyBackfillPercent(historyChainStart, pendingHistoryNextStart, pendingHistoryEndExclusive)

    private fun clearPendingHistoryRange() {
        handler.removeCallbacks(pendingHistoryChunkRunnable)
        cancelHistoryWatchdog()
        historyRetryCount = 0
        synchronized(historyChunkLock) {
            historyChunkBestDataNo = -1
            historyChunkSeenDataNos.clear()
            activeHistoryEndExclusive = -1
            activeHistoryStart = -1
        }
        pendingHistoryReason = null
        pendingHistoryNextStart = 0
        pendingHistoryEndExclusive = 0
        historyChainStart = -1
        UiRefreshBus.requestStatusRefresh()
    }

    // ---- history hole ledger ----
    // Requested-but-undelivered windows. Added at request time for detected gaps, trimmed only
    // when their data actually arrives, retired by the cross-session attempt cap. Survives
    // disconnects and app restarts via OttaiRegistry (per-sensor pref).

    /**
     * Put a hole the live path just revealed on the ledger, whatever the one-shot thinks.
     *
     * [roomBackfillChecked] guards the *initial* whole-history reconciliation, which is
     * expensive and belongs once per connection. A hole that opens mid-session is a different
     * and bounded thing, and gating it behind that latch is why a 16-minute dropout on a link
     * that never dropped stayed missing for the remaining eleven hours of the session.
     */
    private fun ledgerLiveGap(previousDataNo: Int, liveDataNo: Int) {
        val gap = liveGapRange(previousDataNo, liveDataNo, dataNoCeiling(live = true)) ?: return
        Log.i(TAG, "live gap ledgered ${gap.start}..<${gap.endExclusive} previous=$previousDataNo live=$liveDataNo")
        addHistoryHole(gap.start, gap.endExclusive)
        scheduleHoleRetry()
    }

    private fun addHistoryHole(start: Int, endExclusive: Int) {
        if (endExclusive <= start || start < 0) return
        synchronized(historyHolesLock) {
            val dropped = ledgerAdd(historyHoles, start, endExclusive) ?: return // covered
            for (h in dropped) {
                Log.e(TAG, "history hole ledger full — dropping [${h.start},${h.endExclusive}) (records lost)")
            }
            persistHistoryHoles()
        }
    }

    /** Data for [start,endExclusive) actually arrived — the ONLY way a hole shrinks. */
    private fun trimHistoryHoles(start: Int, endExclusive: Int) {
        if (endExclusive <= start) return
        synchronized(historyHolesLock) {
            if (ledgerTrim(historyHoles, start, endExclusive)) persistHistoryHoles()
        }
    }

    /** A request covering [start,endExclusive) failed to deliver — ledger it and bump its counter. */
    private fun noteHoleFailure(start: Int, endExclusive: Int) {
        if (endExclusive <= start || start < 0) return
        synchronized(historyHolesLock) {
            for (h in ledgerFail(historyHoles, start, endExclusive)) {
                Log.e(TAG, "history range [${h.start},${h.endExclusive}) undelivered after ${h.attempts} attempts — giving up")
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

    private fun scheduleRssiPoll(delayMs: Long = RSSI_POLL_INTERVAL_MS) {
        if (stop || phase != Phase.STREAMING) return
        handler.removeCallbacks(rssiPollRunnable)
        handler.postDelayed(rssiPollRunnable, delayMs)
    }

    override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
        super.onReadRemoteRssi(gatt, rssi, status)
        if (stop || gatt !== mBluetoothGatt) return
        if (status != 0) {
            logi(TAG) { "rssi read failed status=$status" }
            return
        }
        // Deliberately NOT noteGattActivity(): readRemoteRssi is HCI_Read_RSSI answered by the
        // local controller from the last packet it saw, so the peer takes no part in it and a
        // successful read is no evidence the sensor is still answering. Counting it as liveness
        // would push worst-case detection of a wedged ATT path in STREAMING from 195s to ~390s.
        logi(TAG) { "rssi=$rssi phase=$phase" }
    }

    /**
     * Queue a GATT write. [GattWriteIssue.Missing] if the characteristic is absent,
     * [GattWriteIssue.Rejected] if writeCharacteristic returns false or throws,
     * [GattWriteIssue.Issued] only when the stack accepted the write.
     */
    @Suppress("DEPRECATION")
    private fun writeChar(gatt: BluetoothGatt, svc: UUID, ch: UUID, value: ByteArray): GattWriteIssue {
        val c = gatt.getService(svc)?.getCharacteristic(ch)
        if (c == null) {
            Log.w(TAG, "writeChar missing $ch")
            return interpretGattWrite(characteristicPresent = false, writeAccepted = null)
        }
        c.value = value
        // Match the official app (a.java d()): use the characteristic's supported write
        // type. The post-CCCD Invalid-Handle we saw was on an EXPIRED unit locking its
        // control chars; a no-response Write Command gave no ATT feedback and didn't
        // un-expire it, so this stays with the official's proven with-response form.
        c.writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        Log.i(TAG, "write ${ch.toString().take(8)}#${c.instanceId} props=0x${c.properties.toString(16)} wt=${c.writeType} len=${value.size}")
        val accepted = runCatching { gatt.writeCharacteristic(c) }.getOrNull()
        val issue = interpretGattWrite(characteristicPresent = true, writeAccepted = accepted)
        if (issue is GattWriteIssue.Rejected) {
            Log.w(TAG, "writeChar rejected $ch accepted=$accepted")
        }
        return issue
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
    /**
     * Official end of this sensor's life: effective activation + [OttaiConstants.expectedLifetimeMs].
     * The effective activation lets a sensor started in the vendor app (no cloud activeTime, start
     * recovered over BLE) still show an end instead of a blank. The sensor card, the countdown
     * ring (via [getExpectedEndMs]) and — because the Ottai adapter hands its callbacks dataptr 0
     * — the expiry warnings and the handover read this value.
     *
     * Once the firmware has accepted a maxActive at activation, THAT is the official end: the
     * cloud's activeExpireTime is only what the account plan rates the sensor at, and this fork
     * deliberately writes the firmware value above it. Reporting the cloud figure here made the
     * sensor card disagree with its own countdown ring by two weeks and aimed every expiry warning
     * at the rated end of a sensor provisioned for longer — false alarms, then a working sensor
     * read as expired. The firmware enforces exactly the value it accepted (see
     * OttaiConstants.expectedLifetimeMs for the field evidence).
     *
     * Deliberately fixed here rather than in the shared alert path: other managed drivers use
     * expectedEndMs for an ADVISORY horizon that is longer than their rated life (iCan defaults
     * it to 28 days for every profile, including 7- and 8-day units), so a shared rule would
     * have suppressed their genuine warnings.
     */
    override fun getOfficialEndMs(): Long {
        val start = effectiveActiveTimeMs()
        if (start <= 0L) return 0L
        val acceptedMs = activatedMaxActiveMs.takeIf { it > 0L }
            ?: Applic.app?.let { OttaiRegistry.loadAcceptedMaxActive(it, SerialNumber.orEmpty()) }
            ?: 0L
        return start + OttaiConstants.expectedLifetimeMs(materials.activeExpireTimeMs, acceptedMs)
    }
    // The ring must not promise more than the warnings aim at: one horizon for both.
    override fun getExpectedEndMs(): Long = getOfficialEndMs()
    // Never expire on the calendar alone: past the reported end, keep reading while
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
