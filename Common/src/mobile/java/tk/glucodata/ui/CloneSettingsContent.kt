@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui

import tk.glucodata.ui.components.RevealSettingsSearchAnchor
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import tk.glucodata.ui.util.ExpressiveMotion
import tk.glucodata.ui.symbols.Symbols
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.util.DiscoveredMirror
import tk.glucodata.ui.components.*
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.util.ConnectedButtonGroup

internal enum class ClonePairMode { SEND, GET }

/** The list's own gap, which joins grouped rows; anything spaced wider adds the rest. */
internal val CloneListGap = 2.dp

/** What this phone is offering while "Send readings" is open. */
internal data class CloneSendState(
    val kind: QuickPairKind,
    val json: String,
    val announced: Boolean,
    val connected: Boolean,
)

@Composable
internal fun CloneSettingsContent(
    cloneEnabled: Boolean,
    controlsEnabled: Boolean,
    hasConnections: Boolean,
    listsConnections: Boolean,
    backgroundLiveness: Boolean,
    pairMode: ClonePairMode?,
    send: CloneSendState?,
    deviceName: String,
    nearbyDevices: List<DiscoveredMirror>,
    /** An item of [connections] to scroll to, counted from its first item. */
    revealConnection: Int? = null,
    onConnectionRevealed: () -> Unit = {},
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onMasterChanged: (Boolean) -> Unit,
    onPairMode: (ClonePairMode?) -> Unit,
    onSendKind: (QuickPairKind) -> Unit,
    onScan: () -> Unit,
    onConnectNearby: (DiscoveredMirror) -> Unit,
    onBackgroundChanged: (Boolean) -> Unit,
    onNetworkSettings: () -> Unit,
    onAddManually: () -> Unit,
    connections: LazyListScope.() -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.mirror),
                onNavigateBack = onBack,
                actions = {
                    IconButtonTooltip(stringResource(R.string.mirror_reconnect_all)) {
                        IconButton(onClick = onRefresh, shapes = IconButtonDefaults.shapes()) {
                            Icon(Symbols.RefreshFilled, stringResource(R.string.mirror_reconnect_all))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val listState = rememberLazyListState()
        RevealSettingsSearchAnchor(
            listState,
            setOf(
                SettingsSearchAnchors.forRow(R.string.mirror_background_liveness),
            )
        )
        // A connection made from Get readings is added above where the list is anchored, which
        // left it half under the top bar. Bring its card into view, as Send's own card already is.
        val currentOnRevealed by rememberUpdatedState(onConnectionRevealed)
        LaunchedEffect(revealConnection) {
            val item = revealConnection ?: return@LaunchedEffect
            // The master switch is the one item above the connections.
            listState.animateScrollToItem((if (hasConnections) 1 else 0) + item)
            currentOnRevealed()
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(CloneListGap),
        ) {
            // The master switch only means something once there is a connection to turn off.
            if (hasConnections) {
                item(key = "clone_master") {
                    MasterSwitchCard(
                        title = stringResource(R.string.clone_network_title),
                        subtitle = stringResource(if (cloneEnabled) R.string.enabled_status else R.string.disabled_status),
                        checked = cloneEnabled,
                        enabled = controlsEnabled,
                        icon = Symbols.SyncAltFilled,
                        onCheckedChange = onMasterChanged,
                    )
                }
            }
            connections()
            item(key = "pair_section") {
                SectionLabel(
                    stringResource(R.string.clone_connect_title),
                    topPadding = if (listsConnections || hasConnections) 24.dp else 8.dp,
                )
            }
            // The phone's role is the first decision; everything after it happens on this screen.
            item(key = "pair_modes") {
                ClonePairButtons(
                    pairMode = pairMode,
                    onPairMode = { mode -> onPairMode(if (mode == pairMode) null else mode) },
                )
            }
            if (pairMode != null) {
                item(key = "pair_panel") {
                    Surface(
                        modifier = Modifier.animateItem().fillMaxWidth().padding(top = 8.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        AnimatedContent(targetState = pairMode, label = "pair_panel") { mode ->
                            Column(Modifier.padding(16.dp)) {
                                when (mode) {
                                    ClonePairMode.SEND -> CloneSendPanel(send, deviceName, onSendKind)
                                    ClonePairMode.GET -> CloneGetPanel(nearbyDevices, onScan, onConnectNearby)
                                }
                            }
                        }
                    }
                }
            }
            item(key = "setup_section") { SectionLabel(stringResource(R.string.clone_connection_options)) }
            // A preference, not a status: gating it on a connection being live right
            // now is what made it look deleted whenever one was not.
            if (hasConnections) {
                item(key = "clone_background_liveness") {
                    SettingsSwitchItem(
                        title = stringResource(R.string.mirror_background_liveness),
                        searchAnchor = SettingsSearchAnchors.forRow(R.string.mirror_background_liveness),
                        subtitle = stringResource(R.string.clone_background_desc),
                        checked = backgroundLiveness,
                        enabled = controlsEnabled,
                        onCheckedChange = onBackgroundChanged,
                        icon = Symbols.BatterySaverFilled,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        position = CardPosition.TOP,
                    )
                }
            }
            item(key = "turn") {
                SettingsItem(
                    title = stringResource(R.string.clone_network_settings),
                    subtitle = stringResource(R.string.clone_network_summary),
                    icon = Symbols.HubFilled,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    position = if (hasConnections) CardPosition.BOTTOM else CardPosition.SINGLE,
                    animatePosition = true,
                    onClick = onNetworkSettings,
                )
            }
            // A way to connect for those who know the address; Send and Get are the way for
            // everyone else, so this one stays quiet at the end of the screen.
            item(key = "add_manually") {
                OutlinedButton(
                    onClick = onAddManually,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp - CloneListGap).heightIn(min = 48.dp),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(Symbols.AddFilled, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.clone_add_custom_connection))
                }
            }
        }
    }
}

/**
 * Send readings | Get readings: the connected group as it always was (the library's toggle
 * buttons, its shapes, colours, label size and press feedback). The one difference is at rest:
 * the icon is larger and sits over the label. Opening either one moves the icon down into its
 * usual place beside the label, and the group returns to its usual height.
 */
@Composable
internal fun ClonePairButtons(
    pairMode: ClonePairMode?,
    onPairMode: (ClonePairMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fold by animateFloatAsState(
        targetValue = if (pairMode != null) 1f else 0f,
        animationSpec = ExpressiveMotion.defaultSpatial(),
        label = "pair_fold",
    )
    ClonePairButtons(pairMode, fold, onPairMode, modifier)
}

private val PairRestHeight = 80.dp
private val PairOpenHeight = 56.dp
private val PairRestIcon = 36.dp
private val PairOpenIcon = 24.dp

/** [fold] is 0 with the icon over the label and 1 with it beside the label; the spring may overshoot either end. */
@Composable
internal fun ClonePairButtons(
    pairMode: ClonePairMode?,
    fold: Float,
    onPairMode: (ClonePairMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = ClonePairMode.entries
    val labels = modes.map { mode ->
        stringResource(if (mode == ClonePairMode.SEND) R.string.clone_send_readings else R.string.clone_get_readings)
    }
    // As in the group: the largest label style in which every option fits on one line.
    var rowWidth by remember { mutableIntStateOf(0) }
    val textMeasurer = rememberTextMeasurer()
    val styles = listOf(
        MaterialTheme.typography.labelLarge,
        MaterialTheme.typography.labelMedium,
        MaterialTheme.typography.labelSmall,
    )
    val labelStyle = with(LocalDensity.current) {
        val gap = ButtonGroupDefaults.ConnectedSpaceBetween.roundToPx()
        val room = (rowWidth - gap) / 2 - (PairOpenIcon + 8.dp + 8.dp).roundToPx()
        styles.firstOrNull { style ->
            rowWidth == 0 || labels.all { textMeasurer.measure(it, style, maxLines = 1).size.width <= room }
        } ?: styles.last()
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { rowWidth = it.width }
            .height(lerp(PairRestHeight, PairOpenHeight, fold).coerceAtLeast(PairOpenHeight - 8.dp))
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        modes.forEachIndexed { index, mode ->
            val selected = mode == pairMode
            val container by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                label = "pair_container",
            )
            val content by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                label = "pair_content",
            )
            ToggleButton(
                checked = selected,
                onCheckedChange = { onPairMode(mode) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shapes = if (index == 0) {
                    ButtonGroupDefaults.connectedLeadingButtonShapes()
                } else {
                    ButtonGroupDefaults.connectedTrailingButtonShapes()
                },
                colors = ToggleButtonDefaults.colors(
                    containerColor = container,
                    contentColor = content,
                    checkedContainerColor = container,
                    checkedContentColor = content,
                ),
                elevation = null,
                contentPadding = PaddingValues(0.dp),
            ) {
                ClonePairButtonContent(
                    label = labels[index],
                    labelStyle = labelStyle,
                    // A phone with the readings leaving it, and arriving in it.
                    icon = if (mode == ClonePairMode.SEND) Symbols.MobileArrowUpRightFilled else Symbols.MobileArrowDownFilled,
                    fold = fold,
                )
            }
        }
    }
}

/**
 * One icon and one label. The label slides from under the icon to the middle line; the icon
 * shrinks and travels to the label's start on an arc, sideways first and then down, so it
 * clears the label's corner instead of crossing the text.
 */
@Composable
private fun ClonePairButtonContent(label: String, labelStyle: TextStyle, icon: ImageVector, fold: Float) {
    Layout(
        content = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(lerp(PairRestIcon, PairOpenIcon, fold)))
            Text(label, style = labelStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        },
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
    ) { measurables, constraints ->
        val stackedGap = 4.dp.roundToPx()
        val inlineGap = 8.dp.roundToPx()
        val restIcon = PairRestIcon.roundToPx()
        val openIcon = PairOpenIcon.roundToPx()
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val iconPlaceable = measurables[0].measure(Constraints())
        val labelPlaceable = measurables[1].measure(
            Constraints(maxWidth = lerp(width, (width - openIcon - inlineGap).coerceAtLeast(0), fold.coerceIn(0f, 1f)))
        )
        val across = 1f - (1f - fold).let { it * it * it * it }
        val down = fold * fold * fold
        layout(width, height) {
            val stackedTop = (height - restIcon - stackedGap - labelPlaceable.height) / 2
            val inlineStart = (width - openIcon - inlineGap - labelPlaceable.width) / 2
            labelPlaceable.placeRelative(
                x = lerp((width - labelPlaceable.width) / 2, inlineStart + openIcon + inlineGap, fold),
                y = lerp(stackedTop + restIcon + stackedGap, (height - labelPlaceable.height) / 2, fold),
            )
            // Placed by its centre, so shrinking doesn't pull it off its path.
            val centreX = lerp(width / 2f, inlineStart + openIcon / 2f, across)
            val centreY = lerp(stackedTop + restIcon / 2f, height / 2f, down)
            iconPlaceable.placeRelative(
                x = (centreX - iconPlaceable.width / 2f).roundToInt(),
                y = (centreY - iconPlaceable.height / 2f).roundToInt(),
            )
        }
    }
}

@Composable
private fun CloneSendPanel(
    send: CloneSendState?,
    deviceName: String,
    onKind: (QuickPairKind) -> Unit,
) {
    if (send == null) return
    // Once the other phone has joined, the code has done its job; showing it longer only
    // invites a second phone to scan a code already in use.
    AnimatedVisibility(visible = !send.connected) {
        Column {
            Text(
                stringResource(
                    R.string.clone_qr_instruction,
                    stringResource(R.string.mirror),
                    stringResource(R.string.clone_get_readings),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            val anyNetwork = stringResource(R.string.clone_code_any_network)
            val wifiOnly = stringResource(R.string.clone_code_wifi_only)
            val kindLabel = { kind: QuickPairKind -> if (kind == QuickPairKind.HYBRID) anyNetwork else wifiOnly }
            ConnectedButtonGroup(
                options = listOf(QuickPairKind.HYBRID, QuickPairKind.LOCAL),
                selectedOption = send.kind,
                onOptionSelected = { if (it != send.kind) onKind(it) },
                labelText = kindLabel,
                label = { kind -> Text(kindLabel(kind)) },
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // The encoder draws the code's own white quiet zone.
                val qrSize = minOf(maxWidth, 320.dp)
                QRCodeImage(
                    send.json,
                    with(LocalDensity.current) { qrSize.roundToPx() },
                    Modifier
                        .size(qrSize)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (send.connected) {
            Icon(
                Symbols.CheckCircleFilled,
                contentDescription = null,
                tint = StatusPalette.success(),
                modifier = Modifier.size(32.dp),
            )
        } else {
            LoadingIndicator(modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (send.connected) R.string.clone_pairing_connected else R.string.clone_pairing_waiting),
                style = MaterialTheme.typography.titleSmall,
            )
            if (!send.connected && send.announced) {
                Text(
                    stringResource(R.string.clone_pairing_announced, deviceName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CloneGetPanel(
    nearbyDevices: List<DiscoveredMirror>,
    onScan: () -> Unit,
    onConnectNearby: (DiscoveredMirror) -> Unit,
) {
    Button(
        onClick = onScan,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shapes = ButtonDefaults.shapes(),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        Icon(Symbols.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.clone_scan_code))
    }
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.clone_on_this_wifi),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    if (nearbyDevices.isEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.clone_nearby_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        nearbyDevices.forEach { device ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.clone_sharing_readings),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                FilledTonalButton(
                    onClick = { onConnectNearby(device) },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Text(stringResource(R.string.mirror_connect_action))
                }
            }
        }
    }
}
