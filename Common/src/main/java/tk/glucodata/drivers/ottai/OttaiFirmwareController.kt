package tk.glucodata.drivers.ottai

import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.util.UUID

internal enum class OttaiFirmwareStage {
    CONNECTING, AUTHENTICATING, CHECKING, READY, NO_UPDATE, INSTALLING, RECONNECTING,
    PREPARATION_READY, PREPARING, COMPLETE, FAILED, CLOSED,
}

internal enum class OttaiFirmwareMode { PREACTIVATION, STARTED_SENSOR }

internal data class OttaiFirmwareObservation(
    val commandStatus: Int,
    val maxActiveSeconds: Long? = null,
    val latestDataNo: Int? = null,
    val rtcSeconds: Long? = null,
)

internal data class OttaiFirmwareState(
    val stage: OttaiFirmwareStage = OttaiFirmwareStage.CONNECTING,
    val firmware: String = "",
    val target: String = "",
    val acknowledgedBytes: Int = 0,
    val totalBytes: Int = 0,
    val sha256: String = "",
    val recovery: Boolean = false,
    val errorCode: String = "",
    val mode: OttaiFirmwareMode = OttaiFirmwareMode.PREACTIVATION,
    val before: OttaiFirmwareObservation? = null,
    val after: OttaiFirmwareObservation? = null,
    val canPrepareParameters: Boolean = false,
) {
    val transferring: Boolean get() = stage in setOf(OttaiFirmwareStage.INSTALLING, OttaiFirmwareStage.RECONNECTING, OttaiFirmwareStage.PREPARING) ||
        (acknowledgedBytes > 0 && stage == OttaiFirmwareStage.AUTHENTICATING)
}

internal data class OttaiFirmwareImage(
    val source: String, val target: String, val image: OttaiOtaPackage,
    val mode: OttaiFirmwareMode = OttaiFirmwareMode.PREACTIVATION,
    val before: OttaiFirmwareObservation? = null,
)
internal data class OttaiFirmwareGatt(val mtu: Int, val bootloaderOnly: Boolean, val hasOta: Boolean)

/** All calls and completions belong to one serial owner (the session Handler). */
internal interface OttaiFirmwareIo {
    fun connect(done: (Result<OttaiFirmwareGatt>) -> Unit)
    fun rediscover(done: (Result<OttaiFirmwareGatt>) -> Unit)
    fun read(service: UUID, characteristic: UUID, done: (Result<ByteArray>) -> Unit)
    fun readOptional(service: UUID, characteristic: UUID, done: (Result<ByteArray>) -> Unit) = read(service, characteristic, done)
    fun write(service: UUID, characteristic: UUID, value: ByteArray, done: (Result<Unit>) -> Unit)
    fun after(delayMs: Long, action: () -> Unit)
    fun prepareImage(version: String, done: (Result<OttaiFirmwareImage?>) -> Unit)
    fun refreshMaterials(version: String, done: (Result<OttaiRegistry.DeviceMaterials>) -> Unit)
    fun savedImage(): OttaiFirmwareImage?
    fun markTransferStarted(image: OttaiFirmwareImage)
    fun reportVerified(image: OttaiFirmwareImage)
    fun clearRecovery()
    fun log(message: String)
    fun disconnect()
    fun close(completed: Boolean = false)
}

/**
 * Wizard-only experiment. No RTC, lifetime, retain or ACTIVATE_CMD writes exist
 * here. Firmware and factory-parameter experiments are explicit, separate actions.
 */
internal class OttaiFirmwareController(
    private val sensorId: String,
    private var materials: OttaiRegistry.DeviceMaterials,
    private val io: OttaiFirmwareIo,
    private val mode: OttaiFirmwareMode = OttaiFirmwareMode.PREACTIVATION,
    private val publish: (OttaiFirmwareState) -> Unit,
) {
    var state = OttaiFirmwareState(mode = mode)
        private set
    private var epoch = 0
    private var retired = false
    private var mtu = 23
    private var hasOta = false
    private var packageForInstall: OttaiFirmwareImage? = null
    private var installed: OttaiFirmwareImage? = null
    private var applicationRecovery: OttaiFirmwareImage? = null
    private var sessionKey = ""
    private var deviceRtcSeconds: Long? = null

    private fun update(stage: OttaiFirmwareStage, change: (OttaiFirmwareState) -> OttaiFirmwareState = { it }) {
        state = change(state.copy(stage = stage))
        io.log("mode=$mode stage=$stage source=${state.firmware} target=${state.target} ack=${state.acknowledgedBytes}/${state.totalBytes} recovery=${state.recovery} error=${state.errorCode}")
        publish(state)
    }

    private fun <T> completion(action: (T) -> Unit): (Result<T>) -> Unit {
        val owner = epoch
        var consumed = false
        return { result ->
            if (!retired && owner == epoch && !consumed) {
                consumed = true
                result.fold(
                    onSuccess = { runCatching { action(it) }.onFailure { fail(it.message ?: "protocol") } },
                    onFailure = { fail(it.message ?: "transport") },
                )
            }
        }
    }

    private fun later(delayMs: Long, action: () -> Unit) {
        val owner = epoch
        io.after(delayMs) { if (!retired && owner == epoch) runCatching(action).onFailure { fail(it.message ?: "protocol") } }
    }

    fun start() {
        if (retired || state.stage != OttaiFirmwareStage.CONNECTING) return
        io.connect(completion { connected(it, afterInstall = false) })
    }

    private fun connected(gatt: OttaiFirmwareGatt, afterInstall: Boolean) {
        mtu = gatt.mtu
        hasOta = gatt.hasOta
        if (gatt.bootloaderOnly) {
            require(!afterInstall) { "still_in_bootloader" }
            val saved = io.savedImage() ?: throw IllegalArgumentException("no_recovery_image")
            require(saved.mode == mode) { "recovery_mode" }
            packageForInstall = saved
            update(OttaiFirmwareStage.READY) {
                it.copy(firmware = saved.source, target = saved.target, totalBytes = saved.image.size,
                    sha256 = saved.image.sha256, recovery = true, before = saved.before)
            }
            return
        }
        read(OttaiConstants.SERVICE_DEVICE_INFO, OttaiConstants.CHAR_SOFTWARE_VERSION) { bytes ->
            // Log only this public revision characteristic, bounded and hex encoded so unusual
            // padding/encoding remains visible without inserting control characters into logs.
            io.log("software_revision length=${bytes.size} hex=${OttaiCrypto.bytesToHex(bytes.take(64).toByteArray())}")
            val reported = OttaiOtaProtocol.revisionText(bytes)
            state = state.copy(firmware = reported.take(64).map { if (it in ' '..'~') it else '?' }.joinToString(""))
            val version = OttaiOtaProtocol.version(reported)
                ?: throw IllegalArgumentException("firmware_profile")
            val saved = io.savedImage()
            require(saved == null || saved.mode == mode) { "recovery_mode" }
            val resumed = !afterInstall && saved != null && OttaiOtaProtocol.matchesTarget(version, saved.target)
            applicationRecovery = saved?.takeIf { !afterInstall && !resumed && OttaiOtaProtocol.sameVersion(it.source, version) }
            require(afterInstall || saved == null || resumed || applicationRecovery != null) { "recovery_version" }
            if (resumed) {
                installed = saved
                state = state.copy(before = saved.before, target = version, recovery = true)
            }
            if (afterInstall) require(installed?.let { OttaiOtaProtocol.matchesTarget(version, it.target) } == true) { "version_readback_mismatch" }
            state = state.copy(firmware = version)
            authenticate {
                // Ottai can change its handles after auth. Match the normal activation path's
                // re-discovery before any control writes; retain the authenticated session.
                io.rediscover(completion { refreshed ->
                    require(!refreshed.bootloaderOnly) { "post_auth_services" }
                    mtu = refreshed.mtu
                    hasOta = refreshed.hasOta
                    checkCommandStatus(version, afterInstall || resumed, resumed)
                })
            }
        }
    }

    private fun checkCommandStatus(version: String, afterInstall: Boolean, resumed: Boolean) {
        read(OttaiConstants.SERVICE_CGM, OttaiConstants.CHAR_COMMAND) { command ->
            val status = command.firstOrNull()?.toInt()?.and(255) ?: -1
            if (mode == OttaiFirmwareMode.PREACTIVATION) {
                require(OttaiConstants.commandNeedsActivation(status)) { "sensor_already_active_or_ended" }
                state = state.copy(canPrepareParameters = true)
                continueAfterStatus(version, afterInstall, resumed)
            } else {
                // A reset or ended state AFTER the transfer is an observation, not a failure.
                require(if (afterInstall) status in 0..4 else status == 3) { "started_sensor_status" }
                observe(status) { observation ->
                    io.log("observation=${if (afterInstall) "after" else "before"} $observation")
                    state = if (afterInstall) state.copy(after = observation) else state.copy(before = observation)
                    continueAfterStatus(version, afterInstall, resumed)
                }
            }
        }
    }

    private fun continueAfterStatus(version: String, afterInstall: Boolean, resumed: Boolean) {
        if (afterInstall) {
            if (!resumed) io.reportVerified(requireNotNull(installed))
            if (mode == OttaiFirmwareMode.STARTED_SENSOR) {
                io.clearRecovery()
                update(OttaiFirmwareStage.COMPLETE)
                retire()
            } else update(OttaiFirmwareStage.PREPARATION_READY)
        } else {
            val saved = applicationRecovery
            if (saved != null) {
                // The journal already owns this exact package. The old application still boots;
                // expose both explicit retry and authenticated parameter-only recovery offline
                // from the firmware host, without requiring a new offer/download.
                packageForInstall = saved
                io.log("normal_application_recovery_using_saved_package")
                require(hasOta || state.canPrepareParameters) { "ota_service_missing" }
                update(if (hasOta) OttaiFirmwareStage.READY else OttaiFirmwareStage.NO_UPDATE) {
                    it.copy(target = saved.target, totalBytes = saved.image.size, sha256 = saved.image.sha256,
                        recovery = true, before = saved.before ?: it.before)
                }
                return
            }
            update(OttaiFirmwareStage.CHECKING)
            val done = completion<Result<OttaiFirmwareImage?>> { result ->
                if (result.isFailure && state.canPrepareParameters) {
                    // A firmware download is not a prerequisite for factory provisioning.
                    update(OttaiFirmwareStage.NO_UPDATE) {
                        it.copy(errorCode = diagnosticCode(result.exceptionOrNull()?.message ?: "cloud_or_package"))
                    }
                    return@completion
                }
                val image = result.getOrThrow()
                packageForInstall = image?.copy(mode = mode, before = state.before)
                if (image == null) update(OttaiFirmwareStage.NO_UPDATE)
                else {
                    require(hasOta && image.source == version && OttaiOtaProtocol.isUpgrade(version, image.target)) { "ota_offer_mismatch" }
                    update(OttaiFirmwareStage.READY) {
                        it.copy(target = image.target, totalBytes = image.image.size, sha256 = image.image.sha256)
                    }
                }
            }
            io.prepareImage(version) { done(Result.success(it)) }
        }
    }

    private fun observe(status: Int, done: (OttaiFirmwareObservation) -> Unit) {
        optionalRead(OttaiConstants.SERVICE_DEVICE_INFO, OttaiConstants.CHAR_MAX_ACTIVE_TIME) { duration ->
            val seconds = duration?.let { OttaiOtaProtocol.maxActiveSeconds(it, sessionKey) }
            optionalRead(OttaiConstants.SERVICE_CGM, OttaiConstants.CHAR_GLUCOSE_LIVE) { live ->
                val number = live?.let { OttaiOtaProtocol.latestDataNo(it, sessionKey, state.firmware) }
                done(OttaiFirmwareObservation(status, seconds, number, deviceRtcSeconds))
            }
        }
    }

    private fun optionalRead(service: UUID, characteristic: UUID, action: (ByteArray?) -> Unit) {
        val done = completion<Result<ByteArray>> { result ->
            if (result.isFailure) io.log("optional_read_unavailable=$characteristic")
            action(result.getOrNull())
        }
        io.readOptional(service, characteristic) { done(Result.success(it)) }
    }

    private fun authenticate(done: () -> Unit) {
        update(OttaiFirmwareStage.AUTHENTICATING)
        val keys = materials.authKeys ?: throw IllegalArgumentException("credentials_missing")
        read(OttaiConstants.SERVICE_DEVICE_INFO, OttaiConstants.CHAR_CURRENT_TIME) { time ->
            require(time.size == 4) { "auth_time" }
            deviceRtcSeconds = OttaiBleAuth.bytesToIntLE(time).toLong() and 0xffffffffL
            read(OttaiConstants.SERVICE_AUTH, OttaiConstants.CHAR_AUTH_DEVICE_PARAM) { param ->
                require(param.size >= 68) { "auth_parameter" }
                val index = param[0].toInt() and 255
                require(index in keys.indices) { "auth_index" }
                val deviceX = param.copyOfRange(4, 36)
                val deviceY = param.copyOfRange(36, 68)
                read(OttaiConstants.SERVICE_AUTH, OttaiConstants.CHAR_AUTH_SIGN) { signature ->
                    // Firmware writes require positive identity proof, including on a manually
                    // entered BLE address. Do not inherit the normal driver's permissive fallback.
                    require(OttaiBleAuth.verifyDeviceSign(signature, OttaiCrypto.bytesToHex(keys[index]),
                        sensorId, deviceX, deviceY, param.copyOfRange(1, 4))) { "sensor_signature" }
                    val pair = OttaiBleAuth.generateKeyPair()
                    val (x, y) = OttaiBleAuth.publicCoords(pair.public as ECPublicKey)
                    val appIndex = keys.indices.random()
                    val appTime = OttaiBleAuth.appTime3(time)
                    val appParam = OttaiBleAuth.appAuthParameter(appIndex, appTime, x, y)
                    val appSign = OttaiBleAuth.authSign(keys[appIndex], OttaiCrypto.hexToBytes(sensorId), x, y, appTime)
                    write(OttaiConstants.SERVICE_AUTH, OttaiConstants.CHAR_AUTH_APP_PARAM, appParam) {
                        write(OttaiConstants.SERVICE_AUTH, OttaiConstants.CHAR_AUTH_SIGN, appSign) {
                            sessionKey = OttaiBleAuth.deriveSessionKey(OttaiCrypto.bytesToHex(deviceX),
                                OttaiCrypto.bytesToHex(deviceY), pair.private as ECPrivateKey)
                                ?: throw IllegalArgumentException("session_key")
                            done()
                        }
                    }
                }
            }
        }
    }

    fun install() {
        if (retired || state.stage != OttaiFirmwareStage.READY) return
        runCatching {
            val image = requireNotNull(packageForInstall)
            require(hasOta) { "ota_service_missing" }
            // Fresh mode must finish factory preparation after flashing. Check the offered
            // target (also for saved recovery images) before creating a journal or any OTA write.
            require(mode != OttaiFirmwareMode.PREACTIVATION || OttaiOtaProtocol.minor(image.target) >= 0) {
                "factory_parameter_profile"
            }
            // Durable before the first write. Recovery never downloads a different image.
            io.markTransferStarted(image)
            installed = image
            update(OttaiFirmwareStage.INSTALLING)
            val transfer = OttaiOtaTransfer(image.image, mtu)
            write(OttaiConstants.SERVICE_OTA, OttaiConstants.CHAR_OTA_CONTROL, byteArrayOf(0)) {
                sendNext(transfer)
            }
        }.onFailure { fail(it.message ?: "install") }
    }

    private fun sendNext(transfer: OttaiOtaTransfer) {
        if (transfer.complete) {
            write(OttaiConstants.SERVICE_OTA, OttaiConstants.CHAR_OTA_CONTROL, byteArrayOf(OttaiOtaProtocol.FINISH)) {
                update(OttaiFirmwareStage.RECONNECTING)
                io.disconnect()
                // A successful finish write is not proof that the image booted.
                later(3_000L) { io.connect(completion { connected(it, afterInstall = true) }) }
            }
        } else {
            val chunk = transfer.nextChunk()
            write(OttaiConstants.SERVICE_OTA, OttaiConstants.CHAR_OTA_DATA, chunk) {
                transfer.acknowledge()
                state = state.copy(acknowledgedBytes = transfer.acknowledgedBytes)
                // Limit trace volume while retaining each ACK in the in-memory state.
                if (transfer.complete || transfer.acknowledgedBytes / 4096 != (transfer.acknowledgedBytes - chunk.size) / 4096) {
                    update(OttaiFirmwareStage.INSTALLING)
                } else publish(state)
                sendNext(transfer)
            }
        }
    }

    fun prepareOnly() {
        if (retired || !state.canPrepareParameters ||
            state.stage !in setOf(OttaiFirmwareStage.READY, OttaiFirmwareStage.NO_UPDATE, OttaiFirmwareStage.PREPARATION_READY)) return
        runCatching { prepareParameters() }.onFailure { fail(it.message ?: "parameters") }
    }

    private fun prepareParameters() {
        val minor = OttaiOtaProtocol.minor(state.firmware)
        require(minor >= 0) { "factory_parameter_profile" }
        update(OttaiFirmwareStage.PREPARING) { it.copy(errorCode = "") }
        io.refreshMaterials(state.firmware, completion { fresh ->
            require(OttaiOtaProtocol.materialsVersionMatches(state.firmware, fresh.deviceVersion) && fresh.authKeys != null) { "materials_version" }
            materials = fresh
            // Construct and validate BOTH packets before issuing either write.
            val coefficients = if (minor >= 6) OttaiOtaProtocol.coefficientPacket(fresh.coefficient) else null
            val calibration = if (minor >= 8) OttaiOtaProtocol.calibrationPacket(fresh.calibrationValueK,
                fresh.calibrationValueB, sessionKey) else null
            require(coefficients == null || coefficients.size <= mtu - 3) { "coefficient_mtu" }
            fun finish() {
                io.clearRecovery()
                update(OttaiFirmwareStage.COMPLETE)
                retire()
            }
            fun writeCalibration() {
                if (calibration == null) finish()
                else write(OttaiConstants.SERVICE_DESTRUCTIVE, OttaiConstants.CHAR_DESTRUCTIVE, calibration) { finish() }
            }
            if (coefficients == null) writeCalibration()
            else write(OttaiConstants.SERVICE_DESTRUCTIVE, OttaiConstants.CHAR_DESTRUCTIVE, coefficients) { writeCalibration() }
        })
    }

    private fun read(service: UUID, characteristic: UUID, action: (ByteArray) -> Unit) =
        io.read(service, characteristic, completion(action))

    private fun write(service: UUID, characteristic: UUID, bytes: ByteArray, action: () -> Unit) {
        later(OttaiOtaProtocol.WRITE_DELAY_MS) { io.write(service, characteristic, bytes, completion { action() }) }
    }

    fun fail(code: String) {
        if (retired) return
        // Only bounded machine codes enter logs/UI; transport never returns bodies, URLs or keys.
        update(OttaiFirmwareStage.FAILED) { it.copy(errorCode = diagnosticCode(code)) }
        retire()
    }

    private fun diagnosticCode(code: String): String = code.take(80).replace(Regex("[^A-Za-z0-9_=-]"), "_")

    private fun retire() {
        retired = true
        epoch++
        sessionKey = ""
        io.close(completed = state.stage == OttaiFirmwareStage.COMPLETE)
    }

    fun close() {
        if (retired) return
        update(OttaiFirmwareStage.CLOSED)
        retire()
    }

    /** Explicit abandonment releases the local setup block, never changes sensor state. */
    fun discardRecovery(): Boolean {
        if (state.transferring) return false
        close() // Invalidate callbacks and stop BLE/worker before deleting durable state.
        io.clearRecovery()
        return true
    }
}
