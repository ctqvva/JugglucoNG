@file:Suppress("DEPRECATION", "MissingPermission")

package tk.glucodata.drivers.ottai

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.util.UUID
import java.util.concurrent.Executors
import tk.glucodata.SensorBluetooth
import tk.glucodata.logi

/**
 * One dialog owns one serial BLE client. It is never registered as a managed sensor and cannot
 * activate. Closing it invalidates every outstanding GATT/network completion before releasing it;
 * successful shutdown lets the advisory cloud report finish without retaining BLE or the handler.
 */
internal class OttaiFirmwareSession private constructor(
    private val context: Context,
    private val sensorId: String,
    private val address: String,
    materials: OttaiRegistry.DeviceMaterials,
    mode: OttaiFirmwareMode,
    publish: (OttaiFirmwareState) -> Unit,
) : OttaiFirmwareIo {
    private val thread = HandlerThread("OttaiFirmware").also { it.start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val otaCloud = lazy { OttaiCloudClient.OtaClient(context) }
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({
            try { task.run() }
            finally { if (otaCloud.isInitialized()) otaCloud.value.close() }
        }, "OttaiFirmwareCloud")
    }
    private var closed = false
    private val discardLock = Any()
    private val pendingDiscards = mutableListOf<(Result<Unit>) -> Unit>()
    private var generation = 0
    private var gatt: BluetoothGatt? = null
    private var mtu = 23
    private var connectCompletion: ((Result<OttaiFirmwareGatt>) -> Unit)? = null
    private var readCompletion: ((Result<ByteArray>) -> Unit)? = null
    private var writeCompletion: ((Result<Unit>) -> Unit)? = null
    private var expected: UUID? = null
    private var expectedService: UUID? = null
    private var operationTimeout: Runnable? = null
    private var discoveryStarted = false
    private var discoveryGate: OttaiFirmwareDiscovery? = null
    private val recovery = AtomicFile(File(context.filesDir, "ottai-ota/$sensorId.bin"))
    private val controller = OttaiFirmwareController(sensorId, materials, this, mode) { state ->
        main.post { publish(state) }
    }

    fun install() { handler.post { controller.install() } }
    fun prepareOnly() { handler.post { controller.prepareOnly() } }
    fun recheckWithCloudAuth() { handler.post { controller.recheckWithCloudAuth() } }
    fun dispose() { handler.post { controller.close() } }

    fun discardRecovery(done: (Result<Unit>) -> Unit) {
        synchronized(discardLock) {
            if (closed) {
                completeDiscards(listOf(done)) { discardPendingTransfer(context, sensorId) }
                return
            }
            // Register before posting: close() can remove an accepted Handler message.
            pendingDiscards += done
            if (!handler.post {
                val requests = synchronized(discardLock) { takePendingDiscards() }
                completeDiscards(requests) { check(controller.discardRecovery()) { "transfer_in_progress" } }
            }) close(completed = false)
        }
    }

    /** Caller holds discardLock; claiming requests prevents reentrant close() completing twice. */
    private fun takePendingDiscards() = pendingDiscards.toList().also { pendingDiscards.clear() }

    private fun completeDiscards(requests: List<(Result<Unit>) -> Unit>, discard: () -> Unit) {
        if (requests.isEmpty()) return
        val result = runCatching(discard)
        main.post { requests.forEach { it(result) } }
    }

    companion object {
        internal fun savePreparedMaterials(context: Context, sensorId: String,
            fresh: OttaiRegistry.DeviceMaterials): OttaiRegistry.DeviceMaterials {
            // bindV3 echoes its request timestamp even for a sensor confirmed unactivated.
            // Preparation cannot start warmup; only the subsequent activation can supply that.
            val prepared = fresh.copy(activeTimeMs = 0L)
            require(OttaiRegistry.saveMaterials(context, sensorId, prepared)) { "materials_save" }
            return prepared
        }

        fun hasPendingTransfer(context: Context, sensorId: String): Boolean {
            val id = OttaiConstants.canonicalSensorId(sensorId)
            if (!OttaiConstants.looksLikeMac(id)) return false
            val file = File(context.filesDir, "ottai-ota/$id.bin")
            return file.exists() || File(file.path + ".bak").exists()
        }

        fun pendingMode(context: Context, sensorId: String, address: String): OttaiFirmwareMode? = runCatching {
            val id = OttaiConstants.canonicalSensorId(sensorId)
            require(OttaiConstants.looksLikeMac(id))
            val ble = requireNotNull(OttaiConstants.normalizeBleAddress(address))
            DataInputStream(AtomicFile(File(context.filesDir, "ottai-ota/$id.bin")).openRead()).use {
                OttaiFirmwareJournal.read(it, id, ble).mode
            }
        }.getOrNull()

        fun discardPendingTransfer(context: Context, sensorId: String) {
            val id = OttaiConstants.canonicalSensorId(sensorId)
            require(OttaiConstants.looksLikeMac(id))
            AtomicFile(File(context.filesDir, "ottai-ota/$id.bin")).delete()
            require(!hasPendingTransfer(context, id)) { "recovery_discard" }
        }

        fun start(context: Context, sensorId: String, address: String,
            mode: OttaiFirmwareMode = OttaiFirmwareMode.PREACTIVATION,
            publish: (OttaiFirmwareState) -> Unit): OttaiFirmwareSession? {
            val id = OttaiConstants.canonicalSensorId(sensorId)
            val ble = OttaiConstants.normalizeBleAddress(address) ?: return null
            if (!OttaiConstants.looksLikeMac(id) || !OttaiCloudClient.loadOtaSession(context)) return null
            if (OttaiRegistry.isV3CredentialBootstrapPending(context, id)) return null
            // A managed owner could reconnect and consume activation intent in parallel.
            if (OttaiRegistry.findRecord(context, id) != null || SensorBluetooth.mygatts().any { callback ->
                    callback is OttaiDriver && callback.matchesManagedSensorId(id)
                }) return null
            val materials = OttaiRegistry.loadMaterials(context, id)
            // Fresh OTA must precede bindV3. Its server-authenticated BLE handshake does
            // not require fetching account-bound credentials first.
            if (materials.authKeys == null && mode != OttaiFirmwareMode.PREACTIVATION) return null
            return OttaiFirmwareSession(context.applicationContext, id, ble, materials, mode, publish).also { session ->
                session.handler.post { session.controller.start() }
            }
        }
    }

    override fun connect(done: (Result<OttaiFirmwareGatt>) -> Unit) {
        if (closed) return
        disconnect()
        connectCompletion = done
        mtu = 23
        discoveryStarted = false
        log("connect address=$address")
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter?.isEnabled != true) { controller.fail("bluetooth_off"); return }
        runCatching {
            // This callback handler also serializes callbacks on pre-API-33 devices.
            gatt = adapter.getRemoteDevice(address).connectGatt(context, false, callbacks,
                android.bluetooth.BluetoothDevice.TRANSPORT_LE, android.bluetooth.BluetoothDevice.PHY_LE_1M_MASK, handler)
            require(gatt != null) { "connect_start" }
            armTimeout("connect_timeout", 60_000L)
        }.onFailure { controller.fail("connect_start") }
    }

    override fun rediscover(done: (Result<OttaiFirmwareGatt>) -> Unit) {
        val current = gatt
        if (!idle() || current == null) { controller.fail("rediscovery_unavailable"); return }
        discoveryGate?.close()
        discoveryGate = null
        connectCompletion = done
        discoveryStarted = false
        armTimeout("rediscovery_timeout")
        discover(current)
    }

    private fun discover(current: BluetoothGatt) {
        if (closed || gatt !== current || discoveryStarted) return
        discoveryStarted = true
        val started = runCatching { current.discoverServices() }.getOrDefault(false)
        log("discovery_requested started=$started mtu=$mtu")
        if (started) armTimeout("discovery_timeout") else controller.fail("discovery_start")
    }

    private val callbacks = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(current: BluetoothGatt, status: Int, newState: Int) = dispatch(current) {
            log("connection status=$status state=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                controller.fail("disconnected_status=$status")
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                current.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                // Like the normal driver, defer discovery until the last MTU callback settles.
                // Starting on the first of two callbacks can lose service discovery on Mi9T.
                discoveryGate?.close()
                val gate = OttaiFirmwareDiscovery(::after) { discover(current) }
                discoveryGate = gate
                val requested = current.requestMtu(247)
                log("mtu_requested started=$requested")
                gate.connected(requested)
            }
        }

        override fun onMtuChanged(current: BluetoothGatt, value: Int, status: Int) = dispatch(current) {
            if (status == BluetoothGatt.GATT_SUCCESS && value >= 23) mtu = value
            log("mtu=$mtu status=$status")
            discoveryGate?.mtuChanged()
        }

        override fun onServicesDiscovered(current: BluetoothGatt, status: Int) = dispatch(current) {
            if (!discoveryStarted || connectCompletion == null) {
                log("discovery_callback_ignored status=$status")
                return@dispatch
            }
            log("discovery_callback status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) { controller.fail("discovery_status=$status"); return@dispatch }
            current.services.forEach { service ->
                log("service=${service.uuid} chars=${service.characteristics.joinToString { "${it.uuid}:${it.properties}" }}")
            }
            val hasOta = characteristic(OttaiConstants.SERVICE_OTA, OttaiConstants.CHAR_OTA_CONTROL) != null &&
                characteristic(OttaiConstants.SERVICE_OTA, OttaiConstants.CHAR_OTA_DATA) != null
            val normal = characteristic(OttaiConstants.SERVICE_DEVICE_INFO, OttaiConstants.CHAR_SOFTWARE_VERSION) != null &&
                current.getService(OttaiConstants.SERVICE_AUTH) != null && current.getService(OttaiConstants.SERVICE_CGM) != null
            if (!normal && !hasOta) { controller.fail("services_missing"); return@dispatch }
            cancelTimeout()
            val done = connectCompletion
            connectCompletion = null
            done?.invoke(Result.success(OttaiFirmwareGatt(mtu, !normal, hasOta)))
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(current: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            readResult(current, ch, ch.value?.copyOf() ?: byteArrayOf(), status)
        }

        override fun onCharacteristicRead(current: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            readResult(current, ch, value.copyOf(), status)
        }

        override fun onCharacteristicWrite(current: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) = dispatch(current) {
            if (ch.uuid != expected || ch.service?.uuid != expectedService || writeCompletion == null) return@dispatch
            cancelTimeout()
            expected = null
            expectedService = null
            val done = writeCompletion
            writeCompletion = null
            if (status == BluetoothGatt.GATT_SUCCESS) done?.invoke(Result.success(Unit))
            else done?.invoke(Result.failure(IllegalStateException("write_status=$status")))
        }
    }

    private fun dispatch(current: BluetoothGatt, action: () -> Unit) {
        handler.post {
            if (!closed && current === gatt) runCatching(action).onFailure { controller.fail("gatt_callback") }
        }
    }

    private fun readResult(current: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) = dispatch(current) {
        if (ch.uuid != expected || ch.service?.uuid != expectedService || readCompletion == null) return@dispatch
        cancelTimeout()
        expected = null
        expectedService = null
        val done = readCompletion
        readCompletion = null
        if (status == BluetoothGatt.GATT_SUCCESS) done?.invoke(Result.success(value))
        else done?.invoke(Result.failure(IllegalStateException("read_status=$status")))
    }

    private fun characteristic(service: UUID, ch: UUID): BluetoothGattCharacteristic? = gatt?.getService(service)?.getCharacteristic(ch)

    private fun idle(): Boolean = readCompletion == null && writeCompletion == null && connectCompletion == null

    override fun read(service: UUID, characteristic: UUID, done: (Result<ByteArray>) -> Unit) {
        if (closed) return
        val ch = characteristic(service, characteristic)
        if (!idle() || ch == null) { controller.fail("read_unavailable"); return }
        expected = characteristic
        expectedService = service
        readCompletion = done
        armTimeout("read_timeout")
        log("read=$characteristic")
        if (!runCatching { gatt?.readCharacteristic(ch) == true }.getOrDefault(false)) controller.fail("read_start")
    }

    override fun readOptional(service: UUID, characteristic: UUID, done: (Result<ByteArray>) -> Unit) {
        if (closed) return
        if (idle() && characteristic(service, characteristic) == null) {
            done(Result.failure(IllegalStateException("optional_missing")))
        } else read(service, characteristic, done)
    }

    override fun write(service: UUID, characteristic: UUID, value: ByteArray, done: (Result<Unit>) -> Unit) {
        if (closed) return
        val ch = characteristic(service, characteristic)
        if (!idle() || ch == null || ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
            controller.fail("write_unavailable"); return
        }
        expected = characteristic
        expectedService = service
        writeCompletion = done
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ch.value = value
        armTimeout("write_timeout")
        // Firmware chunks and factory parameters are intentionally never dumped.
        if (characteristic != OttaiConstants.CHAR_OTA_DATA) log("write=$characteristic bytes=${value.size}")
        if (!runCatching { gatt?.writeCharacteristic(ch) == true }.getOrDefault(false)) controller.fail("write_start")
    }

    private fun armTimeout(code: String, delayMs: Long = 20_000L) {
        cancelTimeout()
        operationTimeout = Runnable { controller.fail(code) }.also { handler.postDelayed(it, delayMs) }
    }

    private fun cancelTimeout() {
        operationTimeout?.let { handler.removeCallbacks(it) }
        operationTimeout = null
    }

    override fun after(delayMs: Long, action: () -> Unit) { handler.postDelayed(action, delayMs) }

    private fun <T> work(task: () -> T, done: (Result<T>) -> Unit) {
        val owner = generation
        worker.execute {
            val result = runCatching(task)
            handler.post {
                if (!closed && owner == generation) {
                    // Never expose exception messages from HTTP (they may contain signed URLs).
                    done(result.fold({ Result.success(it) }, { error ->
                        val safe = setOf("ota_session", "ota_judge", "ota_response", "ota_flags", "download_missing",
                            "download_url", "download_filename", "target_version", "not_an_upgrade", "download_status",
                            "download_size", "download_redirects", "redirect", "gbl_size", "gbl_header", "gbl_truncated",
                            "gbl_tag", "gbl_header_duplicate", "gbl_length", "gbl_end", "gbl_crc", "materials_refresh",
                            "materials_identity", "materials_decrypt", "cancelled", "cloud_auth_parameter", "cloud_auth_material")
                        Result.failure(IllegalStateException(if (error is OttaiOtaCloudException) error.diagnostic
                            else error.message?.takeIf { it in safe } ?: "cloud_or_package"))
                    }))
                }
            }
        }
    }

    override fun prepareImage(version: String, done: (Result<OttaiFirmwareImage?>) -> Unit) {
        work({
            val offer = otaCloud.value.judgeOta(sensorId, version)
            log("offer required=${offer.required} allowed=${offer.allowed} image=${offer.downloadUrl != null}")
            if (!offer.required && !offer.allowed) null
            else {
                val url = offer.downloadUrl ?: throw IllegalArgumentException("download_missing")
                val target = OttaiOtaProtocol.targetVersion(url)
                require(OttaiOtaProtocol.isUpgrade(version, target)) { "not_an_upgrade" }
                val image = download(url)
                log("downloaded bytes=${image.size} sha256=${image.sha256}")
                OttaiFirmwareImage(version, target, image)
            }
        }, done)
    }

    override fun verifyCloudAuth(param: ByteArray, signature: ByteArray,
        done: (Result<OttaiCloudClient.V3AuthMaterial>) -> Unit) {
        work({ otaCloud.value.verifySensor(sensorId, param, signature) }, done)
    }

    private fun download(url: String): OttaiOtaPackage {
        var next = OttaiOtaProtocol.downloadUri(url)
        repeat(4) {
            val connection = next.toURL().openConnection() as HttpURLConnection
            try {
                // Never send cloud Authorization/device headers to the download host.
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 30_000
                connection.readTimeout = 30_000
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    next = OttaiOtaProtocol.downloadUri(next.resolve(connection.getHeaderField("Location") ?: error("redirect")).toString())
                } else {
                    require(status == 200 && connection.contentLengthLong <= OttaiOtaPackage.MAX_BYTES) { "download_status" }
                    val bytes = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            require(!Thread.currentThread().isInterrupted) { "cancelled" }
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(bytes.size() + count <= OttaiOtaPackage.MAX_BYTES) { "download_size" }
                            bytes.write(buffer, 0, count)
                        }
                    }
                    return OttaiOtaPackage.parse(bytes.toByteArray())
                }
            } finally { connection.disconnect() }
        }
        error("download_redirects")
    }

    override fun refreshMaterials(version: String, done: (Result<OttaiRegistry.DeviceMaterials>) -> Unit) {
        work({
            require(OttaiCloudClient.loadOtaSession(context)) { "ota_session" }
            val resp = otaCloud.value.bindMaterials(sensorId, version)
            require(OttaiConstants.canonicalSensorId(resp.mac) == sensorId &&
                OttaiOtaProtocol.materialsVersionMatches(version, resp.deviceVersion)) { "materials_identity" }
            OttaiCloudClient.toMaterials(context, sensorId, resp)?.takeIf { it.authKeys != null } ?: error("materials_decrypt")
        }) { result ->
            done(result.mapCatching { fresh ->
                // Commit only on the still-current session's serial owner, never in a late worker.
                savePreparedMaterials(context, sensorId, fresh)
            })
        }
    }

    override fun savedImage(): OttaiFirmwareImage? {
        if (!hasPendingTransfer(context, sensorId)) return null
        // Invalid data/address is a recovery failure, never permission to fetch a new package.
        return try {
            DataInputStream(recovery.openRead()).use { input ->
                OttaiFirmwareJournal.read(input, sensorId, address)
            }
        } catch (error: Exception) {
            throw IllegalStateException("recovery_image", error)
        }
    }

    override fun markTransferStarted(image: OttaiFirmwareImage) {
        val saved = savedImage()
        require(saved == null || (saved.source == image.source && saved.target == image.target &&
            saved.mode == image.mode && saved.image.sha256 == image.image.sha256)) { "recovery_package" }
        recovery.baseFile.parentFile?.mkdirs()
        val output = recovery.startWrite()
        try {
            val data = DataOutputStream(output)
            OttaiFirmwareJournal.write(data, sensorId, address, image)
            recovery.finishWrite(output)
        } catch (error: Exception) {
            recovery.failWrite(output)
            throw IllegalStateException("recovery_save")
        }
    }

    override fun reportVerified(image: OttaiFirmwareImage) {
        worker.execute {
            runCatching { otaCloud.value.reportOtaSuccess(sensorId, image.source, image.target) }
                .onFailure { log("report_failed") }
        }
    }

    override fun clearRecovery() {
        recovery.delete()
        require(!hasPendingTransfer(context, sensorId)) { "recovery_clear" }
    }
    override fun log(message: String) { logi("OttaiOTA") { message } }

    override fun disconnect() {
        generation++
        discoveryGate?.close()
        discoveryGate = null
        cancelTimeout()
        val old = gatt
        gatt = null
        connectCompletion = null
        readCompletion = null
        writeCompletion = null
        expected = null
        expectedService = null
        runCatching { old?.disconnect() }
        runCatching { old?.close() }
    }

    override fun close(completed: Boolean) {
        val requests = synchronized(discardLock) {
            if (closed) return
            closed = true
            disconnect()
            handler.removeCallbacksAndMessages(null)
            // Success has no pending material writes: only the best-effort OTA report may remain.
            // Drain it with the usual HTTP timeouts, without waiting here or retaining the GATT.
            // Cancellation/failure must still interrupt outstanding downloads/material requests.
            if (completed) worker.shutdown() else worker.shutdownNow()
            thread.quitSafely()
            takePendingDiscards()
        }
        // Retiring the owner cancels queued messages, including accepted discard requests.
        // BLE and material callbacks are now stopped, so finish those requests directly.
        completeDiscards(requests) { discardPendingTransfer(context, sensorId) }
    }
}
