package tk.glucodata.share.lumiflex

import android.content.Context
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LumiflexCloudTestApplication::class)
class LumiflexCloudCredentialsTests {
    private fun context() = RuntimeEnvironment.getApplication().also {
        it.getSharedPreferences("lumiflex_cloud", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun entrySurvivesNewStoreWithoutChangingTheAuthenticatedAccount() {
        val context = context()
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val settings = LumiflexCloudSettings(context)
        settings.startSession("signed-in", "token", "user")
        assertTrue(LumiflexCloudCredentials(context) { key }.save("patient+cloud@example.com", " keep spaces "))
        val restored = LumiflexCloudCredentials(context) { key }.load()
        assertEquals("patient+cloud@example.com", restored.account)
        assertEquals(" keep spaces ", restored.password)
        assertEquals("signed-in", settings.account)
        assertEquals("token", settings.token)
        assertTrue(LumiflexCloudSettings.isSetUp(context))
        val stored = context.getSharedPreferences("lumiflex_cloud", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(stored.contains("keep spaces"))
        assertFalse(stored.contains("patient+cloud@example.com"))
    }

    @Test
    fun signingOutKeepsTheEncryptedEntryForTheNextLogin() {
        val context = context()
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val store = LumiflexCloudCredentials(context) { key }
        store.save("79991234567", "password")
        val settings = LumiflexCloudSettings(context)
        settings.startSession("79991234567", "token", "user")
        settings.endSession()
        assertFalse(settings.isSignedIn())
        assertEquals("password", LumiflexCloudCredentials(context) { key }.load().password)
    }

    @Test
    fun missingKeystoreNeverStoresPlaintextAndWrongKeyDoesNotCrash() {
        val context = context()
        assertFalse(LumiflexCloudCredentials(context) { error("Keystore unavailable") }.save("account", "secret"))
        assertFalse(context.getSharedPreferences("lumiflex_cloud", Context.MODE_PRIVATE).all.values.joinToString().contains("secret"))
        assertTrue(LumiflexCloudCredentials(context) { SecretKeySpec(ByteArray(32) { 7 }, "AES") }.save("account", "secret"))
        assertEquals(LumiflexCloudCredentials.Draft(),
            LumiflexCloudCredentials(context) { SecretKeySpec(ByteArray(32) { 8 }, "AES") }.load())
    }

    @Test
    fun unfinishedEncryptedDraftKeepsSettingsReachableWithoutACloudSession() {
        val context = context()
        assertTrue(LumiflexCloudCredentials(context) { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
            .save("79991234567", "pass1234"))
        assertTrue(LumiflexCloudSettings.isSetUp(context))
        assertFalse(LumiflexCloudSettings(context).isSignedIn())
    }
}
