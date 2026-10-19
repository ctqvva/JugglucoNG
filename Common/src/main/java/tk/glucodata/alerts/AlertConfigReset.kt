package tk.glucodata.alerts

import kotlin.math.abs

/** Restore editable settings without arming or disarming the alarm. */
fun AlertConfig.resetToDefaults(isMmol: Boolean): AlertConfig =
    AlertDefaults.defaultConfig(type, isMmol).copy(enabled = enabled)

/** Keep custom identity and an existing snooze when restoring creation defaults. */
fun CustomAlertConfig.resetToDefaults(isMmol: Boolean): CustomAlertConfig =
    CustomAlertConfig(
        id = id,
        name = name,
        type = type,
        threshold = if (type == CustomAlertType.HIGH) {
            if (isMmol) 10f else 180f
        } else {
            if (isMmol) 3.9f else 70f
        },
        enabled = enabled,
        snoozedUntil = snoozedUntil
    )

/**
 * Whether [resetToDefaults] would change anything the user can see. This is
 * what decides if a reset button is offered at all.
 *
 * Thresholds are compared to within half a display step rather than exactly:
 * legacy alarms round-trip through native storage as whole mg/dl, so a default
 * of 3.6 mmol/L is read back as 3.61 and would otherwise count as edited forever.
 */
fun AlertConfig.isModifiedFromDefaults(isMmol: Boolean): Boolean {
    val defaults = resetToDefaults(isMmol)
    if (!thresholdsMatch(threshold, defaults.threshold, isMmol)) return true
    return copy(threshold = defaults.threshold) != defaults
}

/** Custom-alarm counterpart of [AlertConfig.isModifiedFromDefaults]. */
fun CustomAlertConfig.isModifiedFromDefaults(isMmol: Boolean): Boolean {
    val defaults = resetToDefaults(isMmol)
    if (!thresholdsMatch(threshold, defaults.threshold, isMmol)) return true
    return copy(threshold = defaults.threshold) != defaults
}

private fun thresholdsMatch(a: Float?, b: Float?, isMmol: Boolean): Boolean {
    if (a == null || b == null) return a == b
    return abs(a - b) < if (isMmol) 0.05f else 0.5f
}
