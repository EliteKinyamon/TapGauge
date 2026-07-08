package com.tapgauge.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tapgauge.TapGaugeApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryPoint(val timestamp: Long, val percent: Double)

data class HistoryUiState(
    val tankName: String = "",
    val points: List<HistoryPoint> = emptyList(),
    val csv: String = "",
)

class HistoryViewModel(app: TapGaugeApplication, private val tankId: Long) : ViewModel() {
    private val repo = app.repository
    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val tank = repo.getTank(tankId)
            val engine = repo.loadEngine(tankId)
            val history = repo.history(tankId)
            val pts = history.mapNotNull { p ->
                engine.estimateLevel(p.frequencyHz).percent?.let { HistoryPoint(p.timestamp, it) }
            }
            // CSV export for power users (spec section 3.6): raw (freq, %, timestamp).
            val csv = buildString {
                append("timestamp_ms,frequency_hz,known_level_percent\n")
                history.forEach {
                    append("${it.timestamp},${it.frequencyHz},${it.knownLevelPercent ?: ""}\n")
                }
            }
            _state.value = HistoryUiState(tank?.name ?: "Tank", pts, csv)
        }
    }
}
