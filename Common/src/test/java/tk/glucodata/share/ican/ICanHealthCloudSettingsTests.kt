package tk.glucodata.share.ican

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tk.glucodata.Applic

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ICanHealthCloudSettingsTestApplication::class)
class ICanHealthCloudSettingsTests {

    @Test
    fun roundTripPersistRead() {
        val app = RuntimeEnvironment.getApplication() as ICanHealthCloudSettingsTestApplication
        val context = app
        val prefs = context.getSharedPreferences("ican_cloud", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val settings = ICanHealthCloudSettings(context)
        settings.account = "user@example.com"
        settings.token = "access-token"
        settings.refreshToken = "refresh-token"
        settings.tenantId = "tenant-123"
        settings.userId = "user-456"
        settings.lastSyncMs = 1234567890L

        val settings2 = ICanHealthCloudSettings(context)
        assertEquals("user@example.com", settings2.account)
        assertEquals("access-token", settings2.token)
        assertEquals("refresh-token", settings2.refreshToken)
        assertEquals("tenant-123", settings2.tenantId)
        assertEquals("user-456", settings2.userId)
        assertEquals(1234567890L, settings2.lastSyncMs)
    }

    @Test
    fun clearWipesAll() {
        val app = RuntimeEnvironment.getApplication() as ICanHealthCloudSettingsTestApplication
        val context = app
        val prefs = context.getSharedPreferences("ican_cloud", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val settings = ICanHealthCloudSettings(context)
        settings.account = "user@example.com"
        settings.token = "access-token"
        settings.refreshToken = "refresh-token"
        settings.tenantId = "tenant-123"
        settings.userId = "user-456"
        settings.lastSyncMs = 999L

        settings.clear()

        val settings2 = ICanHealthCloudSettings(context)
        assertEquals("", settings2.account)
        assertEquals("", settings2.token)
        assertEquals("", settings2.refreshToken)
        assertEquals("", settings2.tenantId)
        assertEquals("", settings2.userId)
        assertEquals(0L, settings2.lastSyncMs)
    }
}

class ICanHealthCloudSettingsTestApplication : Applic() {
    override fun onCreate() {}
}
