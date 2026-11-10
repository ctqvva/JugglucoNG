package tk.glucodata.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.data.prediction.analysis.ForecastMoment
import tk.glucodata.data.prediction.analysis.PredictionAnalysisRepository
import tk.glucodata.data.prediction.analysis.PredictionAnalysisSnapshot

/**
 * State for the Prediction accuracy screen and its past-forecast inspector. Everything is read
 * on demand from the recorded forecasts; nothing here records or recomputes a forecast.
 */
class PredictionAnalysisViewModel : ViewModel() {
    private val repository = PredictionAnalysisRepository(Applic.app)

    private val _periodDays = MutableStateFlow(DEFAULT_PERIOD_DAYS)
    val periodDays = _periodDays.asStateFlow()

    private val _horizonMinutes = MutableStateFlow(30)
    val horizonMinutes = _horizonMinutes.asStateFlow()

    /** Null while the first load runs. */
    private val _snapshot = MutableStateFlow<PredictionAnalysisSnapshot?>(null)
    val snapshot = _snapshot.asStateFlow()

    private val _origins = MutableStateFlow<List<Long>?>(null)
    val origins = _origins.asStateFlow()

    private val _selectedIndex = MutableStateFlow(-1)
    val selectedIndex = _selectedIndex.asStateFlow()

    private val _moment = MutableStateFlow<ForecastMoment?>(null)
    val moment = _moment.asStateFlow()

    private var momentJob: Job? = null
    private var refreshJob: Job? = null

    /** One load at a time: a slow 90-day load must not land after a quicker 7-day one. */
    fun refresh() {
        val period = _periodDays.value
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            runCatching { repository.snapshot(period) }
                .onSuccess { if (_periodDays.value == period) _snapshot.value = it }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) Log.stack(LOG_ID, "snapshot", it) }
        }
    }

    fun setPeriodDays(days: Int) {
        if (_periodDays.value == days) return
        _periodDays.value = days
        refresh()
    }

    fun setHorizonMinutes(minutes: Int) {
        _horizonMinutes.value = minutes
    }

    /** Loads the inspector's timeline and opens its newest forecast whose two-hour outcome is in. */
    fun loadOrigins() {
        viewModelScope.launch {
            val origins = runCatching { repository.origins(INSPECTOR_PERIOD_DAYS) }.getOrDefault(emptyList())
            _origins.value = origins
            if (origins.isEmpty()) return@launch
            val settled = System.currentTimeMillis() - 2 * 60 * 60_000L
            select(origins.indexOfLast { it <= settled }.takeIf { it >= 0 } ?: origins.lastIndex)
        }
    }

    fun select(index: Int) {
        val origins = _origins.value ?: return
        if (index !in origins.indices) return
        _selectedIndex.value = index
        momentJob?.cancel()
        momentJob = viewModelScope.launch {
            _moment.value = runCatching { repository.moment(origins[index]) }.getOrNull()
        }
    }

    fun clearRecorded() {
        viewModelScope.launch {
            runCatching { repository.clear() }
            _origins.value = emptyList()
            _moment.value = null
            refresh()
        }
    }

    companion object {
        private const val LOG_ID = "PredictionAnalysisVM"
        const val DEFAULT_PERIOD_DAYS = 30
        val PERIODS = listOf(7, 30, 90)
        private const val INSPECTOR_PERIOD_DAYS = 90
    }
}
