package tk.glucodata.ui.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import kotlinx.coroutines.launch
import tk.glucodata.R
import tk.glucodata.data.settings.FloatingSettingsRepository
import tk.glucodata.ui.viewmodel.DashboardViewModel
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.CompactSheetDragHandle
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.StableModalBottomSheet
// import tk.glucodata.ui.components.StyledSwitch // Unused now

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloatingSettingsSheet(
    viewModel: DashboardViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val repository = viewModel.floatingRepository
    val scope = rememberCoroutineScope()

    // State Collection
    val isEnabled by repository.isEnabled.collectAsState(initial = false)
    val isTransparent by repository.isTransparent.collectAsState(initial = false)
    val showSecondary by repository.showSecondary.collectAsState(initial = false)
    val fontSource by repository.fontSource.collectAsState(initial = "APP")
    val fontSize by repository.fontSize.collectAsState(initial = FloatingSettingsRepository.DEFAULT_FONT_SIZE)
    val fontWeight by repository.fontWeight.collectAsState(initial = "REGULAR")
    val showArrow by repository.showArrow.collectAsState(initial = true)

    // Permission State
    var hasPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    
    // Resume detection to re-check permission
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        hasPermission = Settings.canDrawOverlays(context)
    }

    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false), // 1. Allow partial expansion
        dragHandle = { CompactSheetDragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            Text(
                stringResource(R.string.floatglucose),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(24.dp))

            // Master Switch
            SettingsSwitchItem(
                title = stringResource(R.string.enable_overlay),
                subtitle = stringResource(R.string.enable_overlay_desc),
                checked = isEnabled,
                onCheckedChange = { check ->
                    if (check && !hasPermission) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } else {
                        viewModel.toggleFloatingGlucose(check)
                    }
                },
                icon = Icons.Default.Layers, // 2. Prominent Icon
                position = CardPosition.SINGLE
            )

            if (isEnabled && !hasPermission) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.floating_permission_required),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.appearance), topPadding = 0.dp)
            Spacer(modifier = Modifier.height(8.dp))

            // Background Style
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.background), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !isTransparent,
                        onClick = { repository.setTransparent(false) },
                        label = { Text(stringResource(R.string.filled)) }
                    )
                    FilterChip(
                        selected = isTransparent,
                        onClick = { repository.setTransparent(true) },
                        label = { Text(stringResource(R.string.transparent)) }
                    )
                }
            }
            
            // Corner Radius (Only if Filled)
            if (!isTransparent) {
                Spacer(modifier = Modifier.height(8.dp))
                val cornerRadius by repository.cornerRadius.collectAsState(initial = 28f)
                val opacity by repository.backgroundOpacity.collectAsState(initial = FloatingSettingsRepository.DEFAULT_BACKGROUND_OPACITY)
                
                Text(stringResource(R.string.corner_radius_dp, cornerRadius.toInt()), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = cornerRadius,
                    onValueChange = { repository.setCornerRadius(it) },
                    valueRange = 0f..48f,
                    steps = 24
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.background_opacity_percent, (opacity * 100).toInt()), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = opacity,
                    onValueChange = { repository.setBackgroundOpacity(it) },
                    valueRange = 0.1f..1.0f,
                    steps = 18
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            SectionLabel(stringResource(R.string.position_layout), topPadding = 16.dp)
            Spacer(modifier = Modifier.height(8.dp))
            
            val isDynamicIsland by repository.isDynamicIslandEnabled.collectAsState(initial = false)
            SettingsSwitchItem(
                title = stringResource(R.string.dynamic_island_mode),
                subtitle = stringResource(R.string.dynamic_island_desc),
                checked = isDynamicIsland,
                onCheckedChange = { repository.setDynamicIslandEnabled(it) },
                position = CardPosition.SINGLE
            )

            if (isDynamicIsland) {
                Spacer(modifier = Modifier.height(8.dp))
                val verticalOffset by repository.islandVerticalOffset.collectAsState(initial = FloatingSettingsRepository.DEFAULT_ISLAND_VERTICAL_OFFSET)
                val offsetLabel = "${stringResource(R.string.offset)}: ${stringResource(R.string.dp_value, verticalOffset.toInt())}"
                
                Text(offsetLabel, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = verticalOffset,
                    onValueChange = { repository.setIslandVerticalOffset(it) },
                    valueRange = 0f..50f,
                    steps = 50
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                val manualGap by repository.islandGap.collectAsState(initial = 0f)
                val gapDisplay = if (manualGap == 0f) stringResource(R.string.auto) else stringResource(R.string.dp_value, manualGap.toInt())
                
                Text(stringResource(R.string.gap_width_value, gapDisplay), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = manualGap,
                    onValueChange = { repository.setIslandGap(it) },
                    valueRange = 0f..200f,
                    steps = 40
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))

            // Metrics
            SectionLabel(stringResource(R.string.metrics), topPadding = 0.dp)
            Spacer(modifier = Modifier.height(8.dp))
            
            SettingsSwitchItem(
                title = stringResource(R.string.display_secondary_values),
                subtitle = stringResource(R.string.display_secondary_values_desc),
                checked = showSecondary,
                onCheckedChange = { repository.setShowSecondary(it) },
                position = CardPosition.TOP
            )
            
            Spacer(modifier = Modifier.height(2.dp)) // 3. 2dp padding between items
            
            SettingsSwitchItem(
                title = stringResource(R.string.show_trend_arrow),
                checked = showArrow,
                onCheckedChange = { repository.setShowArrow(it) },
                position = CardPosition.BOTTOM
            )

            Spacer(modifier = Modifier.height(24.dp))
            



            // Typography
            SectionLabel(stringResource(R.string.typography), topPadding = 0.dp)
            Spacer(modifier = Modifier.height(16.dp))

            // Font Source
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.font_source), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                // Chips
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = fontSource == "APP",
                        onClick = { repository.setFontSource("APP") },
                        label = { Text(stringResource(R.string.font_app_plex)) }
                    )
                    FilterChip(
                        selected = fontSource == "SYSTEM",
                        onClick = { repository.setFontSource("SYSTEM") },
                        label = { Text(stringResource(R.string.font_system_sans)) }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Size
            Text(stringResource(R.string.size_sp, fontSize.toInt()), style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = fontSize,
                onValueChange = { repository.setFontSize(it) },
                valueRange = 12f..48f,
                steps = 35
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Weight
            Text(stringResource(R.string.font_weight_label), style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("LIGHT", "REGULAR", "MEDIUM").forEach { w ->
                    val labelRes = when (w) {
                        "LIGHT" -> R.string.theme_light
                        "REGULAR" -> R.string.regular
                        else -> R.string.medium
                    }
                    FilterChip(
                        selected = fontWeight == w,
                        onClick = { repository.setFontWeight(w) },
                        label = { Text(stringResource(labelRes)) }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
