package tk.glucodata.drivers.ottai

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OttaiCnCloudTransportTests {
    private val headers = OttaiCloudClient.cnPhoneHeaders("n:synthetic-install", "synthetic-token",
        System.currentTimeMillis(), "synthetic-trace")

    private data class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

    /** Independent server counterpart, reached through the real cloud HTTP dispatch. */
    private inner class Server {
        val calls = mutableListOf<Call>()
        val clearQueries = mutableListOf<JSONObject>()
        val clearBodies = mutableListOf<JSONObject>()
        val connections = mutableListOf<HttpURLConnection>()
        var business = JSONObject("""{"code":"OK","data":{"result":"synthetic-materials"}}""")
        var exchangeFailure: JSONObject? = null
        var gatewayFailure: JSONObject? = null
        var corruptResponse = false
        var emptyResponse = false
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        private lateinit var aes: ByteArray
        private lateinit var signing: ByteArray
        private lateinit var aad: ByteArray
        private val secretId = "synthetic-secret"

        fun connection(url: String): HttpURLConnection = object : HttpURLConnection(URL(url)) {
            private val output = ByteArrayOutputStream()
            private var reply: OttaiNetSecurity.Reply? = null
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = output
            override fun getResponseCode(): Int {
                val call = Call(requestMethod, url, requestProperties.mapValues { it.value.single() },
                    output.toString("UTF-8").takeIf { it.isNotEmpty() })
                calls += call
                reply = answer(call)
                return reply!!.status
            }
            override fun getInputStream() = ByteArrayInputStream(reply!!.json.toString().toByteArray())
            override fun getErrorStream() = inputStream
        }.also { connections += it }

        private fun answer(call: Call): OttaiNetSecurity.Reply {
            val identity = call.headers.getValue("deviceId")
            if (call.url.endsWith("/security/exchangeKey")) {
                exchangeFailure?.let { return OttaiNetSecurity.Reply(401, it) }
                val request = JSONObject(requireNotNull(call.body))
                val x = request.getString("clientPubKeyX"); val y = request.getString("clientPubKeyY")
                check(request.getString("clientSignature") == sha(identity + x + y + request.getLong("clientTimestamp")))
                val params = (pair.public as ECPublicKey).params
                val client = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(
                    ECPoint(BigInteger(1, decode(x)), BigInteger(1, decode(y))), params))
                val shared = KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(client, true); generateSecret() }
                val expiry = System.currentTimeMillis() + 60_000L
                val prk = hmac(secretId.toByteArray(), shared)
                val info = "$identity#$expiry#$secretId".toByteArray()
                val first = hmac(prk, info + byteArrayOf(1))
                aes = first; signing = hmac(prk, first + info + byteArrayOf(2))
                aad = "$identity|$secretId".toByteArray()
                fun coord(value: BigInteger) = encode(value.toByteArray().takeLast(32).toByteArray().let {
                    ByteArray(32 - it.size) + it
                })
                val public = pair.public as ECPublicKey
                val sx = coord(public.w.affineX); val sy = coord(public.w.affineY)
                val time = System.currentTimeMillis()
                val nonce = ByteArray(12) { 3 }
                val encrypted = crypt(Cipher.ENCRYPT_MODE, nonce, (sx + sy).toByteArray())
                return OttaiNetSecurity.Reply(200, JSONObject().put("code", "OK").put("data", JSONObject()
                    .put("serverPubKeyX", sx).put("serverPubKeyY", sy).put("serverTimestamp", time)
                    .put("serverSignature", sha("$identity$sx$sy$time")).put("expireTime", expiry).put("secretId", secretId)
                    .put("ciphertext", encode(encrypted.dropLast(16).toByteArray()))
                    .put("authTag", encode(encrypted.takeLast(16).toByteArray())).put("iv", encode(nonce))))
            }
            gatewayFailure?.let { return OttaiNetSecurity.Reply(401, it) }
            if (emptyResponse) return OttaiNetSecurity.Reply(200, null)
            check(call.headers["cipherUserId"] == identity && call.headers["cipherSecretId"] == secretId)
            fun decrypt(envelope: JSONObject, kind: String): JSONObject {
                val text = envelope.getString("cipher${kind}Text")
                val signed = call.headers.getValue("cipherNonceId") + call.headers.getValue("cipherSeq") +
                    call.headers.getValue("cipherTimeNow") + text
                check(envelope.getString("cipher${kind}Signature") == encode(hmac(signing, signed.toByteArray())))
                return JSONObject(crypt(Cipher.DECRYPT_MODE, decode(envelope.getString("cipher${kind}Nonce")),
                    decode(text) + decode(envelope.getString("cipher${kind}Mac"))).toString(Charsets.UTF_8))
            }
            val query = call.url.substringAfter('?', "").takeIf { it.isNotEmpty() }?.split('&')?.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }.orEmpty()
            clearQueries += if (query.isEmpty()) JSONObject() else decrypt(JSONObject(query), "Param")
            call.body?.let { clearBodies += decrypt(JSONObject(it), "Body") }
            val nonce = ByteArray(12) { 7 }
            val encrypted = crypt(Cipher.ENCRYPT_MODE, nonce, business.toString().toByteArray())
            val tag = encrypted.takeLast(16).toByteArray()
            if (corruptResponse) tag[0] = (tag[0].toInt() xor 1).toByte()
            return OttaiNetSecurity.Reply(200, JSONObject().put("code", "OK").put("cipherSecretId", secretId)
                .put("cipherText", encode(encrypted.dropLast(16).toByteArray()))
                .put("cipherMac", encode(tag)).put("cipherNonce", encode(nonce)))
        }

        private fun crypt(mode: Int, nonce: ByteArray, bytes: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(bytes)
        }

        fun request(method: String, path: String, body: String? = null) = OttaiCloudClient.requestWithResult(method,
            OttaiConstants.API_BASE + OttaiConstants.PREFIX + path, body, headers, timeoutMs = 60_000,
            openConnection = ::connection)
    }

    @Test fun accountAndValidationQueriesUseTheSecureHostAndPreserveQueryValues() {
        for (path in listOf("/deviceBind/list?pageSize=80&pageNumber=1", "/deviceBind/getBindDevice",
            "/device/validateDeviceByMacV2?mac=001122334455&signature=A%2BB%3D+space%26")) {
            val server = Server()
            val result = server.request("GET", path)
            assertNull(result.failure)
            assertEquals("synthetic-materials", result.body!!.getJSONObject("data").getString("result"))
            assertEquals(2, server.calls.size)
            assertTrue(server.calls.all { it.url.startsWith("https://api.ottai.com/cgm/security/app/server/") })
            assertFalse(server.calls.last().url.contains("001122334455"))
            assertEquals("GET", server.calls.last().method)
            val query = server.clearQueries.single()
            if (path.contains("signature=")) assertEquals("A+B= space&", query.getString("signature"))
            if (path.contains("pageSize=")) assertEquals("80", query.getString("pageSize"))
            if (!path.contains('?')) assertEquals(0, query.length())
        }
    }

    @Test fun loginBindAndCloudAuthBodiesAreEncryptedWithoutChangingTheSignedTimestamp() {
        for (path in listOf("/user/smsCode", "/user/smsLogin", "/device/validateDeviceByMacV3",
            "/deviceBind/composite/bindV3", "/cgmAuth/verify")) {
            val server = Server()
            val body = JSONObject().put("signature", "synthetic-business-signature").put("activeTime", 123L)
                .put("timestamp", headers.getValue("timestamp")).put("paramStr", "synthetic-auth-input")
            assertNull(server.request("POST", path, body.toString()).failure)
            assertEquals(body.toString(), server.clearBodies.single().toString())
            assertFalse(server.calls.last().body!!.contains("synthetic-auth-input"))
            assertEquals("POST", server.calls.last().method)
            assertEquals(headers["timestamp"], server.calls.last().headers["timestamp"])
            assertEquals("synthetic-token", server.calls.last().headers["Authorization"])
            assertTrue(server.connections.all { it.readTimeout == 60_000 && !it.instanceFollowRedirects })
        }
    }

    @Test fun unbindingRetainsPutWithAnEncryptedBody() {
        val server = Server()
        assertNull(server.request("PUT", "/deviceBind/unBindDevice", """{"mac":"001122334455"}""").failure)
        assertEquals("PUT", server.calls.last().method)
        assertEquals("001122334455", server.clearBodies.single().getString("mac"))
    }

    @Test fun decryptedBusinessFailuresStillDriveV3ValidationAndAccountRecovery() {
        for (code in listOf(OttaiCloudClient.BIZ_UPGRADE_VERSION, OttaiCloudClient.BIZ_ALREADY_BINDING,
            OttaiCloudClient.BIZ_TOKEN_INVALID)) {
            val server = Server()
            server.business = JSONObject().put("code", code).put("message", "synthetic business failure")
                .put("data", JSONObject().put("keyA", "must-not-be-used"))
            val result = server.request("GET", "/device/validateDeviceByMacV2?mac=001122334455")
            assertNull(result.body)
            assertEquals(code, result.failure!!.code)
            assertEquals(200, result.failure.httpStatus)
            assertEquals(code == OttaiCloudClient.BIZ_TOKEN_INVALID, result.failure.isTokenInvalid)
            if (code == OttaiCloudClient.BIZ_UPGRADE_VERSION) assertTrue(OttaiCloudClient.shouldValidateViaV3(
                OttaiConstants.API_BASE, OttaiRegistry.SessionProfile.CN_PHONE, result.failure))
        }
    }

    @Test fun tokenRejectionAtExchangeOrGatewaySurvivesWithoutAnUnencryptedRetry() {
        for (atExchange in listOf(true, false)) {
            val server = Server()
            val rejection = JSONObject().put("code", OttaiCloudClient.BIZ_TOKEN_INVALID)
            if (atExchange) server.exchangeFailure = rejection else server.gatewayFailure = rejection
            val result = server.request("GET", "/deviceBind/getBindDevice")
            assertTrue(result.failure!!.isTokenInvalid)
            assertEquals(401, result.failure.httpStatus)
            assertNull(result.body)
            assertEquals(if (atExchange) 1 else 2, server.calls.size)
            assertTrue(server.calls.all { it.url.contains("/cgm/security/app/server/") })
        }
    }

    @Test fun tamperedResponseCannotProvideActivationMaterials() {
        val server = Server(); server.corruptResponse = true
        val result = server.request("POST", "/deviceBind/composite/bindV3", "{}")
        assertNull(result.body)
        assertEquals("ota_response_cipher", result.failure!!.text)
        assertEquals(2, server.calls.size)
    }

    @Test fun emptySecureResponseIsAFailureRatherThanMissingAccountMaterials() {
        val server = Server(); server.emptyResponse = true
        val result = server.request("GET", "/deviceBind/getBindDevice")
        assertNull(result.body)
        assertEquals("ota_request_http=200", result.failure!!.text)
        assertEquals(200, result.failure.httpStatus)
    }

    @Test fun watchCnWebAndAlreadySecureRequestsRetainTheirOriginalTransport() {
        val requests = listOf(
            OttaiConstants.API_BASE + OttaiConstants.EP_GET_BIND_DEVICE to (headers + ("appName" to "ottai-watch")),
            OttaiConstants.WEB_BASE_OTTAI + "/user/getUser" to headers,
            OttaiConstants.API_BASE + "/cgm/security/app/server/user/apiToken" to headers,
            "https://api.ottai.com.example.invalid" + OttaiConstants.EP_GET_BIND_DEVICE to headers,
        )
        for ((url, identity) in requests) {
            val targets = mutableListOf<String>()
            val result = OttaiCloudClient.requestWithResult("GET", url, null, identity, openConnection = { target ->
                targets += target
                object : HttpURLConnection(URL(target)) {
                    override fun connect() = Unit
                    override fun usingProxy() = false
                    override fun disconnect() = Unit
                    override fun getResponseCode() = 200
                    override fun getInputStream() = ByteArrayInputStream("""{"code":"OK","data":null}""".toByteArray())
                }
            })
            assertNull(result.failure)
            assertEquals(listOf(url), targets)
        }
    }

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun decode(value: String) = Base64.getDecoder().decode(value)
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hmac(key: ByteArray, input: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(input)
    }
}
