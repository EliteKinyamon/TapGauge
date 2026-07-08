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
    val tankType: TankType = TankType.OTHER,
    val capacityGallons: Double? = null,
    val measuring: Boolean = false,
    val level: Double = 0.0,
    val tapsDone: Int = 0,
    val lastReadingHz: Double? = null,
    val lastReadingOk: Boolean = false,
    val anchors: List<AnchorRow> = emptyList(),
    val confidence: Confidence = Confidence.UNCALIBRATED,
    // Driveway Calibration running total of clean water added (re-scope section 3.2).
    val drivewayCumulativeGallons: Double = 0.0,
    val message: String? = null,
    val hapticNonce: Int = 0,
)

/**
 * Calibration workflow. RV re-scope (section 3):
 *   FRESH        -> Full/Empty anchors (unchanged) + optional gauge cross-check.
 *   GREY/BLACK   -> "Just Dumped + Rinsed" (0%) + Driveway Calibration, which logs
 *                   (frequency, cumulative-gallons / capacity) anchors from CLEAN
 *                   water added at home. Never calibrate a black tank with waste.
 *   OTHER        -> generic Full/Empty + weigh-in + gauge (original behaviour).
 *
 * Monotonicity + drift come from the untouched CalibrationEngine (section 6 / non-goal 6).
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
                it.copy(
                    tankName = tank?.name ?: "Tank",
                    tankType = tank?.type ?: TankType.OTHER,
                    capacityGallons = tank?.capacityGallons,
                    anchors = rows,
                    confidence = engine.confidence(),
                )
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

    /** Persist a capacity the user confirms/enters during the driveway flow. */
    fun setCapacityGallons(gallons: Double?) {
        viewModelScope.launch {
            val tank = repo.getTank(tankId) ?: return@launch
            repo.updateTank(tank.copy(capacityGallons = gallons))
            _state.update { it.copy(capacityGallons = gallons) }
        }
    }

    /** Percent-full by weight for propane / OTHER (weigh-in method):
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

    // ----- Grey/Black flows (re-scope section 3.2) ----- //

    /** "Just Dumped + Rinsed" -> 0%. The natural, frequent anchor at a dump station. */
    fun logJustDumped() {
        _state.update { it.copy(drivewayCumulativeGallons = 0.0) }
        logAnchor(0.0, "dumped")
    }

    fun resetDriveway() {
        _state.update { it.copy(drivewayCumulativeGallons = 0.0,
            message = "Driveway total reset to 0 gal.") }
    }

    /**
     * Driveway Calibration increment (section 3.2): the user has just added
     * [addedGallons] of CLEAN water (counted jug or timed hose) and tapped.
     * We advance the running total, convert cumulative gallons -> %-of-capacity via
     * the untouched engine helper, and log that as an anchor.
     */
    fun logDrivewayIncrement(addedGallons: Double) {
        val cap = _state.value.capacityGallons
        if (cap == null || cap <= 0.0) {
            _state.update { it.copy(message = "Enter the tank\u2019s capacity (gallons) first.") }
            return
        }
        if (_state.value.lastReadingHz == null) {
            _state.update { it.copy(message = "Add the water, then tap the tank before logging.") }
            return
        }
        val cumulative = _state.value.drivewayCumulativeGallons + addedGallons
        val pct = CalibrationEngine.percentFromCumulativeGallons(cumulative, cap)
        if (pct == null) {
            _state.update { it.copy(message = "Couldn\u2019t compute % from capacity.") }
            return
        }
        _state.update { it.copy(drivewayCumulativeGallons = cumulative) }
        logAnchor(pct, "volume")
        _state.update {
            it.copy(message = "Added ${"%.1f".format(cumulative)} of " +
                "${"%.0f".format(cap)} gal \u2014 logged ${pct.toInt()}%.")
        }
    }

    fun resetCalibration() {
        viewModelScope.launch {
            repo.resetCalibration(tankId)
            _state.update { it.copy(message = "Calibration reset.", drivewayCumulativeGallons = 0.0) }
            refresh()
        }
    }

    override fun onCleared() { super.onCleared(); job?.cancel() }
}
