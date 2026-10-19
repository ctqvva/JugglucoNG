package tk.glucodata

import tk.glucodata.drivers.api.ApiIobSnapshot
import tk.glucodata.drivers.nightscout.NightscoutFollowerDeviceStatus

/** One remote insulin/carb state used consistently by every app surface. */
object RemoteIobSnapshot {
    enum class Source { CLONE, NIGHTSCOUT, API }

    data class Values(
        val iobUnits: Float,
        val eiobUnits: Float,
        val cobGrams: Float,
        val iobNext30Units: Float = Float.NaN,
        val cobNext30Grams: Float = Float.NaN,
        val timestampMillis: Long,
        val source: Source,
    ) {
        fun asArray(): FloatArray = floatArrayOf(
            iobUnits,
            eiobUnits,
            cobGrams,
            iobNext30Units,
            cobNext30Grams,
        )
    }

    /** Pure precedence policy, kept separate so the hard-off behavior is testable. */
    internal fun select(
        cloneReceptionEnabled: Boolean,
        cloneRegistered: Boolean,
        clone: Values?,
        nightscout: Values?,
        api: Values? = null,
    ): Values? = if (cloneReceptionEnabled && cloneRegistered) clone ?: nightscout ?: api else nightscout ?: api

    @JvmStatic
    @JvmOverloads
    fun fresh(
        nowMillis: Long,
        allowClone: Boolean = true,
        allowNightscout: Boolean = true,
        allowApi: Boolean = true,
    ): Values? {
        val clone = if (allowClone) {
            CloneIobSnapshot.fresh(nowMillis)?.let {
                Values(
                    iobUnits = it.iobUnits,
                    eiobUnits = it.eiobUnits,
                    cobGrams = it.cobGrams,
                    iobNext30Units = it.iobNext30Units,
                    cobNext30Grams = it.cobNext30Grams,
                    timestampMillis = it.timestampMillis,
                    source = Source.CLONE,
                )
            }
        } else {
            null
        }
        val nightscout = if (allowNightscout) NightscoutFollowerDeviceStatus.fresh(nowMillis)?.let {
            Values(
                iobUnits = it.iobUnits,
                eiobUnits = it.eiobUnits,
                cobGrams = it.cobGrams,
                timestampMillis = it.timestampMillis,
                source = Source.NIGHTSCOUT,
            )
        } else null
        val api = if (allowApi) ApiIobSnapshot.fresh(nowMillis)?.let {
            Values(
                iobUnits = it.iobUnits,
                eiobUnits = it.eiobUnits,
                cobGrams = it.cobGrams,
                timestampMillis = it.timestampMillis,
                source = Source.API,
            )
        } else null
        return select(
            cloneReceptionEnabled = allowClone && CloneSensorRegistry.isReceptionEnabled(),
            cloneRegistered = allowClone && CloneSensorRegistry.hasAnyCloneSensor(),
            clone = clone,
            nightscout = nightscout,
            api = api,
        )
    }
}
