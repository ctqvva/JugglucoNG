package tk.glucodata.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import tk.glucodata.SensorIdentity

/** Role changes do not change the user's time window. Resume follow on a new timeline point. */
internal class ChartAutoFollowState {
    private var sensorId: String? = null
    private var handoffTimestamp: Long? = null

    fun observe(currentSensorId: String?, latestTimestamp: Long): Boolean {
        if (!sensorId.isNullOrBlank() && !currentSensorId.isNullOrBlank() &&
            !SensorIdentity.matches(sensorId, currentSensorId)) handoffTimestamp = latestTimestamp
        sensorId = currentSensorId
        if (handoffTimestamp?.let { latestTimestamp > it } == true) handoffTimestamp = null
        return canFollow(latestTimestamp)
    }

    fun canFollow(latestTimestamp: Long): Boolean = handoffTimestamp?.let { latestTimestamp > it } ?: true
}

internal fun chartTimeAtProgress(from: Long, to: Long, progress: Float): Long =
    from + ((to - from).toDouble() * progress.coerceIn(0f, 1f).toDouble()).toLong()

internal suspend fun animateChartTime(from: Long, to: Long, onFrame: (Long) -> Unit) {
    Animatable(0f).animateTo(1f, spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )) { onFrame(chartTimeAtProgress(from, to, value)) }
}
