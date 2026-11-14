package tk.glucodata.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import tk.glucodata.R
import tk.glucodata.ui.components.AppTopBar

/** Reachable before any LibreView account, library or sensor exists. */
@Composable
fun Libre3SettingsScreen(navController: NavController) {
    Scaffold(topBar = {
        AppTopBar(title = stringResource(R.string.libre3_managed_title), onNavigateBack = { navController.popBackStack() })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Libre3ManagedDriverSection()
        }
    }
}
