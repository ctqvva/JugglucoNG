package tk.glucodata

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.data.prediction.GlucosePredictionPoint
import tk.glucodata.data.prediction.GlucosePredictionSeries
import tk.glucodata.data.prediction.GlucosePredictionSeriesKind
import tk.glucodata.ui.GlucosePoint

class GluciferPredictionSnapshotTests {
    @Test fun `prediction history and sealed calibration follow phone units before canonical export`() {
        val history = listOf(GlucosePoint(value = 180f, rawValue = 200f, timestamp = 1000, time = "", sealedDisplayValue = 190f))
        for (mmol in listOf(false, true)) {
            val prepared = GluciferPredictionSnapshot.prepareHistory(history, mmol, 0, 0).single()
            assertEquals(if (mmol) 9.99f else 180f, prepared.value, 0.00001f)
            assertEquals(if (mmol) 11.1f else 200f, prepared.rawValue, 0.00001f)
            assertEquals(if (mmol) 10.545f else 190f, prepared.sealedDisplayValue!!, 0.00001f)
            val curves = listOf(GlucosePredictionSeries(GlucosePredictionSeriesKind.CALIBRATED,
                listOf(GlucosePredictionPoint(prepared.timestamp, prepared.sealedDisplayValue!!, 1f))))
            val exported = JSONArray(GluciferPredictionSnapshot.serialize(curves, mmol)).getJSONObject(0)
            assertEquals("calibrated", exported.getString("kind"))
            val point = exported.getJSONArray("points").getJSONObject(0)
            assertEquals(190.0, point.getDouble("mgdl"), 0.0)
            assertEquals(1000L, point.getLong("time_ms"))
        }
        assertEquals(180f, history.single().value, 0f)
    }

    @Test fun `prediction history follows independent current-reading thinning without averaging`() {
        val base = 1_700_000_100_000L // Five-minute boundary, with both buckets closed.
        val history = (0..9).map { index ->
            GlucosePoint(value = 100f + index, rawValue = 90f + index,
                timestamp = base + index * 60_000L, time = "", sealedDisplayValue = 110f + index)
        }
        val prepared = GluciferPredictionSnapshot.prepareHistory(history, false, 0, 5)
        assertEquals(listOf(history[4].timestamp, history[9].timestamp), prepared.map { it.timestamp })
        assertEquals(listOf(104f, 109f), prepared.map { it.value })
        assertEquals(listOf(114f, 119f), prepared.map { it.sealedDisplayValue })
    }
}
