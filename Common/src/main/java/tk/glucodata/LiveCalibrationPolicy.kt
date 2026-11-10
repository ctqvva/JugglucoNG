package tk.glucodata

/**
 * Whether the live publish path (notification, glucose alerts, broadcasts)
 * runs a reading through the app's own calibration before handing it on.
 *
 * The dashboard calibrates what it draws on its own path, so a live value that
 * skips this is the one place the app disagrees with itself — and the alerts
 * are on the live side. The previous gate skipped calibration for every driver
 * that stores its own live readings in Room, meant for drivers that fold the
 * user's calibration into what they store (Sibionics' DSP) and must not have it
 * applied twice. It also skipped AiDEX, which stores its own readings but
 * integrates nothing, so in raw-primary view the notification and the alert
 * engine evaluated the bare raw lane: a LOW at 3.4 mmol/L fired on 2026-09-19
 * while the calibrated line on screen read 5.9.
 *
 * The only reason to leave a live value alone is the driver already having
 * calibrated it. Storing its own rows is not that reason. Native drivers
 * never integrate, so they always pass `false` here.
 */
object LiveCalibrationPolicy {
    @JvmStatic
    fun appliesGenericCalibration(integratesUserCalibration: Boolean): Boolean =
        !integratesUserCalibration
}
