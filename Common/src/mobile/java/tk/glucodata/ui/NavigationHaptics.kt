package tk.glucodata.ui

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The tap under a tab switch: one short, hard-edged click.
 *
 * `PRIMITIVE_CLICK` is the one primitive built as a single sharp pulse. The tick primitives,
 * especially at low strength, smear into a soft buzz on many actuators (the Pixel 8 Pro's
 * among them), which reads as mush rather than a punch. Strength stays well above the
 * point where the pulse loses its edge; below that a lighter tap only gets vaguer.
 *
 * It plays only where it can be rendered cleanly. A device whose actuator supports the
 * composition primitives has the kind of motor that makes a crisp tap; an older or cheaper
 * motor would turn the same request into a dull buzz, so those devices get nothing.
 * It is sent as touch feedback, so the system's touch-vibration setting and intensity
 * still decide whether and how strongly it is felt.
 */
class NavigationTick internal constructor(context: Context) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) capableVibrator(context) else null

    fun play() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val vibrator = vibrator ?: return
        runCatching {
            vibrator.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, CLICK_SCALE)
                    .compose(),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun capableVibrator(context: Context): Vibrator? = runCatching {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            ?.takeIf { it.hasVibrator() }
            ?.takeIf { it.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) }
    }.getOrNull()

    private companion object {
        const val CLICK_SCALE = 0.5f
    }
}

@Composable
fun rememberNavigationTick(): NavigationTick {
    val context = LocalContext.current
    return remember(context) { NavigationTick(context.applicationContext) }
}
