package tk.glucodata.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import tk.glucodata.R
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.RevealSettingsSearchAnchor
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.util.findActivity
import tk.glucodata.ui.viewmodel.DashboardViewModel

/**
 * Everything that hands readings to another app on the same phone: the broadcast
 * formats and Health Connect. Network destinations (Nightscout, Mirror, …) stay on the
 * Settings tab, because each of them has a screen of its own.
 */
@Composable
fun PhoneAppsSettingsScreen(
    navController: NavController,
    viewModel: DashboardViewModel
) {
    val context = LocalContext.current
    val xdripEnabled by viewModel.xDripBroadcastEnabled.collectAsStateWithLifecycle()
    val patchedLibreEnabled by viewModel.patchedLibreBroadcastEnabled.collectAsStateWithLifecycle()
    val glucodataBroadcastEnabled by viewModel.glucodataBroadcastEnabled.collectAsStateWithLifecycle()
    val xdripFollowerEnabled by viewModel.xdripFollowerEnabled.collectAsStateWithLifecycle()
    val xdripReportAsLibre2 by viewModel.xdripReportAsLibre2.collectAsStateWithLifecycle()
    val broadcastComputedTrend by viewModel.broadcastComputedTrend.collectAsStateWithLifecycle()
    var healthConnectEnabled by rememberSaveable { mutableStateOf(PhoneAppsSettings.healthConnectEnabled()) }
    val healthConnectSupported = PhoneAppsSettings.healthConnectSupported

    val showLibre2Identity = PhoneAppsSettings.showsLibre2Identity(xdripEnabled, xdripReportAsLibre2)
    val showAppTrend = PhoneAppsSettings.showsAppTrend(
        xdripEnabled, patchedLibreEnabled, glucodataBroadcastEnabled, broadcastComputedTrend
    )

    val appColor = MaterialTheme.colorScheme.tertiary

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.settings_phone_apps_title),
                onNavigateBack = { navController.popBackStack() },
            )
        }
    ) { padding ->
        val listState = rememberLazyListState()
        // With large text the options group can start below the built rows; only page for
        // the options this screen currently shows, as search does.
        RevealSettingsSearchAnchor(
            listState,
            setOfNotNull(
                SettingsSearchAnchors.XDRIP, SettingsSearchAnchors.XDRIP_FOLLOWER,
                SettingsSearchAnchors.PATCHED_LIBRE, SettingsSearchAnchors.GLUCODATA,
                SettingsSearchAnchors.HEALTH_CONNECT.takeIf { healthConnectSupported },
                SettingsSearchAnchors.LIBRE2_IDENTITY.takeIf { showLibre2Identity },
                SettingsSearchAnchors.APP_TREND.takeIf { showAppTrend },
            )
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)
        ) {
            item(key = "apps") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_xdrip_broadcast_title),
                        subtitle = stringResource(R.string.settings_xdrip_broadcast_desc),
                        checked = xdripEnabled,
                        icon = Icons.Default.Radio,
                        iconTint = appColor,
                        position = CardPosition.TOP,
                        searchAnchor = SettingsSearchAnchors.XDRIP,
                        onCheckedChange = { viewModel.toggleXDripBroadcast(it) }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.xdrip_follower_title),
                        subtitle = stringResource(R.string.xdrip_follower_desc),
                        checked = xdripFollowerEnabled,
                        icon = Icons.Default.Sync,
                        iconTint = appColor,
                        position = CardPosition.MIDDLE,
                        searchAnchor = SettingsSearchAnchors.XDRIP_FOLLOWER,
                        onCheckedChange = { viewModel.setXdripFollowerEnabled(it) }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_patched_libre_title),
                        subtitle = stringResource(R.string.settings_patched_libre_desc),
                        checked = patchedLibreEnabled,
                        icon = Icons.Default.Share,
                        iconTint = appColor,
                        position = CardPosition.MIDDLE,
                        searchAnchor = SettingsSearchAnchors.PATCHED_LIBRE,
                        onCheckedChange = { viewModel.togglePatchedLibreBroadcast(it) }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_glucodata_title),
                        subtitle = stringResource(R.string.settings_glucodata_desc),
                        checked = glucodataBroadcastEnabled,
                        icon = Icons.AutoMirrored.Filled.Send,
                        iconTint = appColor,
                        position = if (healthConnectSupported) CardPosition.MIDDLE else CardPosition.BOTTOM,
                        searchAnchor = SettingsSearchAnchors.GLUCODATA,
                        onCheckedChange = { viewModel.toggleGlucodataBroadcast(it) }
                    )
                    if (healthConnectSupported) {
                        SettingsSwitchItem(
                            title = stringResource(R.string.health_connect_title),
                            subtitle = stringResource(R.string.settings_health_connect_desc),
                            checked = healthConnectEnabled,
                            icon = Icons.Default.HealthAndSafety,
                            iconTint = appColor,
                            position = CardPosition.BOTTOM,
                            searchAnchor = SettingsSearchAnchors.HEALTH_CONNECT,
                            onCheckedChange = { enabled ->
                                PhoneAppsSettings.setHealthConnectEnabled(context.findActivity(), enabled)
                                healthConnectEnabled = enabled
                            }
                        )
                    }
                }
            }

            if (showLibre2Identity || showAppTrend) {
                item(key = "options_label") {
                    SectionLabel(stringResource(R.string.settings_broadcast_options))
                }
                item(key = "options") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (showLibre2Identity) {
                            SettingsSwitchItem(
                                title = stringResource(R.string.xdrip_report_as_libre2_title),
                                subtitle = stringResource(R.string.xdrip_report_as_libre2_desc),
                                checked = xdripReportAsLibre2,
                                icon = Icons.Default.Badge,
                                iconTint = appColor,
                                position = if (showAppTrend) CardPosition.TOP else CardPosition.SINGLE,
                                searchAnchor = SettingsSearchAnchors.LIBRE2_IDENTITY,
                                onCheckedChange = { viewModel.setXdripReportAsLibre2(it) }
                            )
                        }
                        if (showAppTrend) {
                            SettingsSwitchItem(
                                title = stringResource(R.string.settings_app_trend_title),
                                subtitle = stringResource(R.string.settings_app_trend_desc),
                                checked = broadcastComputedTrend,
                                icon = Icons.AutoMirrored.Filled.TrendingUp,
                                iconTint = appColor,
                                position = if (showLibre2Identity) CardPosition.BOTTOM else CardPosition.SINGLE,
                                searchAnchor = SettingsSearchAnchors.APP_TREND,
                                onCheckedChange = { viewModel.setBroadcastComputedTrend(it) }
                            )
                        }
                    }
                }
            }
        }
    }
}
