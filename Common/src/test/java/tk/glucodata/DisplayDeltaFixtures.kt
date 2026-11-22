package tk.glucodata

import tk.glucodata.ui.DisplayValues

internal fun deltaSnapshot(
    at: Long = System.currentTimeMillis(),
    auto: Float = 110f,
    raw: Float = auto,
    sensor: String = "sensor",
    mode: Int = 0,
    mmol: Boolean = false
) = CurrentDisplaySource.Snapshot(
    timeMillis = at, rate = Float.NaN, sensorId = sensor, sensorGen = 0, index = 0,
    viewMode = mode, source = "test", autoValue = auto, rawValue = raw,
    sharedDisplayValue = auto, sharedMgdl = 110, isMmol = mmol,
    displayValues = DisplayValues(primaryValue = if (mode == 1 || mode == 3) raw else auto,
        primaryStr = "", fullFormatted = "")
)
