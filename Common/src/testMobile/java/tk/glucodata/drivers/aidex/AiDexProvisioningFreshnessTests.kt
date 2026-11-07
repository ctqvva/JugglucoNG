package tk.glucodata.drivers.aidex

import android.app.Application
import android.content.Context
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AiDexProvisioningFreshnessTests {
    @Test fun sessionListenerRefreshesTheWizardAndCanBeRemoved() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("aidex_cn_provisioning", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("account_token", "opaque-expired-token").commit()
        var updates = 0
        val remove = AiDexProvisioningStore.addSessionListener(context) { updates++ }
        AiDexProvisioningStore.clearSession(context)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, updates)
        assertFalse(AiDexProvisioningStore.hasSession(context))
        remove()
        prefs.edit().putString("account_token", "opaque-new-token").commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, updates)
    }

}
