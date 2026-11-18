// Libre3Handshake.kt — the Libre 3 BLE security handshake as a pure state machine.
//
// The driver feeds it notifications from the three security characteristics and performs the
// writes it returns, strictly in order and one GATT operation at a time (each command or message
// write completes before the next starts — the same serialisation the legacy callback got from
// chaining onCharacteristicWrite). Nothing here touches Android, so the whole exchange is unit
// tested against a simulated sensor.
//
// Full authorization (first pair, or a reconnect whose cached kAuth was refused):
//   → 01 StartAuthentication, 02 LoadCertificate, cert162, 03 CertificateLoadDone
//   ← [04]                    → 09 GetCertificate
//   ← [0A 8C] + cert140       → verify, 0D ValidateCertificate, ephemeral72, 0E EphemeralDone
//   ← [0F 41] + ephemeral65   → kAuth, 11 StartAuthorization
//   ← [08 17] + R1||nonce     → phase5 (54), 08 ChallengeLoadDone
//   ← [08 43] + phase6 (67)   → verify echoes, session keys
// Cached reconnect: start at 11 with the kAuth saved from the pairing that established it.

package tk.glucodata.drivers.libre3

import java.security.KeyPair
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.util.UUID

class Libre3Handshake(
    private val mode: Mode,
    private val blePin: ByteArray,
    private val identity: Libre3AppIdentity?,
    private val cachedKAuth: ByteArray?,
    private val random: SecureRandom = SecureRandom(),
    private val ephemeralFactory: () -> KeyPair = { Libre3P256.generateKeyPair(random) },
    private val signingKeys: List<ByteArray> = Libre3SensorCertificate.PATCH_SIGNING_KEYS,
) {
    enum class Mode { FULL, CACHED }

    enum class Stage {
        IDLE,
        AWAIT_CERT_ACCEPTED,
        AWAIT_CERT_READY,
        RECEIVING_SENSOR_CERT,
        AWAIT_EPHEMERAL_READY,
        RECEIVING_SENSOR_EPHEMERAL,
        AWAIT_CHALLENGE_READY,
        RECEIVING_CHALLENGE,
        AWAIT_PHASE6_READY,
        RECEIVING_PHASE6,
        DONE,
        FAILED,
    }

    enum class FailureKind {
        /** Programming/configuration: no identity, bad PIN length. Retrying will not help. */
        CONFIGURATION,
        /** The sensor's certificate is not signed by a known patch-signing key. */
        SENSOR_CERTIFICATE,
        /** Phase 6 did not verify: wrong kAuth (cached) or a key the sensor did not derive. */
        AUTHORIZATION,
        /** Unexpected bytes or ordering on the wire. */
        PROTOCOL,
    }

    sealed class Action {
        data class WriteCommand(val command: Int) : Action()
        class WriteMessage(val characteristic: UUID, val message: ByteArray) : Action() {
            override fun toString(): String = "WriteMessage(${characteristic.toString().take(8)}, ${message.size} B)"
        }
        class Authenticated(val session: Libre3Auth.SessionMaterial, val kAuth: ByteArray, val viaCache: Boolean) : Action()
        data class Failed(val kind: FailureKind, val reason: String) : Action()
    }

    var stage: Stage = Stage.IDLE
        private set

    private var assembler: Libre3Framing.SecurityAssembler? = null
    private var sensorCertificate: Libre3SensorCertificate? = null
    private var ephemeral: KeyPair? = null
    private var kAuth: ByteArray? = null
    private var challenge: Libre3Auth.Challenge? = null
    private var r2: ByteArray? = null

    val isFull: Boolean get() = mode == Mode.FULL

    fun start(): List<Action> {
        if (stage != Stage.IDLE) return fail(FailureKind.PROTOCOL, "handshake started twice")
        if (blePin.size != 4) return fail(FailureKind.CONFIGURATION, "BLE PIN must be 4 bytes")
        return when (mode) {
            Mode.FULL -> {
                val id = identity ?: return fail(FailureKind.CONFIGURATION, "no app identity installed")
                stage = Stage.AWAIT_CERT_ACCEPTED
                listOf(
                    Action.WriteCommand(CMD_START_AUTHENTICATION),
                    Action.WriteCommand(CMD_LOAD_CERTIFICATE),
                    Action.WriteMessage(Libre3Gatt.SEC_CERT_DATA, id.certificate),
                    Action.WriteCommand(CMD_CERTIFICATE_LOAD_DONE),
                )
            }
            Mode.CACHED -> {
                val key = cachedKAuth
                if (key == null || key.size != 16) return fail(FailureKind.CONFIGURATION, "no cached kAuth")
                kAuth = key.copyOf()
                stage = Stage.AWAIT_CHALLENGE_READY
                listOf(Action.WriteCommand(CMD_START_AUTHORIZATION))
            }
        }
    }

    fun onCommandResponse(value: ByteArray): List<Action> {
        if (value.isEmpty()) return fail(FailureKind.PROTOCOL, "empty command response")
        val kind = value[0].toInt() and 0xFF
        if (value.size == 1) {
            if (kind == RSP_CERTIFICATE_ACCEPTED && stage == Stage.AWAIT_CERT_ACCEPTED) {
                stage = Stage.AWAIT_CERT_READY
                return listOf(Action.WriteCommand(CMD_GET_CERTIFICATE))
            }
            return fail(FailureKind.PROTOCOL, "unexpected command response %02x in $stage".format(kind))
        }
        val length = value[1].toInt() and 0xFF
        return when {
            kind == RSP_CERTIFICATE_READY && stage == Stage.AWAIT_CERT_READY && length == Libre3SensorCertificate.SIZE -> {
                stage = Stage.RECEIVING_SENSOR_CERT
                assembler = Libre3Framing.SecurityAssembler(length)
                emptyList()
            }
            kind == RSP_EPHEMERAL_READY && stage == Stage.AWAIT_EPHEMERAL_READY && length == 65 -> {
                stage = Stage.RECEIVING_SENSOR_EPHEMERAL
                assembler = Libre3Framing.SecurityAssembler(length)
                emptyList()
            }
            kind == RSP_CHALLENGE_LOAD_DONE && stage == Stage.AWAIT_CHALLENGE_READY && length == Libre3Auth.R1_CHALLENGE_SIZE -> {
                stage = Stage.RECEIVING_CHALLENGE
                assembler = Libre3Framing.SecurityAssembler(length)
                emptyList()
            }
            kind == RSP_CHALLENGE_LOAD_DONE && stage == Stage.AWAIT_PHASE6_READY && length == Libre3Auth.PHASE6_WIRE_SIZE -> {
                stage = Stage.RECEIVING_PHASE6
                assembler = Libre3Framing.SecurityAssembler(length)
                emptyList()
            }
            else -> fail(FailureKind.PROTOCOL, "unexpected command response %02x len=%d in %s".format(kind, length, stage))
        }
    }

    fun onCertData(fragment: ByteArray): List<Action> {
        if (stage != Stage.RECEIVING_SENSOR_CERT && stage != Stage.RECEIVING_SENSOR_EPHEMERAL) {
            return fail(FailureKind.PROTOCOL, "certificate data in $stage")
        }
        val message = feed(fragment) ?: return if (stage == Stage.FAILED) failedAgain() else emptyList()
        return if (stage == Stage.RECEIVING_SENSOR_CERT) onSensorCertificate(message) else onSensorEphemeral(message)
    }

    fun onChallengeData(fragment: ByteArray): List<Action> {
        if (stage != Stage.RECEIVING_CHALLENGE && stage != Stage.RECEIVING_PHASE6) {
            return fail(FailureKind.PROTOCOL, "challenge data in $stage")
        }
        val message = feed(fragment) ?: return if (stage == Stage.FAILED) failedAgain() else emptyList()
        return if (stage == Stage.RECEIVING_CHALLENGE) onChallenge(message) else onPhase6(message)
    }

    private fun feed(fragment: ByteArray): ByteArray? = try {
        assembler?.feed(fragment)
    } catch (e: IllegalStateException) {
        lastFailure = Action.Failed(FailureKind.PROTOCOL, e.message ?: "bad fragment")
        stage = Stage.FAILED
        null
    }

    private var lastFailure: Action.Failed? = null

    private fun failedAgain(): List<Action> = listOfNotNull(lastFailure)

    private fun onSensorCertificate(raw: ByteArray): List<Action> {
        val cert = Libre3SensorCertificate(raw)
        if (cert.verifiedSigningKeyIndex(signingKeys) < 0) {
            return fail(FailureKind.SENSOR_CERTIFICATE, "sensor certificate signature does not verify")
        }
        try {
            Libre3P256.decodePoint(cert.staticPublicKey)
        } catch (e: Libre3P256.InvalidPointException) {
            return fail(FailureKind.SENSOR_CERTIFICATE, "sensor static key: ${e.message}")
        }
        sensorCertificate = cert
        val pair = ephemeralFactory()
        ephemeral = pair
        stage = Stage.AWAIT_EPHEMERAL_READY
        return listOf(
            Action.WriteCommand(CMD_VALIDATE_CERTIFICATE),
            Action.WriteMessage(Libre3Gatt.SEC_CERT_DATA, Libre3Auth.ephemeralWire(Libre3P256.encode(pair.public as ECPublicKey))),
            Action.WriteCommand(CMD_EPHEMERAL_DONE),
        )
    }

    private fun onSensorEphemeral(raw: ByteArray): List<Action> {
        val id = identity ?: return fail(FailureKind.CONFIGURATION, "no app identity installed")
        val cert = sensorCertificate ?: return fail(FailureKind.PROTOCOL, "ephemeral before certificate")
        val pair = ephemeral ?: return fail(FailureKind.PROTOCOL, "no phone ephemeral key")
        kAuth = try {
            Libre3Auth.authKey(id, pair.private as ECPrivateKey, raw, cert.staticPublicKey)
        } catch (e: Exception) {
            return fail(FailureKind.PROTOCOL, "key agreement failed: ${e.message}")
        }
        stage = Stage.AWAIT_CHALLENGE_READY
        return listOf(Action.WriteCommand(CMD_START_AUTHORIZATION))
    }

    private fun onChallenge(raw: ByteArray): List<Action> {
        val c = try {
            Libre3Auth.parseChallenge(raw)
        } catch (e: Libre3Auth.VerificationException) {
            return fail(FailureKind.PROTOCOL, e.message ?: "bad challenge")
        }
        val key = kAuth ?: return fail(FailureKind.PROTOCOL, "challenge before kAuth")
        val ourR2 = ByteArray(16).also { random.nextBytes(it) }
        challenge = c
        r2 = ourR2
        stage = Stage.AWAIT_PHASE6_READY
        return listOf(
            Action.WriteMessage(Libre3Gatt.SEC_CHALLENGE_DATA, Libre3Auth.phase5(key, c, ourR2, blePin)),
            Action.WriteCommand(CMD_CHALLENGE_LOAD_DONE),
        )
    }

    private fun onPhase6(raw: ByteArray): List<Action> {
        val key = kAuth ?: return fail(FailureKind.PROTOCOL, "phase 6 before kAuth")
        val c = challenge ?: return fail(FailureKind.PROTOCOL, "phase 6 before challenge")
        val ourR2 = r2 ?: return fail(FailureKind.PROTOCOL, "phase 6 before R2")
        val material = try {
            Libre3Auth.phase6(key, raw, c.r1, ourR2)
        } catch (e: Libre3Auth.VerificationException) {
            return fail(FailureKind.AUTHORIZATION, e.message ?: "phase 6 did not verify")
        }
        stage = Stage.DONE
        return listOf(Action.Authenticated(material, key.copyOf(), viaCache = mode == Mode.CACHED))
    }

    private fun fail(kind: FailureKind, reason: String): List<Action> {
        stage = Stage.FAILED
        val failure = Action.Failed(kind, reason)
        lastFailure = failure
        return listOf(failure)
    }

    companion object {
        const val CMD_START_AUTHENTICATION = 0x01
        const val CMD_LOAD_CERTIFICATE = 0x02
        const val CMD_CERTIFICATE_LOAD_DONE = 0x03
        const val CMD_CHALLENGE_LOAD_DONE = 0x08
        const val CMD_GET_CERTIFICATE = 0x09
        const val CMD_VALIDATE_CERTIFICATE = 0x0D
        const val CMD_EPHEMERAL_DONE = 0x0E
        const val CMD_START_AUTHORIZATION = 0x11

        const val RSP_CERTIFICATE_ACCEPTED = 0x04
        const val RSP_CHALLENGE_LOAD_DONE = 0x08
        const val RSP_CERTIFICATE_READY = 0x0A
        const val RSP_EPHEMERAL_READY = 0x0F
    }
}
