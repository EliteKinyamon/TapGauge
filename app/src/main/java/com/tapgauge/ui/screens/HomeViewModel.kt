package com.tapgauge.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tapgauge.TapGaugeApplication
import com.tapgauge.calibration.Confidence
import com.tapgauge.data.TankProfileEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

data class TankSummary(
    val entity: TankProfileEntity,
    val lastPercent: Double?,
    val confidence: Confidence,
    val extrapolated: Boolean,
)

class HomeViewModel(app: TapGaugeApplication) : ViewModel() {
    private val repo = app.repository

    @OptIn(ExperimentalCoroutinesApi::class)
    val summaries: StateFlow<List<TankSummary>> =
        repo.observeTanks().mapLatest { tanks ->
            tanks.map { t ->
                val engine = repo.loadEngine(t.id)
                val history = repo.history(t.id)
                val last = history.maxByOrNull { it.timestamp }
                val est = last?.let { engine.estimateLevel(it.frequencyHz) }
                TankSummary(t, est?.percent, engine.confidence(), est?.extrapolated ?: false)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
