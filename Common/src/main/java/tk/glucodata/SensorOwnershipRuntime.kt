package tk.glucodata

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Keeps exactly one device reading each sensor, and hands it over when that
 * device stops being able to.
 *
 * Both ends run this. Each announces, once a minute and whenever something
 * changes, whether it currently holds a sensor and how recent its newest reading
 * is; each then applies [SensorOwnershipPolicy] and either keeps its connection
 * or stands down. Releasing is per sensor — pausing that sensor's driver, the
 * same way the phone's own pause button does — because tearing down Bluetooth
 * wholesale would drop every other sensor with it.
 *
 * Nothing here is destructive: a released sensor keeps its record, its history
 * and its calibration, and resuming is a reconnect.
 */
object SensorOwnershipRuntime {
    private const val LOG_ID = "SensorOwnership"

    /** How often each device says what it is holding. */
    private const val ANNOUNCE_INTERVAL_MS = 60_000L

    /** Three missed announcements before the peer counts as gone. */
    private const val PEER_SILENT_AFTER_MS = 3L * ANNOUNCE_INTERVAL_MS + 15_000L

    /**
     * How long the phone lets go for, when the user hands a sensor to the watch.
     *
     * Long enough for a scan and connect, short enough that a watch which cannot
     * take the sensor costs at most this much data before the phone resumes.
     */
    private const val YIELD_WINDOW_MS = 3L * 60L * 1000L

    /** After a failed handover, how long before offering the watch another go. */
    private const val YIELD_RETRY_INTERVAL_MS = 15L * 60L * 1000L

    /** Reconnect delay when taking a sensor back, in ms. */
    private const val RESUME_DELAY_MS = 200L

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "SensorOwnership").apply { isDaemon = true }
    }

    private val peerReports = ConcurrentHashMap<String, SensorOwnershipPolicy.PeerReport>()
    /** Serials as the peer spells them, so we can answer about sensors we lack. */
    private val peerSerials = ConcurrentHashMap<String, String>()
    private val localReadings = ConcurrentHashMap<String, Long>()
    private val releasedLocally = ConcurrentHashMap<String, Boolean>()
    private val yieldStartedAt = ConcurrentHashMap<String, Long>()

    @Volatile private var started = false

    private fun key(serial: String): String =
        (runCatching { SensorIdentity.resolveAppSensorId(serial) }.getOrNull() ?: serial).lowercase()

    /** Starts announcing and reconciling. Safe to call repeatedly. */
    @JvmStatic
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        executor.scheduleWithFixedDelay(
            { runCatching { announceAndReconcile() }.onFailure { Log.stack(LOG_ID, "tick", it) } },
            5_000L,
            ANNOUNCE_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
        Log.i(LOG_ID, "sensor ownership arbitration started")
    }

    /** A local driver accepted a reading — the evidence that we hold a sensor. */
    @JvmStatic
    fun noteLocalReading(serial: String?, timestampMs: Long) {
        val target = serial?.trim()?.takeIf { SensorIdentity.isUsableSensorId(it) } ?: return
        val stamp = if (timestampMs > 0L) timestampMs else System.currentTimeMillis()
        val previous = localReadings.put(key(target), stamp)
        // First reading after a gap changes what we would announce, so say so now
        // rather than at the next tick.
        if (previous == null) executor.execute { runCatching { announceAndReconcile() } }
    }

    /**
     * Whether this device is the one reading [serial] right now. Readings that
     * arrive over the Data Layer for a sensor we hold ourselves are dropped, so
     * the two devices cannot write over each other during a changeover.
     */
    @JvmStatic
    fun readsLocally(serial: String?): Boolean {
        val target = serial?.trim()?.takeIf { SensorIdentity.isUsableSensorId(it) } ?: return false
        if (releasedLocally[key(target)] == true) return false
        return holdsLiveConnection(target)
    }

    /** The peer told us what it is holding. */
    @JvmStatic
    fun onPeerReport(data: ByteArray?) {
        val report = decode(data) ?: return
        peerSerials[key(report.first)] = report.first
        peerReports[key(report.first)] = SensorOwnershipPolicy.PeerReport(
            owns = report.second,
            lastReadingMs = report.third,
            receivedAtMs = System.currentTimeMillis(),
        )
        if (Log.doLog) {
            Log.i(LOG_ID, "peer holds ${report.first}=${report.second} newest=${report.third}")
        }
        executor.execute { runCatching { reconcile() }.onFailure { Log.stack(LOG_ID, "reconcile", it) } }
    }

    private fun announceAndReconcile() {
        announce()
        reconcile()
    }

    private fun announce() {
        if (!MessageSender.outgoingAllowed()) return
        sensors().forEach { serial ->
            val owns = holdsLiveConnection(serial)
            val newest = localReadings[key(serial)] ?: 0L
            MessageSender.sendSyncMessage(MessageSender.SENSOR_OWNERSHIP_PATH, encode(serial, owns, newest))
        }
    }

    private fun reconcile() {
        val now = System.currentTimeMillis()
        sensors().forEach { serial ->
            val id = key(serial)
            val peer = peerReports[id]
            val intent = intentFor(serial)
            val shouldRead = SensorOwnershipPolicy.shouldReadLocally(
                isPhone = !Applic.isWearable,
                intent = intent,
                localHasConnection = holdsLiveConnection(serial),
                localLastReadingMs = localReadings[id] ?: 0L,
                peer = peer,
                nowMs = now,
                peerSilentAfterMs = PEER_SILENT_AFTER_MS,
                yieldUntilMs = yieldDeadline(id, intent, peer, now),
            )
            val released = releasedLocally[id] == true
            when {
                !shouldRead && !released -> release(serial)
                shouldRead && released -> resume(serial)
            }
        }
    }

    /**
     * What this device should do with a sensor.
     *
     * The watch always takes what it can: with no assignment it only ends up
     * reading a sensor the phone has stopped reading, which is the automatic
     * "whichever device can reach it" behaviour. The phone stands aside only
     * where the user has actually assigned the sensor to the watch.
     */
    private fun intentFor(serial: String): SensorOwnershipPolicy.Intent {
        if (Applic.isWearable) return SensorOwnershipPolicy.Intent.TAKE
        return if (assignedToWatch()) {
            SensorOwnershipPolicy.Intent.YIELD
        } else {
            SensorOwnershipPolicy.Intent.TAKE
        }
    }

    /**
     * Until when this device stays off the sensor.
     *
     * The window opens when the handover starts and closes once it expires; if
     * the watch took the sensor in the meantime the ordinary rules keep the phone
     * off anyway. A handover that failed is retried periodically, so a watch that
     * was out of range gets another chance without the user touching anything.
     */
    private fun yieldDeadline(
        id: String,
        intent: SensorOwnershipPolicy.Intent,
        peer: SensorOwnershipPolicy.PeerReport?,
        now: Long,
    ): Long {
        if (intent != SensorOwnershipPolicy.Intent.YIELD) {
            yieldStartedAt.remove(id)
            return 0L
        }
        // The peer has it: no window needed, the normal rules apply.
        if (peer?.owns == true) {
            yieldStartedAt.remove(id)
            return 0L
        }
        val started = yieldStartedAt[id]
        if (started == null) {
            yieldStartedAt[id] = now
            Log.i(LOG_ID, "handing $id to the watch: standing down for ${YIELD_WINDOW_MS / 1000}s")
            return now + YIELD_WINDOW_MS
        }
        if (now < started + YIELD_WINDOW_MS) return started + YIELD_WINDOW_MS
        if (now >= started + YIELD_RETRY_INTERVAL_MS) {
            yieldStartedAt[id] = now
            Log.i(LOG_ID, "offering $id to the watch again")
            return now + YIELD_WINDOW_MS
        }
        // Window spent and the watch did not take it: read it ourselves again.
        return 0L
    }

    /** Whether the user has assigned the sensor to the watch, read from prefs. */
    private fun assignedToWatch(): Boolean = runCatching {
        Applic.app
            ?.getSharedPreferences("wear_routing_request", android.content.Context.MODE_PRIVATE)
            ?.all
            ?.any { (key, value) -> key.startsWith("direct.") && value == true }
    }.getOrNull() ?: false

    private fun release(serial: String) {
        val gatt = findGatt(serial) ?: return
        releasedLocally[key(serial)] = true
        Log.i(LOG_ID, "standing down from $serial: the other device is reading it")
        runCatching {
            gatt.setPause(true)
            gatt.disconnect()
        }.onFailure { Log.stack(LOG_ID, "release($serial)", it) }
    }

    private fun resume(serial: String) {
        releasedLocally.remove(key(serial))
        val gatt = findGatt(serial) ?: return
        Log.i(LOG_ID, "taking $serial back: the other device is no longer reading it")
        runCatching {
            gatt.setPause(false)
            gatt.connectDevice(RESUME_DELAY_MS)
        }.onFailure { Log.stack(LOG_ID, "resume($serial)", it) }
    }

    /**
     * Every sensor either device might be reading.
     *
     * Deriving this from local connections alone meant a device with no driver
     * for a sensor said nothing about it at all — and silence is what the other
     * side treats as "gone", so the peer could never learn the difference
     * between "I do not hold it" and "I am not here". Both ends therefore answer
     * about the union: what they hold, what the store knows, and whatever the
     * peer has mentioned.
     */
    private fun sensors(): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        fun add(candidate: String?) {
            val serial = candidate?.trim()?.takeIf { SensorIdentity.isUsableSensorId(it) } ?: return
            if (seen.add(key(serial))) out.add(serial)
        }
        runCatching {
            SensorBluetooth.mygatts()?.forEach { add(it.SerialNumber) }
        }
        runCatching { Natives.activeSensors() }.getOrNull()?.forEach(::add)
        peerSerials.values.forEach(::add)
        return out
    }

    private fun findGatt(serial: String): SuperGattCallback? = runCatching {
        SensorBluetooth.mygatts()?.firstOrNull { SensorIdentity.matches(it.SerialNumber, serial) }
    }.getOrNull()

    private fun holdsLiveConnection(serial: String): Boolean = runCatching {
        SensorBluetooth.mygatts()?.any {
            it.hasLocallyConnectedGatt() && SensorIdentity.matches(it.SerialNumber, serial)
        } == true
    }.getOrDefault(false)

    // ---------------------------------------------------------------- wire

    private const val VERSION = 1

    private fun encode(serial: String, owns: Boolean, lastReadingMs: Long): ByteArray {
        val serialBytes = serial.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(1 + 1 + 1 + serialBytes.size + 8)
            .put(VERSION.toByte())
            .put(if (owns) 1 else 0)
            .put(serialBytes.size.toByte())
            .put(serialBytes)
            .putLong(lastReadingMs)
            .array()
    }

    /** [serial, owns, lastReadingMs], or null when malformed. */
    internal fun decode(data: ByteArray?): Triple<String, Boolean, Long>? {
        val bytes = data ?: return null
        if (bytes.size < 11) return null
        val buffer = ByteBuffer.wrap(bytes)
        if (buffer.get().toInt() != VERSION) return null
        val owns = buffer.get().toInt() != 0
        val length = buffer.get().toInt() and 0xFF
        if (length == 0 || buffer.remaining() < length + 8) return null
        val serialBytes = ByteArray(length)
        buffer.get(serialBytes)
        val serial = String(serialBytes, StandardCharsets.UTF_8)
        if (!SensorIdentity.isUsableSensorId(serial)) return null
        return Triple(serial, owns, buffer.long)
    }
}
