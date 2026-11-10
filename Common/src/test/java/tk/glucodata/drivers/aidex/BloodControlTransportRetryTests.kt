package tk.glucodata.drivers.aidex

import java.io.File
import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.drivers.aidex.bloodcontrol.BloodControlHttpsTransport
import tk.glucodata.drivers.aidex.bloodcontrol.BloodControlProtocol
import tk.glucodata.drivers.aidex.bloodcontrol.BloodControlResponse

/** No network calls: exercise real route selection with synthetic signed responses. */
class BloodControlTransportRetryTests {
    private val primary = "https://5.23.53.98"
    private val secondary = "https://178.253.22.116"
    private val ca = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
        .map { File(it, "Common/src/main/res/raw/bloodcontrol_ca.pem") }.first(File::isFile).readBytes()

    @Test
    fun unsignedGatewayFailureUsesAndThenPrefersTheWorkingSecondaryRoute() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val body = "{\"allowlistAidex\":[]}".toByteArray()
        val signature = Signature.getInstance("RSASSA-PSS").apply {
            setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
            initSign(pair.private)
            update(body)
        }.sign()
        val valid = BloodControlResponse(200, body, Base64.getEncoder().encodeToString(signature))
        val routes = mutableListOf<String>()
        val transport = BloodControlHttpsTransport(ca, "synthetic", { 1_000L }) { route, _, _, _, _ ->
            routes += route
            if (route == primary) BloodControlResponse(502, "gateway failure".toByteArray(), null) else valid
        }

        val response = transport.post("/test", byteArrayOf())
        assertSame(valid, response)
        assertTrue(BloodControlProtocol.verify(response.body, response.signature, pair.public))
        assertEquals(listOf(primary, secondary), routes)
        routes.clear()
        assertSame(valid, transport.post("/test", byteArrayOf()))
        assertEquals(listOf(secondary), routes)
    }

    @Test
    fun twoServerFailuresTryEachAddressOnlyOnceWithoutRememberingAFailedRoute() {
        val routes = mutableListOf<String>()
        val transport = BloodControlHttpsTransport(ca, "synthetic", { 1_000L }) { route, _, _, _, _ ->
            routes += route
            BloodControlResponse(if (route == primary) 500 else 503, byteArrayOf(), null)
        }

        repeat(2) { assertEquals(503, transport.post("/test", byteArrayOf()).status) }
        assertEquals(listOf(primary, secondary, primary, secondary), routes)
    }

    @Test
    fun aRecoveredPrimaryClearsTheSecondaryPreference() {
        val routes = mutableListOf<String>()
        var primaryFailed = true
        val transport = BloodControlHttpsTransport(ca, "synthetic", { 1_000L }) { route, _, _, _, _ ->
            routes += route
            val failed = if (primaryFailed) route == primary else route == secondary
            BloodControlResponse(if (failed) 502 else 200, byteArrayOf(), null)
        }

        transport.post("/test", byteArrayOf())
        primaryFailed = false
        routes.clear()
        assertEquals(200, transport.post("/test", byteArrayOf()).status)
        assertEquals(listOf(secondary, primary), routes)
        routes.clear()
        transport.post("/test", byteArrayOf())
        assertEquals(listOf(primary), routes)
    }

    @Test
    fun authenticationRateLimitAndUnverifiedSuccessResponsesDoNotTriggerRouteRetry() {
        for (status in listOf(200, 400, 401, 403, 429)) {
            val routes = mutableListOf<String>()
            val response = BloodControlResponse(status, "unverified".toByteArray(), null)
            val transport = BloodControlHttpsTransport(ca, "synthetic", { 1_000L }) { route, _, _, _, _ ->
                routes += route
                response
            }
            assertSame(response, transport.post("/test", byteArrayOf()))
            assertEquals(listOf(primary), routes)
        }
    }

    @Test
    fun anAlternateReadTimeoutDoesNotFallBackAgainOrMaskTheTimeout() {
        val routes = mutableListOf<String>()
        val transport = BloodControlHttpsTransport(ca, "synthetic", { 1_000L }) { route, _, _, _, _ ->
            routes += route
            if (route == primary) BloodControlResponse(502, byteArrayOf(), null)
            else throw SocketTimeoutException("Read timed out")
        }
        try {
            transport.post("/test", byteArrayOf())
            fail("Expected read timeout")
        } catch (_: SocketTimeoutException) {
            assertEquals(listOf(primary, secondary), routes)
        }
    }
}
