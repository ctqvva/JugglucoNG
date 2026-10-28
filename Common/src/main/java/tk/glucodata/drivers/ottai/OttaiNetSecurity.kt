package tk.glucodata.drivers.ottai

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** Machine diagnostics only; HTTP bodies, signed URLs, tokens and key material never escape. */
internal class OttaiOtaCloudException(
    val diagnostic: String,
    val businessCode: String = "",
    val httpStatus: Int = 0,
) : Exception(diagnostic)

/**
 * Authenticated Ottai/Syai network envelope. Each owner clears its ephemeral keys after its work.
 * See docs/ottai-login-network-security.md for source provenance and live verification.
 */
internal class OttaiNetSecurity(
    private val deviceId: String,
    private val exchange: (JSONObject) -> Reply,
    private val send: (String, String, Map<String, String>, Map<String, String>, String?) -> Reply,
    private val identityHeader: String = "cipherUserId",
    private val randomNonceId: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
    private val keyPair: () -> KeyPair = OttaiBleAuth::generateKeyPair,
    private val randomBytes: (Int) -> ByteArray = { size -> ByteArray(size).also { SecureRandom().nextBytes(it) } },
) {
    internal data class Reply(val status: Int, val json: JSONObject?)
    private class Secret(val id: String, val expires: Long, val aes: ByteArray, val signing: ByteArray) {
        var sequence = 0L
        fun clear() { aes.fill(0); signing.fill(0) }
    }
    private var secret: Secret? = null
    private var clockOffset = 0L
    private var lastNonce = 0L

    fun close() { secret?.clear(); secret = null }

    fun request(method: String, path: String, query: Map<String, String> = emptyMap(), body: String? = null): JSONObject =
        checked(requestReply(method, path, query, body), "ota_request")

    /** Keep authenticated business failures intact for the existing setup/activation fallbacks. */
    fun requestReply(method: String, path: String, query: Map<String, String> = emptyMap(), body: String? = null): Reply {
        checkCancellation()
        require(method in setOf("GET", "POST", "PUT") && path.startsWith('/') && !path.contains("..") && !path.contains('?'))
        var current = secret
        if (current == null || current.expires <= now()) {
            close()
            current = establish()
            secret = current
        }
        checkCancellation()
        val time = now()
        // Same positive decimal Snowflake layout as the client; monotonic within this owner.
        val nonceId = if (randomNonceId) encode(randomBytes(16)) else maxOf(((time - 1_288_834_974_657L) shl 22) or 4096L, lastNonce + 1).also { lastNonce = it }.toString()
        val sequence = ++current.sequence
        val headers = linkedMapOf("cipherNonceId" to nonceId, "cipherTimeNow" to time.toString(),
            "cipherSecretId" to current.id, identityHeader to deviceId, "cipherSeq" to sequence.toString())
        fun envelope(plain: String, kind: String): Map<String, String> {
            val nonce = randomBytes(12)
            val encrypted = crypt(Cipher.ENCRYPT_MODE, current.aes, nonce, plain.toByteArray(), aad(current.id))
            val text = encode(encrypted.copyOfRange(0, encrypted.size - 16))
            return linkedMapOf("cipher${kind}Text" to text,
                "cipher${kind}Mac" to encode(encrypted.takeLast(16).toByteArray()),
                "cipher${kind}Nonce" to encode(nonce),
                "cipher${kind}Signature" to encode(hmac(current.signing, "$nonceId$sequence$time$text".toByteArray())))
        }
        val encryptedQuery = if (query.isEmpty()) emptyMap() else envelope(JSONObject(query).toString(), "Param")
        val encryptedBody = body?.let { JSONObject(envelope(it, "Body")).toString() }
        val reply = send(method, path, headers, encryptedQuery, encryptedBody)
        if (reply.json == null) throw OttaiOtaCloudException("ota_request_http=${reply.status}", httpStatus = reply.status)
        // Gateways can reject a request before decryption (for example an expired account
        // token). Such failures cannot supply materials or authorize a sensor operation.
        if (!successful(reply)) return reply
        val outer = requireNotNull(reply.json)
        // A successful secure response must authenticate before its business result is used.
        if (!outer.has("cipherText")) throw OttaiOtaCloudException("ota_response_cipher")
        return protocol("ota_response_cipher") {
            require(outer.getString("cipherSecretId") == current.id)
            val nonce = decode(outer.getString("cipherNonce"))
            val cipher = decode(outer.getString("cipherText")) + decode(outer.getString("cipherMac"))
            require(nonce.size == 12 && decode(outer.getString("cipherMac")).size == 16)
            val plain = crypt(Cipher.DECRYPT_MODE, current.aes, nonce, cipher, aad(current.id))
            Reply(reply.status, JSONObject(plain.toString(Charsets.UTF_8)))
        }
    }

    private fun establish(): Secret {
        val pair = keyPair()
        val (x, y) = OttaiBleAuth.publicCoords(pair.public as ECPublicKey)
        val publicX = encode(x)
        val publicY = encode(y)
        var result: JSONObject? = null
        repeat(2) { attempt ->
            if (result == null) {
                val timestamp = now()
                val body = JSONObject().put("clientPubKeyX", publicX).put("clientPubKeyY", publicY)
                    .put("clientTimestamp", timestamp)
                    .put("clientSignature", sha256("$deviceId$publicX$publicY$timestamp"))
                val reply = exchange(body)
                val raw = reply.json
                if (attempt == 0 && raw?.optString("code") == "CLIENT_CLOCK_OFFSET" && raw.optLong("currentTime") > 0) {
                    clockOffset = raw.getLong("currentTime") - clock()
                } else result = protocol("ota_exchange_response") { checked(reply, "ota_exchange").getJSONObject("data") }
            }
        }
        return protocol("ota_exchange_crypto") {
            val data = requireNotNull(result)
            val serverX = data.getString("serverPubKeyX")
            val serverY = data.getString("serverPubKeyY")
            val timestamp = data.get("serverTimestamp").toString()
            require(MessageDigest.isEqual(sha256("$deviceId$serverX$serverY$timestamp").toByteArray(),
                data.getString("serverSignature").lowercase().toByteArray()))
            val id = data.get("secretId").toString()
            val expiry = data.get("expireTime").toString()
            require(id.isNotBlank() && id.length <= 256 && expiry.toLong() > now())
            val sx = decode(serverX); val sy = decode(serverY)
            require(sx.size == 32 && sy.size == 32)
            val public = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(
                ECPoint(BigInteger(1, sx), BigInteger(1, sy)), (pair.public as ECPublicKey).params))
            val shared = KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(public, true); generateSecret() }
            val derived = hkdf(shared, id.toByteArray(), "$deviceId#$expiry#$id".toByteArray())
            shared.fill(0)
            val created = Secret(id, expiry.toLong(), derived.copyOfRange(0, 32), derived.copyOfRange(32, 64))
            derived.fill(0)
            try {
                val nonce = decode(data.getString("iv"))
                val tag = decode(data.getString("authTag"))
                require(nonce.size == 12 && tag.size == 16)
                val plain = crypt(Cipher.DECRYPT_MODE, created.aes, nonce,
                    decode(data.getString("ciphertext")) + tag, aad(id))
                require(MessageDigest.isEqual(plain, (serverX + serverY).toByteArray()))
                created
            } catch (error: Exception) { created.clear(); throw error }
        }
    }

    private fun now() = clock() + clockOffset
    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw OttaiOtaCloudException("cancelled")
    }
    private fun aad(id: String) = "$deviceId|$id".toByteArray()

    private fun checked(reply: Reply, phase: String): JSONObject {
        val json = reply.json
        val code = json?.optString("code").orEmpty()
        if (!successful(reply)) {
            val safeCode = code.takeIf { it.matches(Regex("(?:NET_SECURITY_[A-Z_]+|AuthFailed_[A-Za-z_]+|CLIENT_CLOCK_OFFSET)")) }
            throw OttaiOtaCloudException(safeCode?.let { "${phase}_$it" } ?: "${phase}_http=${reply.status}",
                code.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,95}")) }.orEmpty(), reply.status)
        }
        return requireNotNull(json)
    }

    private fun successful(reply: Reply): Boolean {
        val code = reply.json?.optString("code").orEmpty()
        return reply.status in 200..299 && reply.json != null &&
            (code.isBlank() || code == "200" || code.equals("OK", ignoreCase = true))
    }

    private inline fun <T> protocol(code: String, block: () -> T): T = try { block() }
        catch (error: OttaiOtaCloudException) { throw error }
        catch (_: Exception) { throw OttaiOtaCloudException(code) }

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun decode(text: String) = Base64.getDecoder().decode(text)
    private fun sha256(text: String) = OttaiCrypto.bytesToHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(data)
    }
    private fun hkdf(shared: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val prk = hmac(salt, shared)
        val first = hmac(prk, info + byteArrayOf(1))
        val second = hmac(prk, first + info + byteArrayOf(2))
        prk.fill(0)
        return first + second
    }
    private fun crypt(mode: Int, key: ByteArray, nonce: ByteArray, bytes: ByteArray, aad: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(bytes)
        }
}
