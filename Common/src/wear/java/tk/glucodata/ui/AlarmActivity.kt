package tk.glucodata.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.Log
import tk.glucodata.Notify
import tk.glucodata.R
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertType
import tk.glucodata.receivers.AlarmActionReceiver
import tk.glucodata.ui.screens.glucoseColor
import tk.glucodata.ui.screens.trendArrow
import tk.glucodata.ui.theme.WearJugglucoTheme

class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        turnScreenOnAndKeyguard()
        showAlarmContent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        turnScreenOnAndKeyguard()
        showAlarmContent()
    }

    override fun onStart() {
        super.onStart()
        if (closeIfAcknowledged()) return
        Notify.setAlarmUiVisible(true)
        observeAcknowledgement()
        turnScreenOnAndKeyguard()
    }

    override fun onStop() {
        AlarmActivityAccess.removeObserver(this)
        Notify.setAlarmUiVisible(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    private fun observeAcknowledgement() {
        AlarmActivityAccess.removeObserver(this)
        if (intent.getStringExtra(Notify.EXTRA_CUSTOM_ALERT_ID) == null) {
            AlarmActivityAccess.observeAcknowledgement(this, intent.getIntExtra(EXTRA_ALERT_TYPE_ID, -1)) { finish() }
        }
    }

    private fun closeIfAcknowledged(): Boolean {
        if (!AlarmActivityAccess.isAcknowledged(intent)) return false
        finish()
        return true
    }

    private fun showAlarmContent() {
        if (closeIfAcknowledged()) return
        observeAcknowledgement()
        val model = buildUiModel()
        Notify.cancelQueuedAlarmActivityLaunch(
            Notify.resolveAlertKind(model.alertTypeId),
            intent.getStringExtra(Notify.EXTRA_CUSTOM_ALERT_ID),
            "wear-alarm-activity-visible"
        )

        setContent {
            WearJugglucoTheme {
                AlarmContent(
                    model = model,
                    onSnooze = {
                        sendAlarmAction(
                            action = AlarmActionReceiver.ACTION_SNOOZE,
                            snoozeMinutes = model.snoozeMinutes
                        )
                    },
                    onDismiss = {
                        sendAlarmAction(action = AlarmActionReceiver.ACTION_DISMISS)
                    }
                )
            }
        }
    }

    private fun buildUiModel(): AlarmUiModel {
        val alertTypeId = intent.getIntExtra(EXTRA_ALERT_TYPE_ID, -1)
        val alertType = AlertType.fromId(Notify.resolveAlertKind(alertTypeId))
        val snapshot = runCatching { CurrentDisplaySource.resolveCurrent() }.getOrNull()
        val fallback = fallbackGlucose(intent)
        val rate = snapshot?.rate ?: intent.getFloatExtra(EXTRA_RATE, Float.NaN)
        val alertLabel = alertType?.let { getString(it.nameResId) }
            ?: intent.getStringExtra(EXTRA_ALARM_MESSAGE).orEmpty()
                .ifBlank { intent.getStringExtra(EXTRA_ALARM_TYPE).orEmpty() }
                .ifBlank { getString(R.string.alarms) }
        val snoozeMinutes = alertType?.let {
            runCatching { AlertRepository.loadConfig(it).defaultSnoozeMinutes }.getOrDefault(DEFAULT_SNOOZE_MINUTES)
        } ?: DEFAULT_SNOOZE_MINUTES

        return AlarmUiModel(
            alertTypeId = alertTypeId,
            alertLabel = alertLabel,
            primaryGlucose = snapshot?.primaryStr ?: fallback.primary,
            secondaryGlucose = snapshot?.secondaryStr ?: fallback.secondary,
            trend = trendArrow(rate),
            glucoseColor = snapshot?.let { glucoseColor(it) },
            snoozeMinutes = snoozeMinutes
        )
    }

    private fun fallbackGlucose(intent: Intent): FallbackGlucose {
        val source = intent.getStringExtra(EXTRA_GLUCOSE_VAL).orEmpty()
            .ifBlank { intent.getStringExtra(EXTRA_ALARM_MESSAGE).orEmpty() }
        val match = GLUCOSE_VALUE_REGEX.find(source)
        if (match == null) {
            return FallbackGlucose(primary = "---", secondary = null)
        }
        val value = match.value.trim()
        return FallbackGlucose(primary = value, secondary = null)
    }

    private fun sendAlarmAction(action: String, snoozeMinutes: Int? = null) {
        val alertTypeId = intent.getIntExtra(EXTRA_ALERT_TYPE_ID, -1)
        val customAlertId = intent.getStringExtra(Notify.EXTRA_CUSTOM_ALERT_ID)
        val actionIntent = Intent(this, AlarmActionReceiver::class.java).apply {
            this.action = action
            putExtra(AlarmActionReceiver.EXTRA_ALERT_TYPE_ID, alertTypeId)
            if (!customAlertId.isNullOrBlank()) {
                putExtra(Notify.EXTRA_CUSTOM_ALERT_ID, customAlertId)
            }
            if (snoozeMinutes != null) {
                putExtra(AlarmActionReceiver.EXTRA_SNOOZE_MINUTES, snoozeMinutes)
            }
        }
        try {
            sendBroadcast(actionIntent)
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "send alarm action", t)
        } finally {
            finish()
        }
    }

    private fun turnScreenOnAndKeyguard() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        }
    }

    data class AlarmUiModel(
        val alertTypeId: Int,
        val alertLabel: String,
        val primaryGlucose: String,
        val secondaryGlucose: String?,
        val trend: String,
        val glucoseColor: Color?,
        val snoozeMinutes: Int
    )

    private data class FallbackGlucose(
        val primary: String,
        val secondary: String?
    )

    companion object {
        private const val LOG_ID = "WearAlarmActivity"
        private const val DEFAULT_SNOOZE_MINUTES = 15
        private val GLUCOSE_VALUE_REGEX = Regex("\\d+(?:[.,]\\d+)?")

        const val EXTRA_GLUCOSE_VAL = "EXTRA_GLUCOSE_VAL"
        const val EXTRA_ALARM_TYPE = "EXTRA_ALARM_TYPE"
        const val EXTRA_ALARM_MESSAGE = "EXTRA_ALARM_MESSAGE"
        const val EXTRA_ALERT_TYPE_ID = "EXTRA_ALERT_TYPE_ID"
        const val EXTRA_RATE = "EXTRA_RATE"
    }
}

@Composable
private fun AlarmContent(
    model: AlarmActivity.AlarmUiModel,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit
) {
    val glucoseColor = model.glucoseColor ?: MaterialTheme.colorScheme.primary
    val displayTrend = remember(model.trend) { model.trend.takeIf { it.isNotBlank() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 22.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = model.alertLabel,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = model.primaryGlucose,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 50.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = glucoseColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            if (displayTrend != null) {
                Text(
                    text = displayTrend,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 4.dp),
                    maxLines = 1,
                )
            }
        }

        model.secondaryGlucose?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = onSnooze,
                contentPadding = PaddingValues(horizontal = 4.dp),
                label = {
                    AlarmActionLabel(
                        icon = Icons.Rounded.Snooze,
                        text = stringResource(R.string.snooze),
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .sizeIn(minHeight = 52.dp),
            )
            Button(
                onClick = onDismiss,
                contentPadding = PaddingValues(horizontal = 4.dp),
                label = {
                    AlarmActionLabel(
                        icon = Icons.Rounded.Close,
                        text = stringResource(R.string.notification_dismiss_action_dismiss),
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .sizeIn(minHeight = 52.dp),
            )
        }
    }
}

@Composable
private fun AlarmActionLabel(
    icon: ImageVector,
    text: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
            modifier = Modifier.padding(start = 3.dp),
            maxLines = 1,
            softWrap = false,
        )
    }
}
