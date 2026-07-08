package com.tapgauge.ui.screens

import android.annotation.SuppressLint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tapgauge.TapGaugeApplication
import com.tapgauge.audio.MeasureEvent
import com.tapgauge.audio.MeasurementSession
import com.tapgauge.calibration.CalibrationPoint
import com.tapgauge.calibration.Confidence
import com.tapgauge.dsp.AudioCaptureEngine
import com.tapgauge.dsp.SpectralAnalyzer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AnchorRow(val frequencyHz: Double, val percent: Double, val kind: String?)

data class CalibrateUiState(
    val tankName: String = "",
    val measuring: Boolean = false,
    val level: Double = 0.0,
    val tapsDone: Int = 0,
    val lastReadingHz: Double? = null,
    val lastReadingOk: Boolean = false,
    val anchors: List<AnchorRow> = emptyList(),
    val confidence: Confidence = Confidence.UNCALIBRATED,
    val message: String? = null,
    val hapticNonce: Int = 0,
)

/**
 * Calibration workflow (spec section 3.3): tap the tank, then log that frequency
 * against a known level from one of several methods (Full/Empty/weigh-in/
 * existing-gauge/known-volume). Enforces monotonicity and surfaces drift.
 */
class CalibrateViewModel(
    private val app: TapGaugeApplication,
    private val tankId: Long,
) : ViewModel() {

    private val repo = app.repository
    private val settings = app.settings
    private val _state = MutableStateFlow(CalibrateUiState())
    val state: StateFlow<CalibrateUiState> = _state.asStateFlow()
    private var job: Job? = null

    init { refresh() }

    private fun refresh() {
        viewModelScope.launch {
            val tank = repo.getTank(tankId)
            val engine = repo.loadEngine(tankId)
            val rows = engine.anchors().map {
                AnchorRow(it.frequencyHz, it.knownLevelPercent ?: 0.0, null)
            }
            _state.update {
                it.copy(tankName = tank?.name ?: "Tank", anchors = rows,
                    confidence = engine.confidence())
            }
        }
    }

    @SuppressLint("MissingPermission") // gated by RequireMicPermission
    fun tapToCapture() {
        if (_state.value.measuring) return
        job = viewModelScope.launch {
            val tank = repo.getTank(tankId) ?: return@launch
            val sr = settings.sampleRate
            val session = MeasurementSession(
                AudioCaptureEngine(sr), SpectralAnalyzer(sr), sr,
                settings.tapThresholdRatio.toDouble(),
            )
            _state.update { it.copy(measuring = true, tapsDone = 0, message = null) }
            session.run(tank.bandLowHz, tank.bandHighHz).collect { ev ->
                when (ev) {
                    is MeasureEvent.Level -> _state.update { it.copy(level = ev.rms) }
                    is MeasureEvent.TapDetected ->
                        _state.update { it.copy(hapticNonce = it.hapticNonce + 1) }
                    is MeasureEvent.TapAnalyzed -> _state.update { it.copy(tapsDone = ev.index + 1) }
                    is MeasureEvent.Completed -> {
                        val r = ev.reading
                        _state.update {
                            it.copy(measuring = false, level = 0.0,
                                lastReadingHz = r.frequencyHz, lastReadingOk = r.ok,
                                message = if (!r.ok) "Inconsistent taps \u2014 capture again." else
                                    "Captured ${r.frequencyHz?.toInt()} Hz \u2014 now choose a known level.")
                        }
                    }
                }
            }
        }
    }

    fun cancel() { job?.cancel(); job = null; _state.update { it.copy(measuring = false, level = 0.0) } }

    /** Percent-full by weight for propane (spec section 3.3 weigh-in method):
     *  (current - tare) / netFullWeight * 100. */
    fun percentFromWeight(currentWeight: Double, tare: Double, netFullWeight: Double): Double? {
        if (netFullWeight <= 0) return null
        return ((currentWeight - tare) / netFullWeight * 100.0).coerceIn(0.0, 100.0)
    }

    fun logAnchor(percent: Double, kind: String) {
        val hz = _state.value.lastReadingHz
        if (hz == null) {
            _state.update { it.copy(message = "Tap the tank first to capture a frequency.") }
            return
        }
        viewModelScope.launch {
            val engine = repo.loadEngine(tankId)
            if (percent == 100.0) {
                val drift = engine.checkDrift(hz)
                if (drift.drifted) _state.update { it.copy(message = "Note: ${drift.reason}") }
            }
            val check = engine.addAnchor(CalibrationPoint(System.currentTimeMillis(), hz, percent))
            if (!check.ok) {
                _state.update { it.copy(message = "Rejected: ${check.reason}") }
                return@launch
            }
            repo.addPoint(tankId, System.currentTimeMillis(), hz, percent, 0.0, kind)
            _state.update { it.copy(message = "Logged ${percent.toInt()}%.") }
            refresh()
        }
    }

    fun resetCalibration() {
        viewModelScope.launch {
            repo.resetCalibration(tankId)
            _state.update { it.copy(message = "Calibration reset.") }
            refresh()
        }
    }

    override fun onCleared() { super.onCleared(); job?.cancel() }
}
