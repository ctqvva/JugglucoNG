package tk.glucodata.drivers.aidex

import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.drivers.aidex.bloodcontrol.BloodControlAidex

class AiDexCnSessionExpiryTests {
    @Test fun expiredSessionIsInvalidatedBeforeReturningToFallback() {
        val calls = mutableListOf<String>()
        val keys = BloodControlAidex.requestCnKeys("expired-token",
            request = { calls += "request:$it"; AiDexCnResult(error = "expired", code = 800) },
            expire = { calls += "expire:$it" })
        assertNull(keys)
        assertEquals(listOf("request:expired-token", "expire:expired-token"), calls)
    }

    @Test fun successAndNonSessionErrorsDoNotClearTheAccount() {
        val keys = AiDexProvisionedKeys(ByteArray(16), ByteArray(16))
        assertSame(keys, BloodControlAidex.requestCnKeys("current-token",
            request = { AiDexCnResult(keys, code = 200) }, expire = { fail() }))
        assertNull(BloodControlAidex.requestCnKeys("current-token",
            request = { AiDexCnResult(error = "network", code = 500) }, expire = { fail() }))
    }

    @Test fun expirationTargetsOnlyTheTokenUsedByTheRequest() {
        var active = "old-token"
        BloodControlAidex.requestCnKeys(active,
            request = { active = "new-login"; AiDexCnResult(error = "expired", code = 800) },
            expire = { rejected -> if (active == rejected) active = "" })
        assertEquals("new-login", active)
    }
}
