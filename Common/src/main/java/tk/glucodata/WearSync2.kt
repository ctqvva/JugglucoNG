package tk.glucodata

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import tk.glucodata.Log.doLog

/**
 * Wear Sync v2: the plan-of-record replacement for the legacy mirror tunnel.
 * One mechanism carries live values and history backfill in either direction:
 * the receiver asks for everything since a timestamp, the current owner answers
 * with ordered [time, auto*10, raw*10] chunks, and the receiver writes them
 * through its idempotent history path. Tail pushes use the same wire format, so
 * repeats are harmless and there is no session state to corrupt.
 *
 * Wire format (big-endian), version 1:
 *  request  (receiver→owner, SYNC2_REQ_PATH):  [u8 ver][i64 fromSec]
 *  chunk    (owner→receiver, SYNC2_CHUNK_PATH):
 *   [u8 ver][u8 flags bit0=final][u16 count][u8 serialLen][serial utf8]
 *   [count × (i64 timeSec, i32 auto10, i32 raw10)]
 *  calibration (phone→watch, SYNC2_CAL_PATH): see [WearCalibrationPayload].
 *
 * The wire carries source readings. The calibration payload supplies both mode
 * configurations and the phone's fitting unit so the watch can reproduce the
 * same unit-sensitive calculation rather than applying a second approximation.
 */
object WearSync2 {
    private const val LOG_ID = "WearSync2"

    /** Bounds the message volume when several sensors are running at once. */
    private const val MAX_SERVED_SENSORS = 4
    private const val VERSION = 1
    private const val MAX_TRIPLES_PER_CHUNK = 360 // ~8.6KB; smaller messages survive better
    private const val TAIL_TRIPLES = 8
    private const val PUSH_THROTTLE_MS = 45_000L
    // A sensor runs for weeks; a 24h horizon meant the watch could never show
    // more than the last day no matter how often it synced.
    private const val BACKFILL_HORIZON_SEC = 14L * 24L * 3600L
    private const val INCREMENTAL_OVERLAP_SEC = 15L * 60L
    private const val REMOVAL_TOMBSTONE_MS = 10L * 60L * 1000L
    // A full horizon is ~9000 readings in 26 messages. It is worth sending when
    // the watch may be missing history, and pure waste otherwise — it was being
    // re-sent on every app open and every calibration edit.
    private const val DEEP_SERVE_MIN_INTERVAL_MS = 30L * 60L * 1000L
    private const val MGDL_PER_MMOL = 18.0182

    /** Context the arrow needs; matches what the display surfaces use. */
    private const val TREND_WINDOW_MS = 35L * 60L * 1000L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WearSync2").apply { isDaemon = true }
    }
    private val lastPushMs = AtomicLong(0L)
    private val lastServedMs = AtomicLong(0L)
    private val lastServedChunkCount = AtomicLong(0L)
    private val calibrationWireRevision = AtomicLong(0L)
    private val lastDeepServeMs = AtomicLong(0L)
    private val removalTombstones = ConcurrentHashMap<String, Long>()

    // ---- phone side ----

    /** Called from the reading dispatch path; throttled tail push. */
    @JvmStatic
    fun pushTail() {
        val now = System.currentTimeMillis()
        val last = lastPushMs.get()
        if (now - last < PUSH_THROTTLE_MS || !lastPushMs.compareAndSet(last, now)) return
        executor.execute { runCatching { serveSince(tailStartSec()) }.onFailure { Log.stack(LOG_ID, "pushTail", it) } }
    }

    /** Tell the watch a sensor was removed on the phone. */
    @JvmStatic
    fun pushRemoval(serial: String?) {
        if (Applic.isWearable || serial.isNullOrBlank() || !wearCompanionEnabled()) return
        executor.execute {
            runCatching {
                val serialBytes = serial.toByteArray(Charsets.UTF_8)
                val buf = ByteBuffer.allocate(2 + serialBytes.size)
                buf.put(VERSION.toByte())
                buf.put(serialBytes.size.toByte())
                buf.put(serialBytes)
                MessageSender.sendSyncMessage(MessageSender.SYNC2_REMOVE_PATH, buf.array())
                if (doLog) Log.i(LOG_ID, "pushed removal of $serial")
            }.onFailure { Log.stack(LOG_ID, "pushRemoval", it) }
        }
    }

    /** Watch side: mirror a phone-side sensor removal. */
    @JvmStatic
    fun onRemove(data: ByteArray?) {
        executor.execute {
            runCatching {
                val buf = ByteBuffer.wrap(data ?: return@execute)
                if (buf.get().toInt() != VERSION) return@execute
                val len = buf.get().toInt() and 0xFF
                val serialBytes = ByteArray(len); buf.get(serialBytes)
                val serial = String(serialBytes, Charsets.UTF_8)
                if (serial.isEmpty()) return@execute
                removeSensorRecord(serial)
            }.onFailure { Log.stack(LOG_ID, "onRemove", it) }
        }
    }

    /**
     * Drops a sensor the watch holds on its own.
     *
     * A sensor the watch connected to directly after a handoff exists nowhere
     * else, so the phone can never tell it to go away — the only way to be rid of
     * it was to clear the watch app's data.
     */
    @JvmStatic
    fun forgetSensorLocally(serial: String?) {
        val target = serial?.trim().orEmpty()
        if (target.isEmpty()) return
        executor.execute {
            runCatching {
                Log.i(LOG_ID, "forgetting $target on this device")
                removeSensorRecord(target)
            }.onFailure { Log.stack(LOG_ID, "forgetSensorLocally", it) }
        }
    }

    private fun removeSensorRecord(serial: String) {
        run {
            run {
                // Resolve this while the managed record still exists; after it
                // is removed, a native short alias (for example P225043JMV)
                // can no longer be related back to SIBI:P225043JMV safely.
                val removedWasCurrent = SensorIdentity.matches(SensorIdentity.resolveMainSensor(), serial)
                removalTombstones[removalKey(serial)] = System.currentTimeMillis()
                tk.glucodata.drivers.ManagedSensorIdentityRegistry.removePersistedSensor(Applic.app, serial)
                // removePersistedSensor finishes the managed driver's native
                // mirror. Remove any live callback as well, then reconcile so
                // neither the Sensors screen nor dashboard can retain it.
                SensorBluetooth.sensorEnded(serial)
                runCatching {
                    if (removedWasCurrent || SensorIdentity.matches(Natives.lastsensorname(), serial)) {
                        val replacement = SensorBluetooth.resolveReplacementSensorSerial(serial)
                        SensorBluetooth.setCurrentSensorSelection(replacement ?: "")
                    }
                }
                SensorBluetooth.updateDevices()
                UiRefreshBus.requestDataRefresh()
                if (doLog) Log.i(LOG_ID, "removed $serial")
            }
        }
    }

    /** Serve the full backfill horizon (fresh-watch bootstrap). */
    @JvmStatic
    fun serveAll() {
        // Fresh-watch bootstrap: always worth it.
        executor.execute {
            runCatching {
                allowDeepServe(force = true)
                serveSince(sanitizeFrom(0L))
            }.onFailure { Log.stack(LOG_ID, "serveAll", it) }
        }
    }

    /** Re-send both the calibration state and the calibrated full horizon after a phone edit. */
    @JvmStatic
    fun onCalibrationChanged() {
        if (Applic.isWearable) return
        executor.execute {
            runCatching {
                // Calibration changes every stored value, so the horizon does
                // have to go again — but not once per keystroke of an edit.
                if (allowDeepServe(force = false)) serveSince(sanitizeFrom(0L))
                else serveSince(tailStartSec())
            }.onFailure { Log.stack(LOG_ID, "onCalibrationChanged", it) }
        }
    }

    /** Handle an incoming request from the watch. */
    @JvmStatic
    fun onRequest(data: ByteArray?) {
        val fromSec = runCatching {
            val buf = ByteBuffer.wrap(data ?: return)
            if (buf.get().toInt() != VERSION) return
            buf.long
        }.getOrNull() ?: return
        executor.execute {
            runCatching {
                val requestedHorizon = fromSec <= System.currentTimeMillis() / 1000L - BACKFILL_HORIZON_SEC + 60L
                if (!requestedHorizon || allowDeepServe(force = false)) {
                    serveSince(sanitizeFrom(fromSec))
                } else {
                    serveSince(tailStartSec())
                }
            }.onFailure { Log.stack(LOG_ID, "onRequest", it) }
        }
    }

    private fun tailStartSec(): Long = System.currentTimeMillis() / 1000L - TAIL_TRIPLES * 60L

    private fun sanitizeFrom(fromSec: Long): Long {
        val floor = System.currentTimeMillis() / 1000L - BACKFILL_HORIZON_SEC
        return fromSec.coerceAtLeast(floor)
    }

    /** True when a full-horizon serve is worth the traffic right now. */
    private fun allowDeepServe(force: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val last = lastDeepServeMs.get()
        if (!force && now - last < DEEP_SERVE_MIN_INTERVAL_MS) {
            if (doLog) Log.i(LOG_ID, "skipping deep serve, last one ${(now - last) / 1000}s ago")
            return false
        }
        lastDeepServeMs.set(now)
        return true
    }

    /**
     * Whether there is a companion to serve at all.
     *
     * A serve is not cheap: a Room history read over the whole horizon, a calibration pass across
     * every point, then up to 56 chunk messages spaced 400 ms apart. All of it ran on every
     * reading dispatch regardless of whether Wear OS was switched on, because the only thing that
     * knew — [MessageSender.sendSyncMessage] returning false with no transport — is the last call
     * made. A 2026-08-07 trace on a phone that never enabled the companion has 71 serves, one of
     * them 20145 triples: about 22 s of executor work and 56 dropped messages, once a minute.
     */
    private fun wearCompanionEnabled(): Boolean =
        runCatching { Applic.useWearos() }.getOrDefault(false)

    /**
     * Every sensor worth sending, freshest first.
     *
     * Only the phone's "main" sensor used to be served, so with two sensors
     * running the watch could never see the other one at all — and if the main
     * one happened to be the stale one, the watch sat on an hour-old reading
     * while the live sensor was never mentioned.
     */
    private fun serveSerials(): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        fun add(candidate: String?) {
            val serial = candidate?.trim()?.takeIf { SensorIdentity.isUsableSensorId(it) } ?: return
            val canonical = SensorIdentity.canonicalSensorId(serial) ?: serial
            if (seen.add(canonical.lowercase())) out.add(canonical)
        }
        runCatching { SensorIdentity.resolveMainSensor() }.getOrNull()?.let(::add)
        runCatching { Natives.activeSensors() }.getOrNull()?.forEach(::add)
        runCatching { Natives.lastsensorname() }.getOrNull()?.let(::add)
        return out.take(MAX_SERVED_SENSORS)
    }

    private fun serveSince(fromSec: Long) {
        if (!wearCompanionEnabled()) return
        val serials = serveSerials()
        if (serials.isEmpty()) return
        serials.forEach { serial -> serveSensorSince(serial, fromSec) }
    }

    private fun serveSensorSince(serial: String, fromSec: Long) {
        // Calibrations live on the phone; the watch must not echo back the
        // anchors it was given or the two would fight over the revision.
        if (!Applic.isWearable) sendCalibration(serial)
        // Serve the phone's authoritative Room-backed source lanes, not only the
        // native stream. For a managed driver native storage can begin after
        // Room history, which otherwise truncates the watch chart. Calibration
        // remains a display operation and is reproduced from the payload.
        val points = runCatching {
            NotificationHistorySource.getRawHistory(fromSec * 1000L, serial)
        }.getOrDefault(emptyList())
            .filter { it.timestamp > 0L && GlucoseValuePlausibility.isPlausibleMgdl(it.value) }
            .sortedBy { it.timestamp }
        if (points.isEmpty()) return
        // Preserve the source precision on the wire. The earlier display-unit
        // snap was not a measured fix: native storage still stores whole mg/dL,
        // so snapping to 1.80182 mg/dL steps changed the calibration input
        // without preserving that step after ingest.
        fun wireValue(mgdl: Float): Long = Math.round(mgdl * 10.0)
        val triples = LongArray(points.size * 3)
        points.forEachIndexed { i, point ->
            triples[i * 3] = point.timestamp / 1000L
            triples[i * 3 + 1] = wireValue(point.value)
            triples[i * 3 + 2] = point.rawValue
                .takeIf { GlucoseValuePlausibility.isPlausibleMgdl(it) }
                ?.let { wireValue(it) } ?: 0L
        }
        val total = points.size
        var index = 0
        var chunks = 0L
        while (index < total) {
            val count = minOf(MAX_TRIPLES_PER_CHUNK, total - index)
            val final = index + count >= total
            // Oldest first, with transport completion awaited. If the link
            // drops, stop here; the receiver's next incremental request resumes
            // from the contiguous prefix instead of leaving holes behind a
            // newer last timestamp.
            if (!sendChunk(serial, triples, index, count, final)) {
                Log.w(LOG_ID, "history send stopped for $serial at $index/$total")
                break
            }
            chunks++
            index += count
        }
        lastServedChunkCount.set(chunks)
        lastServedMs.set(System.currentTimeMillis())
        if (doLog) Log.i(LOG_ID, "served $total triples for $serial since $fromSec")
    }

    data class ServeStatus(val lastServedMs: Long, val lastChunkCount: Long)

    @JvmStatic
    fun serveStatus(): ServeStatus = ServeStatus(lastServedMs.get(), lastServedChunkCount.get())

    private fun sendChunk(serial: String, triples: LongArray, offset: Int, count: Int, final: Boolean): Boolean {
        val canonical = SensorIdentity.canonicalSensorId(serial) ?: serial
        val serialBytes = canonical.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(1 + 1 + 2 + 1 + serialBytes.size + count * 16)
        buf.put(VERSION.toByte())
        buf.put(if (final) 1 else 0)
        buf.putShort(count.toShort())
        buf.put(serialBytes.size.toByte())
        buf.put(serialBytes)
        for (i in 0 until count) {
            val base = (offset + i) * 3
            val timeSec = triples[base]
            buf.putLong(timeSec)
            // Both lanes travel uncalibrated. The phone used to correct them on
            // the way out, which meant the watch could not calibrate anything it
            // read itself — so a handoff dropped straight back to raw values.
            // Now native holds raw on both devices and each corrects at display
            // time with the same shared maths.
            buf.putInt(triples[base + 1].toInt())
            val raw10 = triples[base + 2].toInt()
            buf.putInt(if (GlucoseValuePlausibility.isPlausibleMgdl(raw10 / 10f)) raw10 else 0)
        }
        return MessageSender.sendSyncMessageAwait(MessageSender.SYNC2_CHUNK_PATH, buf.array())
    }


    /** Canonical storage name, so an alias cannot create a parallel record. */
    private fun existingSensorNameFor(serial: String): String? =
        SensorIdentity.canonicalSensorId(serial)

    private fun sendCalibration(serial: String) {
        // Never publish "no calibration" off the back of a failed load: the watch
        // corrects its own readings from these anchors now, so an empty set it
        // persists would show raw values until something changed.
        if (!CalibrationAccess.isCalibrationStateLoaded()) {
            Log.w(LOG_ID, "skipping calibration serve for $serial: calibrations not loaded yet")
            return
        }
        val payload = WearCalibrationPayload(
            sensorId = serial,
            revision = calibrationWireRevision.updateAndGet { previous ->
                maxOf(System.currentTimeMillis(), previous + 1L)
            },
            valuesPrecalibrated = false,
            hideInitialWhenCalibrated = CalibrationAccess.shouldHideInitialWhenCalibrated(),
            overwriteSensorValues = CalibrationAccess.shouldOverwriteSensorValues(),
            // A managed driver that folds the calibration into its own algorithm
            // (Sibionics STOCK_CALIBRATED) already shipped a corrected value in
            // the chunk above, and CalibrationManager.getCalibratedValue returns
            // it untouched here. Say so, or the watch applies the fit a second
            // time and shows a lower number than the phone for the same reading.
            autoIntegratedByDriver = integratesUserCalibration(serial, false),
            rawIntegratedByDriver = integratesUserCalibration(serial, true),
            tuning = CalibrationAccess.tuningForMode(false),
            rawTuning = CalibrationAccess.tuningForMode(true),
            sourceUnitMgdlPerUnit = if (Applic.unit == 1) MGDL_PER_MMOL else 1.0,
            auto = WearCalibrationMode(canonicalAnchors(serial, false)),
            raw = WearCalibrationMode(canonicalAnchors(serial, true)),
            // Only for a lane the driver integrates: these are the anchors the
            // phone's own evaluation fits against, rebased onto the stock values
            // behind them, so the watch's driver produces the same numbers when
            // it is the one holding the sensor.
            autoIntegration = WearCalibrationMode(canonicalIntegrationAnchors(serial, false)),
            rawIntegration = WearCalibrationMode(canonicalIntegrationAnchors(serial, true)),
        )
        MessageSender.sendSyncMessage(
            MessageSender.SYNC2_CAL_PATH,
            WearCalibrationPayload.encode(payload),
        )
    }

    private fun integratesUserCalibration(serial: String, isRawMode: Boolean): Boolean =
        runCatching {
            tk.glucodata.drivers.ManagedSensorRuntime.integratesUserCalibration(serial, isRawMode)
        }.getOrDefault(false)

    private fun canonicalIntegrationAnchors(serial: String, isRawMode: Boolean): DoubleArray {
        if (!integratesUserCalibration(serial, isRawMode)) return DoubleArray(0)
        return toCanonical(CalibrationAccess.getIntegratedCalibrationAnchors(serial, isRawMode))
    }

    private fun canonicalAnchors(serial: String, isRawMode: Boolean): DoubleArray =
        toCanonical(CalibrationAccess.getActiveCalibrationAnchors(serial, isRawMode))

    private fun toCanonical(anchors: DoubleArray): DoubleArray {
        if (Applic.unit != 1) return anchors
        return anchors.copyOf().also { canonical ->
            for (index in canonical.indices step 3) {
                canonical[index] *= MGDL_PER_MMOL
                canonical[index + 1] *= MGDL_PER_MMOL
            }
        }
    }

    // ---- watch side ----

    /**
     * Ask the phone for what we are missing. [deep] asks for the whole horizon
     * (app open, empty store); the routine path only asks for the tail, so a
     * fortnight of readings is not re-sent — and re-decoded — every few
     * minutes on a watch battery.
     */
    @JvmStatic
    @JvmOverloads
    fun requestSync(deep: Boolean = false) {
        executor.execute {
            runCatching {
                val nowSec = System.currentTimeMillis() / 1000L
                val horizonStart = nowSec - BACKFILL_HORIZON_SEC
                val lastSec = runCatching { Natives.lastglucosetime() }.getOrDefault(0L)
                val fromSec = if (deep || lastSec <= 0L) {
                    horizonStart
                } else {
                    (lastSec - INCREMENTAL_OVERLAP_SEC).coerceIn(horizonStart, nowSec)
                }
                val buf = ByteBuffer.allocate(9)
                buf.put(VERSION.toByte())
                buf.putLong(fromSec)
                MessageSender.sendSyncMessage(MessageSender.SYNC2_REQ_PATH, buf.array())
                if (doLog) Log.i(LOG_ID, "requested sync from $fromSec")
            }.onFailure { Log.stack(LOG_ID, "requestSync", it) }
        }
    }

    /** Ingest a chunk into native storage. */
    @JvmStatic
    fun onChunk(data: ByteArray?) {
        executor.execute {
            runCatching {
                if (!Applic.isWearable && !wearCompanionEnabled()) {
                    return@runCatching
                }
                val buf = ByteBuffer.wrap(data ?: return@execute)
                if (buf.get().toInt() != VERSION) return@execute
                val final = buf.get().toInt() and 1 != 0
                val count = buf.short.toInt() and 0xFFFF
                val serialLen = buf.get().toInt() and 0xFF
                val serialBytes = ByteArray(serialLen); buf.get(serialBytes)
                val wireSerial = String(serialBytes, Charsets.UTF_8)
                if (wireSerial.isEmpty() || count <= 0) return@execute
                // One physical sensor is spelled several ways — the full serial
                // and a short native alias — and creating a store for whichever
                // spelling happened to arrive gave the watch a second, empty
                // sensor record that then won the display and showed "No data".
                // Everything lands on the record this device already has.
                val serial = existingSensorNameFor(wireSerial) ?: wireSerial
                if (shouldIgnoreRemovedSensor(serial)) {
                    if (doLog) Log.i(LOG_ID, "ignored stale chunk for removed sensor $serial")
                    return@execute
                }
                // Chunks now travel in whichever direction ownership points, so
                // the receiving side must not be reading the same sensor itself.
                if (SensorOwnershipRuntime.readsLocally(serial)) {
                    if (doLog) Log.i(LOG_ID, "ignored chunk for $serial: this device is reading it")
                    return@execute
                }
                var written = 0
                var earliest = 0L
                val stamps = LongArray(count)
                val values = FloatArray(count)
                val raws = FloatArray(count)
                val nativeSecs = LongArray(count)
                val nativeValues = FloatArray(count)
                for (i in 0 until count) {
                    val t = buf.long
                    val auto10 = buf.int
                    val raw10 = buf.int
                    if (t <= 0L || auto10 <= 0) continue
                    if (earliest == 0L) {
                        earliest = t
                        // Native scale contract (g.cpp addGlucoseStreamInternal):
                        // glucose param = mgdl/10 (native ×10), raw param = plain
                        // mgdl. Triples carry mgdl*10.
                        Natives.ensureSensorShell(serial, (t - 3600L).coerceAtLeast(1L))
                    }
                    val rawMgdl = if (raw10 > 0) raw10 / 10f else 0f
                    nativeSecs[written] = t
                    nativeValues[written] = auto10 / 100f
                    stamps[written] = t * 1000L
                    values[written] = auto10 / 10f
                    raws[written] = rawMgdl
                    written++
                }
                if (written > 0) {
                    // One JNI call for the chunk. Per reading, the native side
                    // re-seeded direct-stream state (stat + read + alloc), logged a
                    // line and rewound the stream cursor every time; a large chunk
                    // was enough to GC-stall the app. Same reason the Sibionics and
                    // Anytime history mirrors are batched.
                    Natives.addGlucoseStreamBatchWithRawTemp(
                        nativeSecs.copyOf(written),
                        nativeValues.copyOf(written),
                        raws.copyOf(written),
                        FloatArray(written),
                        serial,
                    )
                }
                // The phone reads its history from Room and never looks at native,
                // so readings taken over by the watch landed somewhere the phone
                // does not display. Put them where the phone actually looks.
                if (written > 0 && !Applic.isWearable) {
                    HistorySyncAccess.storeSensorHistoryBatchAsync(
                        serial,
                        stamps.copyOf(written),
                        values.copyOf(written),
                        raws.copyOf(written),
                    )
                    val newest = written - 1
                    HistorySyncAccess.storeCurrentReadingAsync(
                        stamps[newest],
                        values[newest],
                        raws[newest],
                        0f,
                        serial,
                    )
                    emitExchangeOutputsForSyncedReading(
                        serial,
                        stamps[newest],
                        values[newest],
                    )
                }
                if (written > 0) {
                    // The companion follows the phone's served sensor: after a
                    // sensor swap the watch's stale current selection otherwise
                    // sticks to the dead sensor ("No data" with fresh chunks
                    // landing in the new one).
                    runCatching {
                        val current = Natives.lastsensorname()
                        if (current.isNullOrEmpty() || !current.equals(serial, ignoreCase = true)) {
                            Natives.setcurrentsensor(serial)
                        }
                    }
                    UiRefreshBus.requestDataRefresh()
                }
                if (doLog) Log.i(LOG_ID, "ingested $written/$count triples for $serial final=$final")
            }.onFailure { Log.stack(LOG_ID, "onChunk", it) }
        }
    }

    /**
     * Runs the newest synced reading through the same outbound path a locally
     * read one takes: Nightscout and LibreView through numdata, the outbound
     * API, the widget, and the xDrip / Gadgetbridge / WearInt
     * broadcasts.
     *
     * While the watch holds the sensor the phone's BLE callback never fires, so
     * every one of those silently stopped for the duration — the phone kept
     * displaying readings it was no longer forwarding anywhere. Alarms and
     * notifications are deliberately not included: the device that read the
     * sensor has already raised them.
     *
     * @param valueMgdl the reading in mg/dL, as the chunk carries it.
     */
    private fun emitExchangeOutputsForSyncedReading(
        serial: String,
        timestampMs: Long,
        valueMgdl: Float,
    ) {
        if (Applic.isWearable || timestampMs <= 0L || !valueMgdl.isFinite() || valueMgdl <= 0f) return
        runCatching {
            val isMmol = Applic.unit == 1
            val displayValue =
                if (isMmol) (valueMgdl / MGDL_PER_MMOL).toFloat() else valueMgdl
            // The arrow the exchange targets carry has to come from the series,
            // not the single reading; the payload resolver falls back to this.
            val rate = runCatching {
                val from = timestampMs - TREND_WINDOW_MS
                val window = NotificationHistorySource
                    .getDisplayHistory(from, isMmol, serial)
                    .filter { it.timestamp in from..timestampMs }
                if (window.size >= 2) TrendAccess.calculateVelocity(window, false, isMmol) else Float.NaN
            }.getOrDefault(Float.NaN).takeIf { it.isFinite() } ?: 0f
            val primaryText = String.format(Applic.usedlocale, Notify.pureglucoseformat, displayValue)
            val sensorStartMs = runCatching {
                val ptr = Natives.getdataptr(serial)
                if (ptr != 0L) Natives.getSensorStartmsec(ptr) else 0L
            }.getOrDefault(0L)
            SuperGattCallback.emitExchangeOutputs(
                serial,
                displayValue,
                rate,
                0,
                timestampMs,
                sensorStartMs,
                timestampMs / 1000L,
                // Only a fallback: the payload resolver prefers the live
                // snapshot's own generation when it has one.
                0,
                primaryText,
                // Came from the peer; sending it straight back would echo.
                false,
            )
        }.onFailure { Log.stack(LOG_ID, "emitExchangeOutputsForSyncedReading", it) }
    }

    @JvmStatic
    fun onCalibration(data: ByteArray?) {
        if (!Applic.isWearable) return
        val payload = WearCalibrationPayload.decode(data) ?: run {
            Log.w(LOG_ID, "ignored malformed sync2 calibration payload")
            return
        }
        SyncedWearCalibrationProvider.update(payload)
        if (doLog) {
            Log.i(
                LOG_ID,
                "received calibration for ${payload.sensorId}: " +
                    "auto=${payload.auto.anchorsMgdl.size / 3} raw=${payload.raw.anchorsMgdl.size / 3}",
            )
        }
    }

    private fun removalKey(serial: String): String = serial.trim().uppercase()

    private fun shouldIgnoreRemovedSensor(serial: String): Boolean {
        val key = removalKey(serial)
        val removedAt = removalTombstones[key] ?: return false
        val persistedAgain = runCatching {
            tk.glucodata.drivers.ManagedSensorIdentityRegistry.persistedSensorIds(Applic.app)
                .any { SensorIdentity.matches(it, serial) }
        }.getOrDefault(false)
        if (persistedAgain || System.currentTimeMillis() - removedAt > REMOVAL_TOMBSTONE_MS) {
            removalTombstones.remove(key, removedAt)
            return false
        }
        return true
    }
}
