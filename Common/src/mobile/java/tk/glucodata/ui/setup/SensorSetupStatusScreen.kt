package tk.glucodata.ui.setup

import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tk.glucodata.R

internal const val SENSOR_SETUP_SUCCESS_AUTO_ADVANCE_MS = 1400L

private enum class SensorSetupStatusTone {
    Connecting,
    Success
}

@Composable
fun SensorSetupConnectingScreen(
    ui: WizardUiMetrics,
    sensorLabel: String? = null,
    title: String = stringResource(R.string.connecting_to_sensor),
    supportingText: String = stringResource(R.string.connecting_to_sensor_wait)
) {
    SensorSetupStatusScreen(
        ui = ui,
        tone = SensorSetupStatusTone.Connecting,
        sensorLabel = sensorLabel,
        title = title,
        supportingText = supportingText
    )
}

@Composable
fun SensorSetupSuccessScreen(
    ui: WizardUiMetrics,
    sensorLabel: String? = null,
    title: String = stringResource(R.string.status_connected)
) {
    SensorSetupStatusScreen(
        ui = ui,
        tone = SensorSetupStatusTone.Success,
        sensorLabel = sensorLabel,
        title = title,
        supportingText = null
    )
}

@Composable
private fun SensorSetupStatusScreen(
    ui: WizardUiMetrics,
    tone: SensorSetupStatusTone,
    sensorLabel: String?,
    title: String,
    supportingText: String?
) {
    val infiniteTransition = rememberInfiniteTransition(label = "SetupStatus")
    val haloScalePrimary by infiniteTransition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.24f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "HaloScalePrimary"
    )
    val haloAlphaPrimary by infiniteTransition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "HaloAlphaPrimary"
    )
    val haloScaleSecondary by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.34f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, delayMillis = 480, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "HaloScaleSecondary"
    )
    val haloAlphaSecondary by infiniteTransition.animateFloat(
        initialValue = 0.16f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, delayMillis = 480, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "HaloAlphaSecondary"
    )
    val heroScale by animateFloatAsState(
        targetValue = if (tone == SensorSetupStatusTone.Success) 1f else 0.98f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 460f),
        label = "HeroScale"
    )
    val completionProgress by animateFloatAsState(
        targetValue = if (tone == SensorSetupStatusTone.Success) 1f else 0f,
        animationSpec = tween(
            durationMillis = SENSOR_SETUP_SUCCESS_AUTO_ADVANCE_MS.toInt(),
            easing = LinearEasing
        ),
        label = "CompletionProgress"
    )

    val containerColor = when (tone) {
        SensorSetupStatusTone.Connecting -> MaterialTheme.colorScheme.primaryContainer
        SensorSetupStatusTone.Success -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = when (tone) {
        SensorSetupStatusTone.Connecting -> MaterialTheme.colorScheme.onPrimaryContainer
        SensorSetupStatusTone.Success -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    val accentColor = when (tone) {
        SensorSetupStatusTone.Connecting -> MaterialTheme.colorScheme.primary
        SensorSetupStatusTone.Success -> MaterialTheme.colorScheme.tertiary
    }
    // ui-guardrails: allow offscale_radius - a deliberately uneven organic shape, not a corner radius
    val heroShape = RoundedCornerShape(
        topStart = if (ui.compact) 30.dp else 36.dp,
        topEnd = if (ui.compact) 22.dp else 28.dp,
        bottomEnd = if (ui.compact) 36.dp else 42.dp,
        bottomStart = if (ui.compact) 20.dp else 24.dp
    )
    val heroSize = if (ui.compact) 120.dp else 148.dp
    val haloSize = if (ui.compact) 172.dp else 208.dp
    val icon = when (tone) {
        SensorSetupStatusTone.Connecting -> Icons.Default.Bluetooth
        SensorSetupStatusTone.Success -> Icons.Default.CheckCircle
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ui.horizontalPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(haloSize),
            contentAlignment = Alignment.Center
        ) {
            if (tone == SensorSetupStatusTone.Connecting) {
                Box(
                    modifier = Modifier
                        .size(haloSize * 0.84f)
                        .graphicsLayer {
                            scaleX = haloScalePrimary
                            scaleY = haloScalePrimary
                            alpha = haloAlphaPrimary
                        }
                        .border(2.dp, accentColor.copy(alpha = 0.55f), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(haloSize * 0.84f)
                        .graphicsLayer {
                            scaleX = haloScaleSecondary
                            scaleY = haloScaleSecondary
                            alpha = haloAlphaSecondary
                        }
                        .border(1.5.dp, accentColor.copy(alpha = 0.34f), CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(haloSize * 0.78f)
                        .background(accentColor.copy(alpha = 0.10f), CircleShape)
                )
            }

            Surface(
                color = containerColor,
                contentColor = contentColor,
                shape = heroShape,
                tonalElevation = 3.dp,
                shadowElevation = 0.dp,
                modifier = Modifier.graphicsLayer {
                    scaleX = heroScale
                    scaleY = heroScale
                }
            ) {
                Box(
                    modifier = Modifier.size(heroSize),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(if (ui.compact) 40.dp else 48.dp)
                    )
                }
            }
        }

        if (!sensorLabel.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(ui.spacerSmall))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = CircleShape
            ) {
                Text(
                    text = sensorLabel,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.height(ui.spacerLarge))

        Text(
            text = title,
            style = if (ui.compact) MaterialTheme.typography.headlineMediumEmphasized else MaterialTheme.typography.headlineLargeEmphasized,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (!supportingText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(ui.spacerSmall))
            Text(
                text = supportingText,
                style = if (ui.compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(0.86f)
            )
        }

        Spacer(modifier = Modifier.height(ui.spacerLarge))

        if (tone == SensorSetupStatusTone.Success) {
            LinearWavyProgressIndicator(
                progress = { completionProgress },
                modifier = Modifier.fillMaxWidth(0.42f),
                color = accentColor,
                trackColor = accentColor.copy(alpha = 0.18f)
            )
        } else {
            LinearWavyProgressIndicator(
                modifier = Modifier.fillMaxWidth(0.42f),
                color = accentColor,
                trackColor = accentColor.copy(alpha = 0.18f)
            )
        }
    }
}
