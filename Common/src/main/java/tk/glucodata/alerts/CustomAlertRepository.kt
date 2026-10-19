package tk.glucodata.alerts

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import tk.glucodata.Applic

object CustomAlertRepository {
    private const val PREFS_NAME = "tk.glucodata.custom_alerts"
    private const val KEY_ALERTS = "custom_alerts_list"

    private val prefs: SharedPreferences by lazy {
        Applic.app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getAll(): List<CustomAlertConfig> {
        val jsonString = prefs.getString(KEY_ALERTS, null) ?: return emptyList()
        val list = mutableListOf<CustomAlertConfig>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(CustomAlertConfig.fromJson(obj))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveAll(alerts: List<CustomAlertConfig>) {
        val jsonArray = JSONArray()
        alerts.forEach { 
            jsonArray.put(it.toJson()) 
        }
        prefs.edit().putString(KEY_ALERTS, jsonArray.toString()).apply()
    }

    fun add(alert: CustomAlertConfig) {
        val current = getAll().toMutableList()
        current.add(alert)
        saveAll(current)
    }

    fun update(updatedAlert: CustomAlertConfig) {
        val current = getAll().toMutableList()
        val index = current.indexOfFirst { it.id == updatedAlert.id }
        if (index != -1) {
            current[index] = updatedAlert
            saveAll(current)
        }
    }

    fun delete(alertId: String) {
        val current = getAll().toMutableList()
        current.removeIf { it.id == alertId }
        saveAll(current)
    }
}
