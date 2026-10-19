package tk.glucodata.glucosemeter

import android.content.Context
import java.util.Locale
import tk.glucodata.Applic

object SatelliteMeterCredentials {
    private const val PREFS = "tk.glucodata_preferences"
    private const val KEY_CODE = "satellite_meter_code"

    @JvmStatic
    fun load(): String = Applic.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_CODE, "")
        .orEmpty()

    @JvmStatic
    fun save(code: String) {
        Applic.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CODE, code.trim().uppercase(Locale.US))
            .apply()
    }

    @JvmStatic
    fun isValid(code: String?): Boolean = SatelliteMeterProtocol.resolvePin(code) != null
}
