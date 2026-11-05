package tk.glucodata.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import tk.glucodata.share.lumiflex.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LumiflexCloudTestApplication::class)
class LumiflexCloudViewModelTests {
    private val stores = mutableListOf<ViewModelStore>()
    private val app get() = RuntimeEnvironment.getApplication()
    private val settings get() = LumiflexCloudSettings(app)
    private val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
    private val credentials get() = LumiflexCloudCredentials(app) { key }

    private class Transport : LumiflexCloudClient.Transport {
        var codeRequests = 0
        var failPassword = true
        var onLogin: () -> Unit = {}
        var validationCode = 200
        var smsResponseLost = false
        override fun post(url: String, body: String, headers: Map<String, String>): LumiflexCloudClient.HttpResult {
            val response = when {
                url.endsWith("sendRegisterPhoneVerificationCode") -> {
                    codeRequests++
                    if (smsResponseLost) return LumiflexCloudClient.HttpResult(-1, "Response lost")
                    """{"code":200}"""
                }
                url.endsWith("setPassword") -> {
                    assertEquals("sms-token", headers["x-token"])
                    assertTrue(JSONObject(body).getString("password").isNotBlank())
                    if (failPassword) """{"code":400,"msg":"Try again"}""" else """{"code":200}"""
                }
                else -> {
                    onLogin()
                    """{"code":200,"data":{"token":"sms-token","userId":"u1"}}"""
                }
            }
            return LumiflexCloudClient.HttpResult(200, response)
        }

        override fun get(url: String, headers: Map<String, String>) = LumiflexCloudClient.HttpResult(
            200, if (validationCode == 200) """{"code":200,"data":{"userId":"u1"}}"""
            else """{"code":$validationCode,"msg":"Session expired"}""",
        )
    }

    @Before fun reset() { settings.clear() }
    @After fun clear() { stores.forEach { it.clear() } }

    private fun model(transport: Transport): LumiflexCloudViewModel {
        val store = ViewModelStore().also { stores += it }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LumiflexCloudViewModel(
                app, LumiflexCloudClient(transport, encrypt = { it }), credentials,
                Dispatchers.Unconfined, scheduleSync = {},
            ) as T
        }
        return ViewModelProvider(store, factory)[LumiflexCloudViewModel::class.java].also { ShadowLooper.idleMainLooper() }
    }

    @Test
    fun signupKeepsVerifiedSessionAfterPasswordFailureAndResumesAfterReopening() {
        val transport = Transport()
        val first = model(transport)
        first.showRegistration(true)
        first.phoneChanged("+7 (999) 123-45-67")
        first.requestCode()
        ShadowLooper.idleMainLooper()
        assertTrue(first.state.value.codeSent)
        first.requestCode()
        assertEquals(1, transport.codeRequests)
        first.codeChanged("123456")
        first.verifyCode()
        ShadowLooper.idleMainLooper()
        assertTrue(settings.isSignedIn())
        assertTrue(settings.passwordSetupRequired)
        assertFalse(settings.enabled)
        first.passwordChanged("pass1234")
        first.setPassword()
        ShadowLooper.idleMainLooper()
        assertEquals("Try again", first.state.value.error)
        assertEquals("sms-token", settings.token)
        assertTrue(settings.passwordSetupRequired)
        stores.last().clear()

        val reopened = model(transport)
        assertEquals("pass1234", reopened.state.value.password)
        assertTrue(reopened.state.value.passwordSetupRequired)
        transport.failPassword = false
        reopened.setPassword()
        ShadowLooper.idleMainLooper()
        assertFalse(settings.passwordSetupRequired)
        assertTrue(settings.isSignedIn())
        assertEquals(1, transport.codeRequests)
        assertTrue(settings.enabled)
    }

    @Test
    fun signOutDuringLoginRejectsTheLateSuccessfulReply() {
        val transport = Transport()
        val model = model(transport)
        model.accountChanged("+79991234567")
        model.passwordChanged("pass1234")
        transport.onLogin = { model.signOut() }
        model.signIn()
        ShadowLooper.idleMainLooper()
        assertFalse(settings.isSignedIn())
        assertFalse(model.state.value.session.signedIn)
        assertFalse(model.state.value.busy)
        assertEquals("pass1234", credentials.load().password)
    }

    @Test
    fun openingTheScreenValidatesTheTokenAndRetainsCredentialsWhenTheSessionExpired() {
        credentials.save("79991234567", "pass1234")
        settings.startSession("79991234567", "old-token", "u1")
        val model = model(Transport().apply { validationCode = 802 })
        assertFalse(model.state.value.session.signedIn)
        assertTrue(model.state.value.sessionExpired)
        assertEquals("pass1234", model.state.value.password)
        assertFalse(model.state.value.checkingSession)
    }

    @Test
    fun aLostSmsResponseStillAllowsTheReceivedCodeWithoutSendingAnotherSms() {
        val transport = Transport().apply { smsResponseLost = true }
        val model = model(transport)
        model.showRegistration(true)
        model.phoneChanged("79991234567")
        model.requestCode()
        ShadowLooper.idleMainLooper()
        assertTrue(model.state.value.codeSent)
        assertNotNull(model.state.value.error)
        model.codeChanged("123456")
        model.verifyCode()
        ShadowLooper.idleMainLooper()
        assertTrue(settings.isSignedIn())
        assertEquals(1, transport.codeRequests)
    }
}
