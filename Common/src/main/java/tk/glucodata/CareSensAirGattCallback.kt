// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import tk.glucodata.drivers.caresens.CareSensAirFactoryConfig
import tk.glucodata.drivers.caresens.CareSensAirEngine
import tk.glucodata.drivers.caresens.CareSensAirInfoAssembler
import tk.glucodata.drivers.caresens.CareSensAirJavaEngine
import tk.glucodata.drivers.caresens.CareSensAirJournal
import tk.glucodata.drivers.caresens.CareSensAirProtocol
import tk.glucodata.drivers.caresens.CareSensAirSession
import tk.glucodata.drivers.caresens.CareSensAirVersion
import tk.glucodata.drivers.caresens.hasHeader

/**
 * Private-test collector. Android scan, PIN storage and the native poll shell retain the normal
 * CareSens identity. The BLE state and calibration cursor are owned by the Kotlin worker.
 */
@SuppressLint("MissingPermission")
internal class CareSensAirGattCallback(
    serial: String,
    pointer: Long,
    private val directory: File,
    private val openEngine: ((ByteArray) -> CareSensAirEngine)?,
    private val worker: ScheduledExecutorService = sharedWorker,
) : SuperGattCallback(serial, pointer, SensorSourceResolver.SENSOR_KIND_CARESENS_AIR) {
    private enum class Stage {
        MTU, DISCOVERY, SERIAL, SOFTWARE, REGISTER_SUBSCRIBE, REGISTER_REPLY, BOND,
        DATA_SUBSCRIBE, CONTROL_SUBSCRIBE, AUTH_REPLY, START, FACTORY, CLOCK, RECORDS, CLOSED,
    }

    private class Link(val gatt: BluetoothGatt) {
        var stage = Stage.MTU
        var deadline: ScheduledFuture<*>? = null
        var batchSettled: ScheduledFuture<*>? = null
        var verified = false
        lateinit var characteristics: Map<UUID, BluetoothGattCharacteristic>
        lateinit var software: CareSensAirVersion
        var syncClock = false
        var start: CareSensAirProtocol.StartInfo? = null
        var startSeconds = 0L
        val factory = CareSensAirInfoAssembler()
        var askedCount = false
        var remaining: Int? = null
        var latest: CareSensAirSession.Computed? = null
        var historyStartSeconds = Long.MAX_VALUE
    }

    private val expectedSerial = serial.takeLast(12)
    private val registration = File(directory, "registered")
    private val journal = CareSensAirJournal(directory, BuildConfig.CARESENS_AIR_ENGINE_ID, serial)
    private var session: CareSensAirSession? = null
    @Volatile private var link: Link? = null
    @Volatile private var pairingGatt: BluetoothGatt? = null
    @Volatile private var rejectedAddress: String? = null
    @Volatile private var rejectAddressUntil = 0L
    @Volatile private var blocked = openEngine == null
    private var failures = 0
    private var lastPublishedMillis = 0L

    init {
        showtime = 6 * 60 * 1000L
        mActiveDeviceAddress = Natives.getDeviceAddress(dataptr, false)
        if (blocked) handshake = "CareSens Air private engine unavailable"
    }

    // A parked experimental identity is still owned here when the private JAR is absent.
    // Report it as handled to avoid starting a scan repeatedly.
    override fun connectDevice(delayMillis: Long): Boolean = blocked || super.connectDevice(delayMillis)

    override fun connectionAttemptTimeoutMillis(): Long = 45_000

    override fun getService(): UUID = SERVICE

    override fun matchDeviceName(nameDevice: String?, address: String?): Boolean =
        !blocked && nameDevice?.startsWith("CSAir " + expectedSerial.takeLast(4)) == true &&
            (address != rejectedAddress || System.currentTimeMillis() >= rejectAddressUntil)

    override fun setDevice(device: BluetoothDevice?) {
        mActiveBluetoothDevice = device
        mActiveDeviceAddress = device?.address
        // Scan results are candidates. The verified serial read commits the address.
        if (device == null) super.setDeviceAddress(null)
    }

    @Synchronized
    override fun resetdataptr(): Long {
        val pointer = super.resetdataptr()
        mActiveDeviceAddress = Natives.getDeviceAddress(pointer, false)
        return pointer
    }

    @Synchronized
    override fun pairingRequest(): Boolean {
        val active = pairingGatt ?: return false
        if (!current(active) || active.device.address != mActiveBluetoothDevice?.address || dataptr == 0L) return false
        val pin = Natives.airGetPin(dataptr) ?: return false
        return pin.size == 6 && active.device.setPin(pin)
    }

    override fun bonded() {
        val gatt = pairingGatt ?: return
        event(gatt) {
            if (stage == Stage.BOND && gatt.device.bondState == BluetoothDevice.BOND_BONDED) {
                pairingGatt = null
                wrotepass[0] = System.currentTimeMillis()
                subscribe(DATA, Stage.DATA_SUBSCRIBE)
            }
        }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        noteFirstGattCallback("onConnectionStateChange", gatt)
        if (!acceptConnectionAttemptCallback(gatt, newState)) return
        super.onConnectionStateChange(gatt, status, newState)
        worker.execute {
            if (!current(gatt)) return@execute
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        closeGattTransport()
                        reconnectAfterFailure()
                        return@execute
                    }
                    constatchange[0] = System.currentTimeMillis()
                    val next = Link(gatt)
                    link?.let {
                        session = null
                        it.deadline?.cancel(false)
                        it.batchSettled?.cancel(false)
                    }
                    link = next
                    event(gatt) {
                        arm(Stage.MTU, 5_000)
                        if (!gatt.requestMtu(512)) discover()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    link?.deadline?.cancel(false)
                    link?.batchSettled?.cancel(false)
                    session = null
                    link = null
                    pairingGatt = null
                    constatchange[1] = System.currentTimeMillis()
                    setConStatus(status)
                    closeGattTransport()
                    reconnectAfterFailure()
                }
            }
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = event(gatt) {
        if (stage == Stage.MTU) discover()
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = event(gatt) {
        if (stage != Stage.DISCOVERY) return@event
        require(status == BluetoothGatt.GATT_SUCCESS)
        characteristics = gatt.services.flatMap { it.characteristics }.associateBy { it.uuid }
        require(listOf(SERIAL, SOFTWARE, DATA, CONTROL).all(characteristics::containsKey))
        read(SERIAL, Stage.SERIAL)
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int,
    ) = readInfo(gatt, characteristic.uuid, value.copyOf(), status)

    @Deprecated("Android reads through the value snapshot overload on API 33 and later")
    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        readInfo(gatt, characteristic.uuid, characteristic.value?.copyOf() ?: ByteArray(0), status)
    }

    private fun readInfo(gatt: BluetoothGatt, uuid: UUID, value: ByteArray, status: Int) = event(gatt) {
        if (stage == Stage.SERIAL && uuid == SERIAL) {
            require(status == BluetoothGatt.GATT_SUCCESS)
            if (ascii(value) != expectedSerial) {
                rejectedAddress = gatt.device.address
                rejectAddressUntil = System.currentTimeMillis() + 60_000
                synchronized(this@CareSensAirGattCallback) {
                    if (current(gatt) && dataptr != 0L) {
                        if (Natives.getDeviceAddress(dataptr, false) == rejectedAddress) {
                            super@CareSensAirGattCallback.setDeviceAddress(null)
                        }
                        mActiveDeviceAddress = null
                        mActiveBluetoothDevice = null
                        foundtime = 0L
                    }
                }
                fail("CareSens Air transmitter serial mismatch")
                return@event
            }
            synchronized(this@CareSensAirGattCallback) {
                if (!current(gatt) || dataptr == 0L) return@event
                super@CareSensAirGattCallback.setDeviceAddress(gatt.device.address)
            }
            verified = true
            read(SOFTWARE, Stage.SOFTWARE)
        } else if (stage == Stage.SOFTWARE && uuid == SOFTWARE) {
            require(status == BluetoothGatt.GATT_SUCCESS)
            software = requireNotNull(CareSensAirVersion.parse(ascii(value)))
            if (!restore()) return@event
            if (software >= CareSensAirVersion.V1_5) subscribe(REGISTRATION, Stage.REGISTER_SUBSCRIBE)
            else bondOrSubscribe()
        }
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = event(gatt) {
        if (descriptor.uuid != mCharacteristicConfigDescriptor) return@event
        val uuid = descriptor.characteristic.uuid
        when {
            stage == Stage.REGISTER_SUBSCRIBE && uuid == REGISTRATION -> {
                require(status == BluetoothGatt.GATT_SUCCESS)
                after(100) {
                    write(REGISTRATION, CareSensAirProtocol.registerApp(!registration.exists() && session == null), Stage.REGISTER_REPLY)
                }
            }
            stage == Stage.DATA_SUBSCRIBE && uuid == DATA -> {
                require(status == BluetoothGatt.GATT_SUCCESS)
                subscribe(CONTROL, Stage.CONTROL_SUBSCRIBE)
            }
            stage == Stage.CONTROL_SUBSCRIBE && uuid == CONTROL -> {
                require(status == BluetoothGatt.GATT_SUCCESS)
                after(500) {
                    val auth = requireNotNull(CareSensAirProtocol.authentication(software, expectedSerial))
                    write(CONTROL, auth, Stage.AUTH_REPLY)
                }
            }
        }
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
        event(gatt) {
            val expected = when (stage) {
                Stage.REGISTER_REPLY -> REGISTRATION
                Stage.AUTH_REPLY, Stage.START, Stage.FACTORY, Stage.CLOCK -> CONTROL
                Stage.RECORDS -> DATA
                else -> null
            }
            if (characteristic.uuid == expected && status != BluetoothGatt.GATT_SUCCESS) {
                fail("CareSens Air GATT write failed")
            }
        }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
    ) = notification(gatt, characteristic.uuid, value.copyOf())

    @Deprecated("Android notifies through the value snapshot overload on API 33 and later")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) =
        notification(gatt, characteristic.uuid, characteristic.value?.copyOf() ?: ByteArray(0))

    private fun notification(gatt: BluetoothGatt, uuid: UUID, frame: ByteArray) = event(gatt) {
        if (!verified) return@event
        when {
            uuid == REGISTRATION && stage == Stage.REGISTER_REPLY && frame.hasHeader(0xC0, 3) -> {
                val reply = requireNotNull(CareSensAirProtocol.parseRegistration(frame))
                if (!reply.accepted) fail("CareSens Air registration failed (${reply.statusCode})")
                else {
                    registration.createNewFile()
                    bondOrSubscribe()
                }
            }
            uuid == CONTROL -> control(frame)
            uuid == DATA && stage == Stage.RECORDS -> records(frame)
        }
    }

    private fun Link.control(frame: ByteArray) {
        when {
            frame.hasHeader(0xCC, 2) -> {
                flush(live = false)
                blocked = true
                synchronized(this@CareSensAirGattCallback) {
                    if (current(gatt) && dataptr != 0L) finishSensor()
                }
                retire()
            }
            frame.hasHeader(0xCD, 2) -> fail("CareSens Air transmitter reset")
            stage == Stage.AUTH_REPLY && frame.hasHeader(0xC0, 1) -> {
                val info = requireNotNull(CareSensAirProtocol.parseApplicationInfo(frame, software))
                if (info.keyCheckResult == 0 || (info.qcResult != null && info.qcResult !in setOf(0, 16))) {
                    fail("CareSens Air authentication or transmitter QC failed")
                    return
                }
                syncClock = abs(nowSeconds() - info.deviceTimeSeconds) >= (info.adcInterval / 5000).coerceAtLeast(1)
                if (session == null) write(CONTROL, CareSensAirProtocol.requestStartInfo(), Stage.START)
                else requestRecordsOrSync()
            }
            stage == Stage.START && frame.hasHeader(0xC0, 2) -> {
                val info = requireNotNull(CareSensAirProtocol.parseStartInfo(frame, software))
                require(info.elapsedSeconds <= CareSensAirFactoryConfig.MAX_SEQUENCE * CareSensAirFactoryConfig.RECORD_INTERVAL_SECONDS)
                start = info
                startSeconds = nowSeconds() - info.elapsedSeconds
                write(CONTROL, CareSensAirProtocol.requestFactoryInfo(), Stage.FACTORY)
            }
            stage == Stage.FACTORY && frame.size >= 2 && (frame[0].toInt() and 0xFF) == 0xC2 -> when (val result = factory.accept(frame)) {
                CareSensAirInfoAssembler.Result.Pending -> arm(Stage.FACTORY)
                is CareSensAirInfoAssembler.Result.Rejected -> fail("CareSens Air factory transfer: ${result.failure}")
                is CareSensAirInfoAssembler.Result.Verified -> {
                    val packed = CareSensAirFactoryConfig.pack(result, requireNotNull(start), startSeconds)
                    val config = CareSensAirJournal.Config(
                        packed,
                        result.payloadWithChecksum(),
                        intArrayOf(result.headerPayloadSize) + result.continuationDataSizes(),
                    )
                    val engine = checkNotNull(openEngine)(packed)
                    journal.saveConfig(config)
                    setNativeStart(config)
                    session = CareSensAirSession(journal, config, engine)
                    requestRecordsOrSync()
                }
            }
            stage == Stage.CLOCK && frame.hasHeader(0xC3, 2) -> requestRecords()
        }
    }

    private fun Link.records(frame: ByteArray) {
        val count = CareSensAirProtocol.parseRecordCount(frame)
        if (count != null) {
            batchSettled?.cancel(false)
            require(count.toLong() <= CareSensAirFactoryConfig.MAX_SEQUENCE)
            remaining = count
            if (!askedCount) {
                askedCount = true
                write(DATA, CareSensAirProtocol.requestRecordCount(), Stage.RECORDS)
            }
            if (count == 0) settleBatch() else arm(Stage.RECORDS)
            return
        }
        if (!frame.hasHeader(0xC5, 1)) return
        batchSettled?.cancel(false)
        when (val arrival = checkNotNull(session).receive(frame, nowSeconds())) {
            CareSensAirSession.Arrival.Duplicate -> Unit
            CareSensAirSession.Arrival.Gap -> {
                fail("CareSens Air record gap; requesting from the last contiguous sequence")
                return
            }
            CareSensAirSession.Arrival.Invalid -> {
                fail("CareSens Air record or measurement time is invalid")
                return
            }
            is CareSensAirSession.Arrival.Accepted -> {
                // Only the newest reading in the completed batch may reach current/alerts.
                flush(live = false)
                latest = arrival.computed
            }
        }
        val left = remaining?.minus(1)?.coerceAtLeast(0)
        remaining = left
        if (left == 0) settleBatch() else arm(Stage.RECORDS)
    }

    private fun Link.settleBatch() {
        arm(Stage.RECORDS)
        batchSettled = after(1_000) {
            flush(live = true)
            importRecentHistory()
            failures = 0
            handshake = "CareSens Air managed batch complete"
            Log.i(TAG, "Managed batch complete at sequence ${session?.cursor}")
            retire()
            reconnect(60_000L, afterKnownReading = true)
        }
    }

    private fun Link.restore(): Boolean {
        if (session != null) return true
        return try {
            val config = journal.loadConfig() ?: return true
            val restored = CareSensAirSession(journal, config, checkNotNull(openEngine)(config.packed))
            val history = restored.recover()
            if (!isActive()) return false
            setNativeStart(config)
            for (computed in history) publish(computed, live = false)
            importRecentHistory()
            session = restored
            true
        } catch (error: Exception) {
            if (!isActive()) return false
            // State that cannot be replayed must not reset the engine and start again at an
            // arbitrary sensor sequence. Keep the files for investigation/recovery.
            Log.e(TAG, "Private session restore failed: ${error.javaClass.simpleName}")
            blocked = true
            fail("CareSens Air saved session could not be restored")
            false
        }
    }

    private fun Link.setNativeStart(config: CareSensAirJournal.Config) {
        synchronized(this@CareSensAirGattCallback) {
            check(current(gatt) && dataptr != 0L && Natives.airManagedSetStart(dataptr, config.startSeconds))
            sensorstartmsec = config.startSeconds * 1_000
        }
    }

    private fun Link.flush(live: Boolean) {
        val reading = latest ?: return
        publish(reading, live)
        latest = null
    }

    private fun Link.publish(value: CareSensAirSession.Computed, live: Boolean) {
        val output = value.output
        val timeMillis = output.timestampSeconds * 1_000
        val rate = output.rate.takeIf { it.isFinite() && abs(it) < 99.0 }?.toFloat() ?: Float.NaN
        val response = synchronized(this@CareSensAirGattCallback) {
            check(current(gatt) && dataptr != 0L)
            Natives.airManagedPublish(
                dataptr, output.sequence, output.timestampSeconds, output.glucoseMgdl.roundToInt(),
                rate, live && value.hasSensorTime,
            )
        }
        check(response != 0L) { "CareSens Air native history write failed" }
        historyStartSeconds = minOf(historyStartSeconds, output.timestampSeconds)
        if (response != 1L && current(gatt)) {
            lastPublishedMillis = timeMillis
            handleGlucoseResult(response, timeMillis)
        }
    }

    private fun Link.importRecentHistory() {
        if (historyStartSeconds != Long.MAX_VALUE) {
            val from = (historyStartSeconds - 1).coerceAtLeast(0L) * 1_000
            HistorySyncAccess.syncSensorWindowFromNative(SerialNumber, from)
            historyStartSeconds = Long.MAX_VALUE
        }
    }

    private fun Link.requestRecordsOrSync() {
        if (syncClock) write(CONTROL, CareSensAirProtocol.syncTime(nowSeconds()), Stage.CLOCK)
        else requestRecords()
    }

    private fun Link.requestRecords() {
        write(DATA, CareSensAirProtocol.requestRecords(checkNotNull(session).cursor.toLong()), Stage.RECORDS)
    }

    private fun Link.discover() {
        arm(Stage.DISCOVERY)
        check(gatt.discoverServices())
    }

    private fun Link.read(uuid: UUID, next: Stage) {
        arm(next)
        check(gatt.readCharacteristic(characteristics.getValue(uuid)))
    }

    private fun Link.bondOrSubscribe() {
        when (gatt.device.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                wrotepass[0] = System.currentTimeMillis()
                subscribe(DATA, Stage.DATA_SUBSCRIBE)
            }
            else -> {
                arm(Stage.BOND, 45_000)
                pairingGatt = gatt
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDING) {
                    after(1_000) { check(gatt.device.createBond()) }
                }
            }
        }
    }

    private fun Link.subscribe(uuid: UUID, next: Stage) {
        arm(next)
        check(enableNotification(gatt, characteristics.getValue(uuid)))
    }

    @Suppress("DEPRECATION")
    private fun Link.write(uuid: UUID, payload: ByteArray, next: Stage) {
        val characteristic = characteristics.getValue(uuid)
        arm(next)
        val type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val sent = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(characteristic, payload, type) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = type
            characteristic.setValue(payload) && gatt.writeCharacteristic(characteristic)
        }
        check(sent)
    }

    private fun Link.arm(next: Stage, delay: Long = 15_000) {
        stage = next
        deadline?.cancel(false)
        deadline = worker.schedule({
            if (isActive() && stage == next) {
                runSafely {
                    if (next == Stage.MTU) discover()
                    else fail("CareSens Air timed out during ${next.name.lowercase()}")
                }
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun Link.after(delay: Long, action: Link.() -> Unit): ScheduledFuture<*> {
        val expected = stage
        return worker.schedule(
            { if (isActive() && stage == expected) runSafely { action() } }, delay, TimeUnit.MILLISECONDS,
        )
    }

    private fun Link.fail(message: String) {
        handshake = message
        wrotepass[1] = System.currentTimeMillis()
        Log.w(TAG, message)
        // Rebuild from durable inputs on the next connection, including any append that
        // succeeded immediately before an algorithm or native publication failure.
        session = null
        retire()
        reconnectAfterFailure()
    }

    private fun Link.retire() {
        stage = Stage.CLOSED
        deadline?.cancel(false)
        batchSettled?.cancel(false)
        link = null
        pairingGatt = null
        if (current(gatt)) closeGattTransport()
        constatchange[1] = System.currentTimeMillis()
    }

    private fun reconnectAfterFailure() {
        failures = (failures + 1).coerceAtMost(5)
        if (failures == 3 && mBluetoothGatt == null) {
            mActiveBluetoothDevice = null
            mActiveDeviceAddress = null
            foundtime = 0L
        }
        reconnect(5_000L shl (failures - 1))
    }

    private fun reconnect(delayMillis: Long, afterKnownReading: Boolean = false) {
        if (blocked || stop) return
        val previous = lastPublishedMillis
        val untilNextReading = previous + 299_000L - System.currentTimeMillis()
        val delay = if (afterKnownReading && untilNextReading > 0) untilNextReading else delayMillis
        worker.schedule({
            if (!blocked && !stop && mBluetoothGatt == null &&
                !SensorOwnershipRuntime.blocksLocalConnection(SerialNumber)) {
                SensorBluetooth.blueone?.connectToActiveDevice(this, 0L)
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun current(gatt: BluetoothGatt): Boolean = !stop && gatt === mBluetoothGatt
    private fun Link.isActive(): Boolean = link === this && current(gatt) && stage != Stage.CLOSED

    private fun event(gatt: BluetoothGatt, action: Link.() -> Unit) {
        worker.execute {
            val active = link ?: return@execute
            if (active.gatt === gatt && active.isActive()) active.runSafely { active.action() }
        }
    }

    private fun Link.runSafely(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            Log.e(TAG, "Managed session error at ${stage.name}: ${error.javaClass.simpleName}")
            if (isActive()) fail("CareSens Air failed during ${stage.name.lowercase()}")
        }
    }

    companion object {
        private const val TAG = "CareSensAirManaged"
        private val sharedWorker = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, TAG).apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        private val SERVICE = uuid("c4de9a20")
        private val SERIAL = uuid("c4de8544")
        private val SOFTWARE = uuid("c4de89ae")
        private val DATA = uuid("c4de9b74")
        private val CONTROL = uuid("c4de9ee4")
        private val REGISTRATION = uuid("c4dec61c")

        @JvmStatic
        fun createIfSelected(serial: String, pointer: Long): CareSensAirGattCallback? {
            if (!BuildConfig.DEBUG || serial.length != 16 || pointer == 0L) return null
            val key = MessageDigest.getInstance("SHA-256").digest(serial.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val directory = File(Applic.app.noBackupFilesDir, "caresens-air/$key")
            if (!directory.isDirectory && !BuildConfig.CARESENS_AIR_MANAGED) return null
            val engine = CareSensAirJavaEngine.available
            if (!directory.isDirectory) {
                if (!BuildConfig.CARESENS_AIR_MANAGED || engine == null ||
                    !Natives.airCanStartManaged(pointer) || Natives.getDeviceAddress(pointer, false) != null) return null
                // The directory pins this sensor to its experimental journal, including across
                // restarts and later installations of the default debug build.
                if (!directory.mkdirs() && !directory.isDirectory) return null
            }
            return CareSensAirGattCallback(serial, pointer, directory, engine?.let { it::open })
        }

        private fun uuid(prefix: String) = UUID.fromString("$prefix-5a9d-11e9-8647-d663bd873d93")
        private fun ascii(bytes: ByteArray) = String(bytes, Charsets.US_ASCII).trimEnd('\u0000')
        private fun nowSeconds() = System.currentTimeMillis() / 1_000L
    }
}
