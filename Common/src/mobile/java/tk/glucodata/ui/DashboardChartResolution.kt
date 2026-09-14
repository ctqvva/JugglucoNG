package tk.glucodata.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything the chart derives from its data before it can draw: the
 * smoothed primary series, the smoothed peer series, and the resolved model.
 *
 * Kept together because they must agree — the draw path indexes [renderData]
 * and asks [chartModel] for the value at that point's timestamp — so they are
 * only ever swapped in as one.
 */
internal class ChartResolution(
    val renderData: List<GlucosePoint>,
    val peerChartSeries: List<PeerSensorChartSeries>,
    val chartModel: tk.glucodata.chart.HistoryChartModel,
)

/**
 * The inputs a resolution is built from. Lists are compared by identity: the
 * timeline is tens of thousands of points, and a structural comparison on every
 * recomposition would cost about what the resolution itself does.
 */
internal class ChartResolutionInputs(
    val safeData: List<GlucosePoint>,
    val multiSensorDisplay: MultiSensorDisplayData,
    val graphSmoothingMinutes: Int,
    val collapseSmoothedData: Boolean,
    val mainSensorOwnership: tk.glucodata.chart.MainSensorOwnership,
    val calibrationRevision: Long,
    val viewMode: Int,
    val hasCalibration: Boolean,
    val hideInitialWhenCalibrated: Boolean,
    val primarySerial: String?,
    val primaryColorArgb: Int,
) {
    fun sameAs(other: ChartResolutionInputs): Boolean =
        safeData === other.safeData &&
            multiSensorDisplay === other.multiSensorDisplay &&
            graphSmoothingMinutes == other.graphSmoothingMinutes &&
            collapseSmoothedData == other.collapseSmoothedData &&
            mainSensorOwnership === other.mainSensorOwnership &&
            calibrationRevision == other.calibrationRevision &&
            viewMode == other.viewMode &&
            hasCalibration == other.hasCalibration &&
            hideInitialWhenCalibrated == other.hideInitialWhenCalibrated &&
            primarySerial == other.primarySerial &&
            primaryColorArgb == other.primaryColorArgb
}

/**
 * Resolves the chart off the main thread, keeping the last resolution on
 * screen until the next one is ready.
 *
 * Smoothing and the model builder are each a pass over the list the chart is
 * handed — the stretches loaded around its viewport, see
 * GlucoseRepository.getMergedWindowFlowRaw, and before that the whole store.
 * Run inside `remember` they were a main-thread pass per emission, once a
 * minute. The first resolution is still synchronous, so the chart's first
 * frame is the same frame it always was; only the updates move.
 *
 * [inputs] must be the same instance while nothing changed — build it with
 * `remember` keyed on its parts. The effect is keyed on it, so a change of
 * inputs while a resolution is in flight abandons the stale computation, and
 * a recomposition that changes nothing (a viewport animation, a tick of the
 * age colour) leaves the in-flight one alone.
 */
private class ChartResolutionHolder(
    var resolvedFor: ChartResolutionInputs,
    var resolution: ChartResolution,
)

@Composable
internal fun rememberChartResolution(
    inputs: ChartResolutionInputs,
    resolve: (ChartResolutionInputs) -> ChartResolution,
): ChartResolution {
    // A plain holder rather than state, so the synchronous path below can
    // replace the resolution during composition without writing a state that
    // this same composition has already read; the async path bumps
    // [asyncRevision] to be recomposed.
    val holder = remember { ChartResolutionHolder(inputs, resolve(inputs)) }
    var asyncRevision by remember { mutableStateOf(0) }
    // The first frame *with data* is the one that matters, and it is still
    // resolved in composition: a chart that composed empty — the history
    // screen waiting for a range to load — draws its first readings in the
    // frame they arrive, not a resolution later.
    if (!inputs.sameAs(holder.resolvedFor) && holder.resolution.renderData.isEmpty() && inputs.safeData.isNotEmpty()) {
        holder.resolution = resolve(inputs)
        holder.resolvedFor = inputs
    }
    LaunchedEffect(inputs) {
        if (inputs.sameAs(holder.resolvedFor)) return@LaunchedEffect
        val resolution = withContext(Dispatchers.Default) { resolve(inputs) }
        holder.resolution = resolution
        holder.resolvedFor = inputs
        asyncRevision++
    }
    return remember(asyncRevision, holder.resolution) { holder.resolution }
}
