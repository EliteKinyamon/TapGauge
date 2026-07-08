package com.tapgauge.ui.screens

import android.annotation.SuppressLint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tapgauge.TapGaugeApplication
import com.tapgauge.audio.MeasureEvent
import com.tapgauge.audio.MeasurementSession
import com.tapgauge.calibration.CalibrationEngine
import com.tapgauge.calibration.CalibrationPoint
import com.tapgauge.calibration.Confidence
import com.tapgauge.data.TankType
import com.tapgauge.dsp.AudioCaptureEngine
import com.tapgauge.dsp.ReadingResult
import com.tapgauge.dsp.SpectralAnalyzer
import com.tapgauge.ui.components.ReadingConfidence
import com.tapgauge.ui.components.scoreReading
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MeasureUiState(
    val tankName: String = "",
    val tankType: TankType = TankType.OTHER,
    val measuring: Boolean = false,
    val level: Double = 0.0,
    val tapsDone: Int = 0,
    val tapsNeeded: Int = 3,
    val lastReadingHz: Double? = null,
    val percent: Double? = null,
    val readingConfidence: ReadingConfidence? = null,
    val calibrationConfidence: Confidence = Confidence.UNCALIBRATED,
    val anchorCount: Int = 0,
    val extrapolated: Boolean = false,
    val daysUntilEmpty: Double? = null,
    val message: String? = null,
    /** Increments each time a knock is detected so the screen can fire haptics. */
    val hapticNonce: Int = 0,
)

/**
 * Drives the measurement screen (spec section 3.4): runs a 3-tap session, maps the
 * combined frequency to a fill % via the tank's [CalibrationEngine], scores
 * confidence (spec section 5.6), stores the tap as silent history (spec section 6.3), and
 * estimates days-until-empty (spec section 3.4).
 */
class MeasureViewModel(
    private val app: TapGaugeApplication,
    private val tankId: Long,
) : ViewModel() {

    private val repo = app.repository
    private val settings = app.settings

    private val _state = MutableStateFlow(MeasureUiState())
    val state: StateFlow<MeasureUiState> = _state.asStateFlow()

    private var measureJob: Job? = null

    init {
        viewModelScope.launch {
            val tank = repo.getTank(tankId)
            val engine = repo.loadEngine(tankId)
            _state.update {
                it.copy(
                    tankName = tank?.name ?: "Tank",
                    tankType = tank?.type ?: TankType.OTHER,
                    calibrationConfidence = engine.confidence(),
                    anchorCount = engine.anchorCount(),
                )
            }
        }
    }

    @SuppressLint("MissingPermission") // gated by RequireMicPermission in the UI
    fun startMeasurement() {
        if (_state.value.measuring) return
        measureJob = viewModelScope.launch {
            val tank = repo.getTank(tankId) ?: return@launch
            val sr = settings.sampleRate
            val analyzer = SpectralAnalyzer(sr)
            val session = MeasurementSession(
                AudioCaptureEngine(sr), analyzer, sr, settings.tapThresholdRatio.toDouble(),
            )
            val engine = repo.loadEngine(tankId)

            _state.update {
                it.copy(measuring = true, tapsDone = 0, percent = null,
                    readingConfidence = null, message = null, extrapolated = false)
            }

            session.run(tank.bandLowHz, tank.bandHighHz, tapsNeeded = _state.value.tapsNeeded)
                .collect { ev ->
                    when (ev) {
                        is MeasureEvent.Level ->
                            _state.update { it.copy(level = ev.rms) }
                        is MeasureEvent.TapDetected ->
                            _state.update { it.copy(hapticNonce = it.hapticNonce + 1) }
                        is MeasureEvent.TapAnalyzed ->
                            _state.update { it.copy(tapsDone = ev.index + 1) }
                        is MeasureEvent.Completed ->
                            onReadingComplete(ev.reading, engine)
                    }
                }
        }
    }

    fun stopMeasurement() {
        measureJob?.cancel()
        measureJob = null
        _state.update { it.copy(measuring = false, level = 0.0) }
    }

    private suspend fun onReadingComplete(reading: ReadingResult, engine: CalibrationEngine) {
        val hz = reading.frequencyHz
        if (hz == null || !reading.ok) {
            _state.update {
                it.copy(measuring = false, level = 0.0,
                    message = "Low confidence \u2014 try tapping the same spot again.")
            }
            return
        }
        val est = engine.estimateLevel(hz)
        val conf = scoreReading(reading, est.extrapolated)

        // Store as a silent (unlabeled) measurement tap for future refinement (spec section 6.3).
        repo.addPoint(tankId, System.currentTimeMillis(), hz, null, reading.prominence)

        // Days-until-empty from recent readings mapped through the current curve (spec section 3.4).
        val history = repo.history(tankId)
        val pts = history.mapNotNull { p ->
            engine.estimateLevel(p.frequencyHz).percent?.let { pc ->
                (p.timestamp / 1000.0) to pc
            }
        }.takeLast(12)
        val days = CalibrationEngine.daysUntilEmpty(pts)

        val notCalibratedHint = when (_state.value.tankType) {
            TankType.GREY, TankType.BLACK ->
                "Not calibrated yet \u2014 log \u201cJust Dumped\u201d (0%) or run Driveway Calibration."
            else ->
                "Not calibrated yet \u2014 log Full/Empty to start."
        }
        _state.update {
            it.copy(
                measuring = false, level = 0.0, lastReadingHz = hz,
                percent = est.percent, readingConfidence = conf,
                extrapolated = est.extrapolated, daysUntilEmpty = days,
                calibrationConfidence = engine.confidence(),
                anchorCount = engine.anchorCount(),
                message = if (est.percent == null) notCalibratedHint else null,
            )
        }
    }

    /** In-context calibration shortcut (spec section 3.4): log the just-measured
     *  frequency as Full (100%) or Empty (0%). */
    fun logAnchorFromLastReading(percent: Double, kind: String) {
        val hz = _state.value.lastReadingHz
        if (hz == null) {
            _state.update { it.copy(message = "Take a measurement first, then log Full/Empty.") }
            return
        }
        viewModelScope.launch {
            val engine = repo.loadEngine(tankId)
            // Drift nudge for a fresh Full (spec section 6.5).
            if (percent == 100.0) {
                val drift = engine.checkDrift(hz)
                if (drift.drifted) {
                    _state.update { it.copy(message = "Recalibrating: ${drift.reason}") }
                }
            }
            val check = engine.addAnchor(CalibrationPoint(System.currentTimeMillis(), hz, percent))
            if (!check.ok) {
                _state.update { it.copy(message = "Couldn\u2019t add point: ${check.reason}") }
                return@launch
            }
            repo.addPoint(tankId, System.currentTimeMillis(), hz, percent, 0.0, kind)
            val refreshed = repo.loadEngine(tankId)
            _state.update {
                it.copy(
                    calibrationConfidence = refreshed.confidence(),
                    anchorCount = refreshed.anchorCount(),
                    message = "Logged ${percent.toInt()}% \u2014 calibration updated.",
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        measureJob?.cancel()
    }
}
