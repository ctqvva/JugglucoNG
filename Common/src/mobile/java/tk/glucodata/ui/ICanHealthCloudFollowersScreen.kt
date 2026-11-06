package tk.glucodata.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.share.ican.FollowerFriends
import tk.glucodata.share.ican.ICanHealthCloudFollowerManager
import tk.glucodata.share.ican.ICanHealthCloudSettings
import tk.glucodata.ui.components.AppTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ICanHealthCloudFollowersScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { ICanHealthCloudSettings(context) }
    val manager = remember { ICanHealthCloudFollowerManager(
        tk.glucodata.share.ican.ICanHealthCloudClient(),
        settings
    ) }

    var followers by remember { mutableStateOf<List<FollowerFriends>>(emptyList()) }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var generation by remember { mutableStateOf(0) }

    fun reload() {
        val gen = ++generation
        scope.launch {
            val list = withContext(Dispatchers.IO) { manager.list() }
            if (gen == generation) followers = list
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.ican_health_followers_title),
                onNavigateBack = { navController.popBackStack() },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.ican_health_invite_follower), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text(stringResource(R.string.ican_health_follower_email)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text(stringResource(R.string.ican_health_follower_phone)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.ican_health_follower_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(
                shapes = ButtonDefaults.shapes(),
                onClick = {
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                manager.invite(email, phone, "", name)
                            }
                            if (result != null) {
                                reload()
                                status = context.getString(R.string.ican_health_invited)
                                email = ""; phone = ""; name = ""
                            } else {
                                status = context.getString(R.string.ican_health_invite_failed, "")
                            }
                        } catch (e: Exception) {
                            status = context.getString(R.string.ican_health_invite_failed, e.message ?: "")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.ican_health_send_invite))
            }

            if (status.isNotEmpty()) {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }

            Text(stringResource(R.string.ican_health_followers), style = MaterialTheme.typography.titleMedium)
            followers.forEach { f ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(f.sharerName.ifBlank { f.email }, style = MaterialTheme.typography.bodyMedium)
                            Text(f.email, style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.ican_health_follower_status, f.status), style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(shapes = IconButtonDefaults.shapes(), onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { manager.remove(f.id) }
                                if (ok) {
                                    reload()
                                } else {
                                    status = context.getString(R.string.ican_health_invite_failed, "")
                                }
                            }
                        }) {
                            Text(stringResource(R.string.ican_health_remove))
                        }
                    }
                }
            }
        }
    }
}