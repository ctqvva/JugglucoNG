package tk.glucodata.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.compose.rememberNavController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.R
import tk.glucodata.share.ican.ICanHealthCloudClient
import tk.glucodata.share.ican.ICanHealthCloudFollowerManager
import tk.glucodata.share.ican.ICanHealthCloudSettings
import tk.glucodata.ui.theme.AppTypography

/**
 * An expired iCan session makes every follower call throw
 * [ICanHealthCloudClient.SessionExpired]; the screen must show an error
 * state instead of crashing the process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "ru-w460dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ICanFollowersScreenSessionExpiredTests {
    @get:Rule val compose = createComposeRule()

    private class RejectingTransport : ICanHealthCloudClient.Transport {
        override fun post(url: String, jsonBody: String?): ICanHealthCloudClient.HttpResult =
            ICanHealthCloudClient.HttpResult(401, """{"code":401,"msg":"Not Authorized"}""")

        override fun get(url: String): ICanHealthCloudClient.HttpResult =
            ICanHealthCloudClient.HttpResult(401, """{"code":401,"msg":"Not Authorized"}""")
    }

    @Test
    fun expiredSessionShowsErrorInsteadOfCrashing() {
        val context = RuntimeEnvironment.getApplication()
        val manager = ICanHealthCloudFollowerManager(
            ICanHealthCloudClient(RejectingTransport()),
            ICanHealthCloudSettings(context),
        )
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                Box(Modifier.fillMaxSize().testTag("screen")) {
                    ICanHealthCloudFollowersContent(rememberNavController(), manager)
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(
                context.getString(R.string.ican_health_login_failed, ""),
                substring = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("screen").assertExists()
    }
}
