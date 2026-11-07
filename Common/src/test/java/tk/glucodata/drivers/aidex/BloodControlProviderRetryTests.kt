package tk.glucodata.drivers.aidex

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.drivers.aidex.bloodcontrol.*
import java.net.ConnectException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Base64

/** Retry classification uses synthetic signatures and an injected transport, never real keys. */
class BloodControlProviderRetryTests {
    private val serial = "BCTEST0001"
    private var clock = 1_799_999_999_000L
    private val credentials = BloodControlCredentials("synthetic", ByteArray(32), pair.private, pair.public)

    private class Cache : BloodControlCache {
        private val values = mutableMapOf<String, Any>()
        override fun string(key: String) = values[key] as? String
        override fun long(key: String) = values[key] as? Long ?: 0L
        override fun put(key: String, value: String) { values[key] = value }
        override fun put(key: String, value: Long) { values[key] = value }
    }

    private fun signed(status: Int, json: String): BloodControlResponse {
        val body = json.toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("RSASSA-PSS").apply {
            setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
            initSign(pair.private)
            update(body)
        }.sign()
        return BloodControlResponse(status, body, Base64.getEncoder().encodeToString(signature))
    }

    private fun keys() = JSONObject().put("serialNumber", serial)
        .put("publicKey", "000102030405060708090a0b0c0d0e0f")
        .put("communicationKey", "101112131415161718191a1b1c1d1e1f").toString()

    private fun cachedAllowlist(at: Long = clock) = Cache().apply {
        put("allowlist", "[\"$serial\"]")
        put("allowlist_at", at)
    }

    @Test fun signedAllowlist400IsNegativeCachedAcrossProviderInstances() {
        val cache = Cache()
        val paths = mutableListOf<String>()
        var reject = true
        val transport = BloodControlTransport { path, _ ->
            paths += path
            if (path == BloodControlAidexKeyProvider.ALLOWLIST) {
                if (reject) signed(400, "{\"detail\":\"INVALID_REQUEST\"}")
                else signed(200, "{\"allowlistAidex\":[\"$serial\"]}")
            } else signed(200, keys())
        }
        fun provider() = BloodControlAidexKeyProvider(credentials, transport, cache, { clock })
        val initial = provider().request(serial)
        assertNull(initial.keys)
        assertNull(initial.retryAtMillis)
        assertEquals(clock + 60 * 60_000, cache.long("retry_allowlist"))
        clock += 59 * 60_000
        assertNull(provider().request(serial).retryAtMillis)
        assertEquals(listOf(BloodControlAidexKeyProvider.ALLOWLIST), paths)
        reject = false
        clock += 60_000
        assertNotNull(provider().request(serial).keys)
        assertEquals(listOf(BloodControlAidexKeyProvider.ALLOWLIST,
            BloodControlAidexKeyProvider.ALLOWLIST, BloodControlAidexKeyProvider.KEYS), paths)
    }

    @Test fun allowlistConnectionFailureExposesPersistedIncreasingRetryDeadline() {
        val cache = Cache()
        var calls = 0
        val transport = BloodControlTransport { _, _ -> calls++; throw ConnectException() }
        fun provider() = BloodControlAidexKeyProvider(credentials, transport, cache, { clock })
        val deadline = clock + 15 * 60_000
        assertEquals(deadline, provider().request(serial).retryAtMillis)
        assertEquals(deadline, provider().request(serial).retryAtMillis)
        assertEquals(1, calls)
        clock = deadline
        assertEquals(clock + 60 * 60_000, provider().request(serial).retryAtMillis)
        assertEquals(2, calls)
    }

    @Test fun signedRateLimitAndServerFailuresExposeActiveGlobalBackoff() {
        for (status in listOf(429, 500)) {
            val cache = Cache()
            var calls = 0
            val provider = BloodControlAidexKeyProvider(credentials, BloodControlTransport { _, _ ->
                calls++; signed(status, "{}")
            }, cache, { clock })
            val deadline = clock + 15 * 60_000
            assertEquals(deadline, provider.request(serial).retryAtMillis)
            assertEquals(deadline, provider.request(serial).retryAtMillis)
            assertEquals(1, calls)
            clock = deadline
            assertEquals(clock + 60 * 60_000, provider.request(serial).retryAtMillis)
            assertEquals(2, calls)
        }
    }

    @Test fun keyConnectionFailureCanRecoverAfterItsDeadline() {
        val cache = cachedAllowlist()
        var calls = 0
        var fail = true
        val provider = BloodControlAidexKeyProvider(credentials, BloodControlTransport { path, _ ->
            calls++
            if (path == BloodControlAidexKeyProvider.ALLOWLIST) signed(200, "{\"allowlistAidex\":[\"$serial\"]}")
            else if (fail) throw ConnectException()
            else signed(200, keys())
        }, cache, { clock })
        val deadline = clock + 15 * 60_000
        assertEquals(deadline, provider.request(serial).retryAtMillis)
        assertEquals(deadline, provider.request(serial).retryAtMillis)
        assertEquals(1, calls)
        clock = deadline
        fail = false
        val result = provider.request(serial)
        assertNotNull(result.keys)
        assertNull(result.retryAtMillis)
        assertEquals(3, calls)
    }

    @Test fun unavailableKeysAreTerminalAndDoNotSuggestAutomaticRetry() {
        var calls = 0
        val provider = BloodControlAidexKeyProvider(credentials, BloodControlTransport { _, _ ->
            calls++; signed(400, "{\"detail\":\"AIDEX_KEYS_NOT_AVAILABLE\"}")
        }, cachedAllowlist(), { clock })
        assertNull(provider.request(serial).retryAtMillis)
        assertNull(provider.request(serial).retryAtMillis)
        assertEquals(1, calls)
    }

    @Test fun authenticationAndSignatureFailuresStayTerminal() {
        for (status in listOf(401, 403, 200)) {
            var calls = 0
            val provider = BloodControlAidexKeyProvider(credentials, BloodControlTransport { _, _ ->
                calls++; BloodControlResponse(status, "invalid".toByteArray(), null)
            }, Cache(), { clock })
            assertNull(provider.request(serial).retryAtMillis)
            assertNull(provider.request(serial).retryAtMillis)
            assertEquals(1, calls)
        }
    }

    @Test fun staleAllowlistNetworkFailureDoesNotTaintSuccessfulKeyResult() {
        val paths = mutableListOf<String>()
        val provider = BloodControlAidexKeyProvider(credentials, BloodControlTransport { path, _ ->
            paths += path
            if (path == BloodControlAidexKeyProvider.ALLOWLIST) throw ConnectException()
            signed(200, keys())
        }, cachedAllowlist(clock - 6 * 60_000), { clock })
        val result = provider.request(serial)
        assertNotNull(result.keys)
        assertNull(result.retryAtMillis)
        assertEquals(listOf(BloodControlAidexKeyProvider.ALLOWLIST, BloodControlAidexKeyProvider.KEYS), paths)
    }

    @Test fun staleAllowlistMissPreservesRefreshDeadlineAcrossRecreationAndCanBecomeEligible() {
        val cache = Cache().apply {
            put("allowlist", "[\"BCTEST0002\"]")
            put("allowlist_at", clock - 6 * 60_000)
        }
        val paths = mutableListOf<String>()
        var failRefresh = true
        val transport = BloodControlTransport { path, _ ->
            paths += path
            if (path == BloodControlAidexKeyProvider.ALLOWLIST) {
                if (failRefresh) throw ConnectException()
                signed(200, "{\"allowlistAidex\":[\"$serial\"]}")
            } else signed(200, keys())
        }
        fun provider() = BloodControlAidexKeyProvider(credentials, transport, cache, { clock })
        val deadline = clock + 15 * 60_000
        assertEquals(deadline, provider().request(serial).retryAtMillis)
        assertEquals(0L, cache.long("retry_$serial"))
        assertEquals(listOf(BloodControlAidexKeyProvider.ALLOWLIST), paths)

        clock = deadline - 1
        assertEquals(deadline, provider().request(serial).retryAtMillis)
        assertEquals(0L, cache.long("retry_$serial"))
        assertEquals(listOf(BloodControlAidexKeyProvider.ALLOWLIST), paths)

        clock = deadline
        failRefresh = false
        val result = provider().request(serial)
        assertNotNull(result.keys)
        assertNull(result.retryAtMillis)
        assertEquals(listOf(BloodControlAidexKeyProvider.ALLOWLIST,
            BloodControlAidexKeyProvider.ALLOWLIST, BloodControlAidexKeyProvider.KEYS), paths)
    }

    @Test fun confirmedFreshAndNonTransientStaleMissesRemainNegativeCached() {
        for (stale in listOf(false, true)) {
            val cache = Cache().apply {
                put("allowlist", "[\"BCTEST0002\"]")
                put("allowlist_at", clock - if (stale) 6 * 60_000 else 0)
            }
            val paths = mutableListOf<String>()
            val provider = BloodControlAidexKeyProvider(credentials, BloodControlTransport { path, _ ->
                paths += path
                signed(400, "{\"detail\":\"INVALID_REQUEST\"}")
            }, cache, { clock })
            assertNull(provider.request(serial).retryAtMillis)
            assertEquals(clock + 5 * 60_000, cache.long("retry_$serial"))
            assertNull(provider.request(serial).retryAtMillis)
            assertEquals(if (stale) listOf(BloodControlAidexKeyProvider.ALLOWLIST) else emptyList<String>(), paths)
        }
    }

    @Test fun signedMalformedAllowlistIsTerminalRatherThanANetworkRetry() {
        for (body in listOf("not json", "{}")) {
            var calls = 0
            val provider = BloodControlAidexKeyProvider(credentials, BloodControlTransport { _, _ ->
                calls++; signed(200, body)
            }, Cache(), { clock })
            assertNull(provider.request(serial).retryAtMillis)
            assertNull(provider.request(serial).retryAtMillis)
            assertEquals(1, calls)
        }
    }

    companion object {
        private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}
