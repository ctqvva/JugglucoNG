// Libre3Security.kt — Libre 3 BLE authorization material: app identity, sensor certificate,
// kAuth derivation, and the Phase 5 / Phase 6 challenge messages.
//
// Message formats and the key schedule follow RoundWhiteDiscKit (MIT, Pairing/*.swift and
// protocol.md) and match the byte counts the legacy Libre3GattCallback has used on Android:
//   app certificate 162 B, sensor certificate 140 B, ephemeral points 65 B (written padded to 72),
//   R1 challenge 23 B (R1_16 || nonce7), Phase 5 54 B, Phase 6 67 B.
//
//   Ze    = ECDH(phoneEphemeralPrivate, sensorEphemeralPublic)
//   Zs    = ECDH(appStaticPrivate,      sensorStaticPublic)
//   kAuth = SHA-256(00 00 00 01 || Ze || Zs)[0..16)
//
// This file ships no app credential. A sensor only authorizes an app certificate it accepts, so
// the user supplies an identity (P-256 private scalar + matching 162-byte certificate) through
// Libre3IdentityStore. The two public patch-signing keys below are verifier material only.

package tk.glucodata.drivers.libre3

import java.security.MessageDigest
import java.security.interfaces.ECPrivateKey
import org.json.JSONArray
import org.json.JSONObject

class Libre3AppIdentity private constructor(
    val label: String,
    private val scalar: ByteArray,
    val certificate: ByteArray,
) {
    /** The app's static public key, from bytes 33..98 of the certificate. */
    val staticPublicKey: ByteArray get() = certificate.copyOfRange(CERT_PUBKEY_START, CERT_PUBKEY_END)

    /** Certificate header bytes 0..2 (e.g. 03 03), the "security version" family it was issued for. */
    val certificateVersion: Int get() = ((certificate[0].toInt() and 0xFF) shl 8) or (certificate[1].toInt() and 0xFF)

    internal fun privateKey(): ECPrivateKey = Libre3P256.privateKey(scalar)

    internal fun scalarCopy(): ByteArray = scalar.copyOf()

    companion object {
        const val CERTIFICATE_SIZE = 162
        private const val CERT_PUBKEY_START = 33
        private const val CERT_PUBKEY_END = 98

        /** Validates sizes and that the scalar is the private key behind the certificate's point. */
        fun create(label: String, privateScalar: ByteArray, certificate: ByteArray): Libre3AppIdentity {
            require(label.isNotBlank()) { "identity label is blank" }
            require(privateScalar.size == 32) { "identity '$label': private key must be 32 bytes" }
            require(certificate.size == CERTIFICATE_SIZE) {
                "identity '$label': certificate must be $CERTIFICATE_SIZE bytes, got ${certificate.size}"
            }
            val certPoint = certificate.copyOfRange(CERT_PUBKEY_START, CERT_PUBKEY_END)
            Libre3P256.decodePoint(certPoint)
            val derived = Libre3P256.publicPointFromScalar(privateScalar)
            require(derived.contentEquals(certPoint)) { "identity '$label': private key does not match certificate" }
            return Libre3AppIdentity(label.trim(), privateScalar.copyOf(), certificate.copyOf())
        }

        /**
         * The interchange format RoundWhiteDiscKit documents, so one file works across clients:
         * `{"format":1,"curve":"P-256","identities":[{"label","privateKeyHex","certificateHex"}]}`.
         */
        fun parseJson(text: String): List<Libre3AppIdentity> {
            val root = JSONObject(text)
            require(root.optInt("format", -1) == 1) { "unsupported identity file format" }
            require(root.optString("curve") == "P-256") { "unsupported curve '${root.optString("curve")}'" }
            val list: JSONArray = root.getJSONArray("identities")
            require(list.length() > 0) { "identity file lists no identities" }
            val out = ArrayList<Libre3AppIdentity>(list.length())
            for (i in 0 until list.length()) {
                val entry = list.getJSONObject(i)
                val label = entry.optString("label").ifBlank { "identity${i + 1}" }
                val scalar = Libre3Bytes.parseHex(entry.optString("privateKeyHex"))
                    ?: throw IllegalArgumentException("identity '$label': privateKeyHex is not hex")
                val cert = Libre3Bytes.parseHex(entry.optString("certificateHex"))
                    ?: throw IllegalArgumentException("identity '$label': certificateHex is not hex")
                out += create(label, scalar, cert)
            }
            require(out.map { it.label }.toSet().size == out.size) { "identity labels must be unique" }
            return out
        }

        fun toJson(identities: List<Libre3AppIdentity>): String {
            val list = JSONArray()
            identities.forEach {
                list.put(
                    JSONObject()
                        .put("label", it.label)
                        .put("privateKeyHex", Libre3Bytes.hex(it.scalar))
                        .put("certificateHex", Libre3Bytes.hex(it.certificate))
                )
            }
            return JSONObject().put("format", 1).put("curve", "P-256").put("identities", list).toString()
        }
    }
}

/** 140-byte sensor certificate: header11 || staticPublicKey65 || ECDSA r||s 64 over bytes 0..76. */
class Libre3SensorCertificate(val raw: ByteArray) {
    init {
        require(raw.size == SIZE) { "sensor certificate must be $SIZE bytes, got ${raw.size}" }
    }

    val staticPublicKey: ByteArray get() = raw.copyOfRange(11, 76)

    /** Index into [signingKeys] whose key verifies the signature, or -1. */
    fun verifiedSigningKeyIndex(signingKeys: List<ByteArray> = PATCH_SIGNING_KEYS): Int =
        signingKeys.indexOfFirst { Libre3P256.verify(it, raw.copyOfRange(0, 76), raw.copyOfRange(76, 140)) }

    companion object {
        const val SIZE = 140

        /**
         * Public P-256 keys that sign Libre 3 sensor certificates (families 0 and 1). Public verifier
         * material, also published in RoundWhiteDiscKit (SensorCert.swift).
         */
        val PATCH_SIGNING_KEYS: List<ByteArray> = listOf(
            Libre3Bytes.parseHex(
                "04b69d1734f5e425bcc0576ad1f727c1311c90b6ea986f006e7e9f9096f6a8284f" +
                    "12bf7ddfe154a3f1d45a0f2734ecabca6b9eb56ee4ecca87853ad853b6a64180"
            )!!,
            Libre3Bytes.parseHex(
                "04a2d8478990945f70a9570ade07b155bc904d2d380647587b123917013" +
                    "09bd10b5990c4c47c47f1f08046cb6f2de0748d1fa7f73790ec9d8dd637212778528838"
            )!!,
        )
    }
}

object Libre3Auth {

    const val R1_CHALLENGE_SIZE = 23
    const val PHASE5_PLAINTEXT_SIZE = 36
    const val PHASE5_WIRE_SIZE = 54
    const val PHASE6_WIRE_SIZE = 67
    const val EPHEMERAL_WIRE_SIZE = 72
    const val TAG_SIZE = 4

    class VerificationException(message: String) : Exception(message)

    fun authKey(identity: Libre3AppIdentity, phoneEphemeral: ECPrivateKey, sensorEphemeral: ByteArray, sensorStatic: ByteArray): ByteArray {
        val ze = Libre3P256.ecdh(phoneEphemeral, Libre3P256.publicKey(sensorEphemeral))
        val zs = Libre3P256.ecdh(identity.privateKey(), Libre3P256.publicKey(sensorStatic))
        val digest = MessageDigest.getInstance("SHA-256").run {
            update(byteArrayOf(0, 0, 0, 1))
            update(ze)
            update(zs)
            digest()
        }
        return digest.copyOf(16)
    }

    /** Phase 3 write: the phone's ephemeral point, zero padded to the four-fragment size. */
    fun ephemeralWire(publicKey65: ByteArray): ByteArray = publicKey65.copyOf(EPHEMERAL_WIRE_SIZE)

    data class Challenge(val r1: ByteArray, val nonce: ByteArray)

    fun parseChallenge(raw: ByteArray): Challenge {
        if (raw.size != R1_CHALLENGE_SIZE) throw VerificationException("R1 challenge is ${raw.size} bytes, expected 23")
        return Challenge(raw.copyOfRange(0, 16), raw.copyOfRange(16, 23))
    }

    /** Phase 5 wire: CCM(kAuth, nonce7, R1 || R2 || blePin) ct36 || tag4 || 14 zero bytes. */
    fun phase5(kAuth: ByteArray, challenge: Challenge, r2: ByteArray, blePin: ByteArray): ByteArray {
        require(r2.size == 16) { "R2 must be 16 bytes" }
        require(blePin.size == 4) { "BLE PIN must be 4 bytes" }
        val plaintext = challenge.r1 + r2 + blePin
        val sealed = Libre3AesCcm(kAuth).encrypt(challenge.nonce, plaintext, TAG_SIZE)
        return sealed.copyOf(PHASE5_WIRE_SIZE)
    }

    data class SessionMaterial(val kEnc: ByteArray, val ivEnc: ByteArray)

    /**
     * Phase 6: ct56 || tag4 || nonce7, decrypting to R2 || R1 || kEnc16 || ivEnc8. Both challenges
     * must be echoed, which is what proves the sensor derived the same kAuth.
     */
    fun phase6(kAuth: ByteArray, raw: ByteArray, expectedR1: ByteArray, expectedR2: ByteArray): SessionMaterial {
        if (raw.size != PHASE6_WIRE_SIZE) throw VerificationException("Phase 6 is ${raw.size} bytes, expected 67")
        val nonce = raw.copyOfRange(60, 67)
        val plaintext = try {
            Libre3AesCcm(kAuth).decrypt(nonce, raw.copyOfRange(0, 60), TAG_SIZE)
        } catch (e: Libre3AesCcm.AuthenticationException) {
            throw VerificationException("Phase 6 tag does not verify under kAuth")
        }
        if (!plaintext.copyOfRange(0, 16).contentEquals(expectedR2)) throw VerificationException("Phase 6 R2 echo mismatch")
        if (!plaintext.copyOfRange(16, 32).contentEquals(expectedR1)) throw VerificationException("Phase 6 R1 echo mismatch")
        return SessionMaterial(plaintext.copyOfRange(32, 48), plaintext.copyOfRange(48, 56))
    }
}
