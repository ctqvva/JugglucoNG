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

class OttaiRegionalTransportTests {
    private val headers = mapOf("deviceId" to "ottai-watch:a:synthetic-install", "appName" to "ottai-watch",
        "packageName" to "com.ottai.tag.watch", "versionCode" to "244301", "versionName" to "1.1.0",
        "timestamp" to System.currentTimeMillis().toString(), "Authorization" to "synthetic-token",
        "deviceFingerprinting" to "synthetic-fingerprint")

    private data class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

    /** Independent server counterpart, reached through the real cloud HTTP dispatch. */
    private inner class Server(val base: String = OttaiConstants.API_BASE_GLOBAL) {
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
            val identity = call.headers.getValue("webDeviceId")
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
            check(call.headers["webDeviceId"] == identity && call.headers["cipherSecretId"] == secretId)
            check(call.headers["versionCode"] == "10000000")
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
            base + (if (base.contains("www.")) "" else OttaiConstants.PREFIX) + path, body, headers, timeoutMs = 60_000,
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
            assertTrue(server.calls.all { it.url.startsWith("https://seas.ottai.com/cgm/security/app/server/") })
            assertFalse(server.calls.last().url.contains("001122334455"))
            assertEquals("GET", server.calls.last().method)
            val query = server.clearQueries.single()
            if (path.contains("signature=")) assertEquals("A+B= space&", query.getString("signature"))
            if (path.contains("pageSize=")) assertEquals("80", query.getString("pageSize"))
            if (!path.contains('?')) assertEquals(0, query.length())
        }
    }

    @Test fun loginBindAndCloudAuthBodiesAreEncryptedWithoutChangingTheSignedTimestamp() {
        for (path in listOf("/user/accountLogin", "/device/validateDeviceByMacV3",
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

    @Test fun typedBoxSerialReachesTheEncryptedLookupBeforeAnySensorOperation() {
        for (base in listOf(OttaiConstants.API_BASE_SYAI, OttaiConstants.API_BASE_GLOBAL)) {
            val server = Server(base)
            server.business = JSONObject("""{"code":"OK","data":{"mac":"B4893117F817"}}""")
            val resolved = OttaiCloudClient.resolveSetupSensorId("X01269290MMR", base) { path, body ->
                server.request("POST", path.removePrefix(OttaiConstants.PREFIX), body.toString())
            }
            assertEquals("B4893117F817", resolved.sensorId)
            assertEquals(2, server.calls.size)
            assertTrue(server.calls.last().url.endsWith("/device/getDeviceMacOrSnInfo2"))
            assertEquals("POST", server.calls.last().method)
            assertFalse(server.calls.last().body!!.contains("X01269290MMR"))
            assertEquals("X01269290MMR", server.clearBodies.single().getString("colorBoxTailSn"))
            assertTrue(server.clearBodies.single().isNull("mac"))
        }
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

    @Test fun cnOttaiWebUnrelatedHostsAndAlreadySecureRequestsKeepTheirTransport() {
        val urls = listOf(
            OttaiConstants.API_BASE + OttaiConstants.EP_GET_BIND_DEVICE,
            OttaiConstants.WEB_BASE_OTTAI + "/user/getUser",
            OttaiConstants.API_BASE_SYAI + "/cgm/security/app/server/user/apiToken",
            "https://api.syai.com.example.invalid" + OttaiConstants.EP_GET_BIND_DEVICE,
            "https://api.syai.com/cgm/app/server-other/user/apiToken",
        )
        for (url in urls) {
            val targets = mutableListOf<String>()
            val result = OttaiCloudClient.requestWithResult("GET", url, null, headers, openConnection = { target ->
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

    @Test fun syaiWebAndBothMobileGatewaysEncryptTheEntireLoginAndProfileChain() {
        for (base in listOf(OttaiConstants.WEB_BASE_SYAI, OttaiConstants.API_BASE_SYAI, OttaiConstants.API_BASE_GLOBAL)) {
            for ((method, path, body) in listOf(
                Triple("GET", "/user/apiToken?signature=synthetic-signature", null),
                Triple("POST", if (base == OttaiConstants.WEB_BASE_SYAI) "/user/mail/login" else "/user/accountLogin",
                    """{"password":"synthetic-password","signature":"synthetic-signature"}"""),
                Triple("GET", "/user/getUser", null),
            )) {
                val server = Server(base)
                val result = server.request(method, path, body)
                assertNull(result.failure)
                val exchange = server.calls.first()
                val actual = server.calls.last()
                assertEquals(if (base == OttaiConstants.WEB_BASE_SYAI)
                    "https://www.syai.com/api/cgm/security/official/server/security/exchangeKey"
                    else "$base/cgm/security/app/server/security/exchangeKey", exchange.url)
                assertTrue(actual.url.startsWith(if (base == OttaiConstants.WEB_BASE_SYAI)
                    "https://www.syai.com/api/cgm/security/web/" else "$base/cgm/security/app/server/"))
                assertFalse(actual.url.contains("synthetic-signature"))
                assertFalse(actual.body.orEmpty().contains("synthetic-password"))
                assertEquals("synthetic-token", actual.headers["Authorization"])
                assertEquals("ottai-watch", actual.headers["appName"])
                assertFalse(actual.headers.keys.any { it.startsWith("X-Forwarded", true) })
                if (base == OttaiConstants.WEB_BASE_SYAI) {
                    assertEquals("synthetic-fingerprint", actual.headers["deviceId"])
                    assertEquals(16, decode(actual.headers.getValue("cipherNonceId")).size)
                } else assertEquals(headers["deviceId"], actual.headers["deviceId"])
                assertEquals(headers["timestamp"], actual.headers["timestamp"])
            }
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
