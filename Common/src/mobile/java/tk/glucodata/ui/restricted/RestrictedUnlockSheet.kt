@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui.restricted

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.text.format.DateUtils
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tk.glucodata.R
import tk.glucodata.RestrictedMode
import tk.glucodata.RestrictedModePin
import tk.glucodata.ui.components.IconButtonTooltip
import tk.glucodata.ui.components.StableModalBottomSheet
import tk.glucodata.ui.util.findActivity

/**
 * The way from restricted to full mode. Without a PIN it is one tap — the point is that a
 * child does not wander into the settings by accident, not that an adult has to prove
 * anything. With a PIN it is a keypad, and biometrics when the adult turned them on.
 */
@Composable
internal fun RestrictedUnlockSheet(
    onUnlocked: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by RestrictedMode.state.collectAsStateWithLifecycle()
    val config = state.config
    val context = LocalContext.current
    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        if (!config.hasPin) {
            NoPinUnlock(onUnlock = {
                RestrictedMode.unlock()
                onUnlocked()
            })
        } else {
            val biometricLabel = stringResource(R.string.restricted_use_biometric)
            val title = stringResource(R.string.restricted_unlock_title)
            val cancel = stringResource(R.string.cancel)
            val biometricsReady = config.biometricUnlock && RestrictedBiometrics.isAvailable(context)
            val launchBiometrics: () -> Unit = {
                val activity = context.findActivity()
                if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    RestrictedBiometrics.authenticate(activity, title, cancel) {
                        RestrictedMode.unlock()
                        onUnlocked()
                    }
                }
            }
            // Offer the fingerprint straight away; the keypad stays underneath as the
            // fallback for when the prompt is cancelled.
            LaunchedEffect(biometricsReady) {
                if (biometricsReady) launchBiometrics()
            }
            PinUnlock(
                onAccepted = {
                    RestrictedMode.unlock()
                    onUnlocked()
                },
                biometricAction = if (biometricsReady) {
                    {
                        TextButton(
                            onClick = launchBiometrics,
                            shapes = ButtonDefaults.shapes(),
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                        ) {
                            Icon(
                                Icons.Default.Fingerprint,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(biometricLabel)
                        }
                    }
                } else null,
            )
        }
    }
}

@Composable
private fun NoPinUnlock(onUnlock: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.LockOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.restricted_unlock_title),
            style = MaterialTheme.typography.headlineSmallEmphasized,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.restricted_unlock_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onUnlock,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(stringResource(R.string.restricted_unlock_title))
        }
    }
}

@Composable
private fun PinUnlock(
    onAccepted: () -> Unit,
    biometricAction: (@Composable () -> Unit)?,
) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var lockedOutUntil by remember { mutableLongStateOf(RestrictedMode.pinLockoutUntil(context)) }
    val wrongPin = stringResource(R.string.restricted_wrong_pin)
    PinPad(
        title = stringResource(R.string.restricted_enter_pin),
        supporting = stringResource(R.string.restricted_enter_pin_desc),
        error = error,
        lockedOutUntil = lockedOutUntil,
        onLockoutEnded = { lockedOutUntil = 0L },
        resetKey = attempt,
        onSubmit = { pin ->
            when (val result = RestrictedMode.checkPin(context, pin)) {
                RestrictedMode.PinCheck.Accepted -> onAccepted()
                is RestrictedMode.PinCheck.Rejected -> {
                    error = wrongPin
                    lockedOutUntil = result.lockedOutUntilMs
                    attempt++
                }
                is RestrictedMode.PinCheck.LockedOut -> {
                    lockedOutUntil = result.untilMs
                    attempt++
                }
            }
        },
        footer = biometricAction,
    )
}

/**
 * Choosing a PIN: once, then again to confirm. [onChosen] receives the confirmed digits.
 */
@Composable
internal fun RestrictedPinSetupSheet(
    onChosen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val mismatch = stringResource(R.string.restricted_pin_mismatch)
    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        PinPad(
            title = stringResource(
                if (first == null) R.string.restricted_choose_pin else R.string.restricted_confirm_pin
            ),
            supporting = stringResource(
                R.string.restricted_choose_pin_desc,
                RestrictedModePin.MIN_LENGTH,
                RestrictedModePin.MAX_LENGTH,
            ),
            error = error,
            lockedOutUntil = 0L,
            onLockoutEnded = {},
            resetKey = attempt,
            onSubmit = { pin ->
                val chosen = first
                when {
                    chosen == null -> {
                        first = pin
                        error = null
                    }
                    chosen == pin -> onChosen(pin)
                    else -> {
                        first = null
                        error = mismatch
                    }
                }
                attempt++
            },
            footer = null,
        )
    }
}

private val KeySize = 72.dp

@Composable
private fun PinPad(
    title: String,
    supporting: String,
    error: String?,
    lockedOutUntil: Long,
    onLockoutEnded: () -> Unit,
    resetKey: Int,
    onSubmit: (String) -> Unit,
    footer: (@Composable () -> Unit)?,
) {
    var pin by remember(resetKey) { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Ticks only while the keypad is locked, to count the wait down.
    LaunchedEffect(lockedOutUntil) {
        while (lockedOutUntil > 0L) {
            now = System.currentTimeMillis()
            if (now >= lockedOutUntil) {
                onLockoutEnded()
                break
            }
            delay(1_000L)
        }
    }
    val lockedOut = lockedOutUntil > now
    val keysEnabled = !lockedOut
    val message = when {
        lockedOut -> stringResource(
            R.string.restricted_pin_locked_out,
            DateUtils.formatElapsedTime(((lockedOutUntil - now + 999L) / 1000L).coerceAtLeast(1L)),
        )
        else -> error
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmallEmphasized,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = supporting,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PinDots(length = pin.length)
        Spacer(Modifier.height(12.dp))
        // Always laid out, so a message appearing does not push the keypad down.
        Text(
            text = message.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            minLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(16.dp))

        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { digit ->
                        DigitKey(digit, enabled = keysEnabled && pin.length < RestrictedModePin.MAX_LENGTH) {
                            pin += digit
                        }
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val deleteLabel = stringResource(R.string.restricted_delete_digit)
                Box(modifier = Modifier.size(KeySize), contentAlignment = Alignment.Center) {
                    IconButtonTooltip(deleteLabel) {
                        IconButton(
                            onClick = { pin = pin.dropLast(1) },
                            enabled = pin.isNotEmpty(),
                            shapes = IconButtonDefaults.shapes(),
                            modifier = Modifier.size(56.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = deleteLabel)
                        }
                    }
                }
                DigitKey("0", enabled = keysEnabled && pin.length < RestrictedModePin.MAX_LENGTH) {
                    pin += "0"
                }
                val confirmLabel = stringResource(R.string.ok)
                Box(modifier = Modifier.size(KeySize), contentAlignment = Alignment.Center) {
                    IconButtonTooltip(confirmLabel) {
                        FilledIconButton(
                            onClick = { onSubmit(pin) },
                            enabled = keysEnabled && RestrictedModePin.isValid(pin),
                            shapes = IconButtonDefaults.shapes(),
                            modifier = Modifier.size(KeySize),
                        ) {
                            Icon(Icons.Default.Check, contentDescription = confirmLabel)
                        }
                    }
                }
            }
        }
        if (footer != null) {
            Spacer(Modifier.height(16.dp))
            footer()
        }
    }
}

@Composable
private fun PinDots(length: Int) {
    val slots = maxOf(RestrictedModePin.MIN_LENGTH, length)
    val entered = pluralCount(length)
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .heightIn(min = 16.dp)
            .clearAndSetSemantics { contentDescription = entered },
    ) {
        repeat(slots) { index ->
            val filled = index < length
            val fill by animateColorAsState(
                if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "pinDot",
            )
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(fill, CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
}

@Composable
private fun pluralCount(length: Int): String {
    val resources = LocalContext.current.resources
    return resources.getQuantityString(R.plurals.restricted_pin_digits_entered, length, length)
}

@Composable
private fun DigitKey(digit: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.sizeIn(minWidth = KeySize, minHeight = KeySize),
    ) {
        Text(
            text = digit,
            style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
        )
    }
}

/**
 * Fingerprint or face through the platform prompt (Android 11+, where the allowed
 * authenticators can be limited to biometrics). The phone's screen lock is deliberately not
 * accepted: on a child's own phone the child knows it.
 */
internal object RestrictedBiometrics {
    fun isAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val manager = context.getSystemService(BiometricManager::class.java) ?: return false
        return manager.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun authenticate(activity: Activity, title: String, cancel: String, onSuccess: () -> Unit) {
        val executor = activity.mainExecutor
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setNegativeButton(cancel, executor) { _, _ -> }
            .build()
        runCatching {
            prompt.authenticate(
                CancellationSignal(),
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onSuccess()
                    }
                },
            )
        }
    }

    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
}
