package tk.glucodata.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Factory reset is the system erasing the app, so what is ours to guarantee is how the answer
 * reaches the caller: a refused request is reported (the screen says so instead of closing on a
 * half-reset app), and a request that throws is a refusal, not a crash.
 *
 * The erase itself is the platform's and ends the process; it is not exercised here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class FactoryResetTests {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun anAcceptedRequestIsReportedAsDone() = runBlocking {
        assertTrue(DataManagement.factoryReset(context) { true })
    }

    @Test
    fun aRefusedRequestIsReportedSoTheScreenCanSayNothingWasErased() = runBlocking {
        assertFalse(DataManagement.factoryReset(context) { false })
    }

    @Test
    fun aRequestThatThrowsIsARefusalNotACrash() = runBlocking {
        assertFalse(DataManagement.factoryReset(context) { error("system refused") })
    }

    @Test
    fun theRequestIsMadeWithTheContextItWasGiven() = runBlocking {
        var seen: Context? = null
        DataManagement.factoryReset(context) { seen = it; true }
        assertEquals(context, seen)
    }
}
