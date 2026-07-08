package com.tapgauge.ui.screens

import android.annotation.SuppressLint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tapgauge.TapGaugeApplication
import com.tapgauge.dsp.AudioCaptureEngine
import com.tapgauge.dsp.SpectralAnalyzer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.ln

data class DiagUiState(
    val running: Boolean = false,
    /** Recent normalized spectrum columns (0..1), newest last, for the heatmap. */
    val columns: List<FloatArray> = emptyList(),
    val peakHz: Double? = null,
    val sampleRate: Int = 44100,
)

/**
 * Developer diagnostics (spec section 8): continuously computes the magnitude
 * spectrum of a rolling window and streams downsampled columns for a live
 * spectrogram, plus the current top peak. This is the tool used to tune the
 * onset thresholds and resonance-band bounds of spec section 5 against real recordings.
 * Gated behind BuildConfig.DIAGNOSTICS_ENABLED.
 */
class DiagnosticsViewModel(private val app: TapGaugeApplication) : ViewModel() {
    private val settings = app.settings
    private val _state = MutableStateFlow(DiagUiState(sampleRate = settings.sampleRate))
    val state: StateFlow<DiagUiState> = _state.asStateFlow()
    private var job: Job? = null

    private val displayBins = 96
    private val maxColumns = 120

    @SuppressLint("MissingPermission") // gated by RequireMicPermission
    fun start() {
        if (_state.value.running) return
        job = viewModelScope.launch {
            val sr = settings.sampleRate
            val analyzer = SpectralAnalyzer(sr)
            val capture = AudioCaptureEngine(sr)
            val windowSamples = 2048
            val buf = ArrayList<Double>(windowSamples)
            _state.update { it.copy(running = true, columns = emptyList(), sampleRate = sr) }

            capture.frames().collect { frame ->
                for (x in frame) buf.add(x)
                if (buf.size >= windowSamples) {
                    val seg = DoubleArray(windowSamples) { buf[it] }
                    // slide by half a window (overlap for a smoother spectrogram)
                    val keep = windowSamples / 2
                    val tail = ArrayList<Double>(keep)
                    for (i in windowSamples - keep until buf.size) tail.add(buf[i])
                    buf.clear(); buf.addAll(tail)

                    val mags = analyzer.magnitudeSpectrum(seg)
                    val nfft = (mags.size - 1) * 2
                    // limit display to ~0..3500 Hz
                    val hiBin = minOf(mags.size - 1, (3500.0 * nfft / sr).toInt())
                    val col = FloatArray(displayBins)
                    for (b in 0 until displayBins) {
                        val lo = b * hiBin / displayBins
                        val hi = ((b + 1) * hiBin / displayBins).coerceAtLeast(lo + 1)
                        var m = 0.0
                        for (k in lo until hi) if (mags[k] > m) m = mags[k]
                        col[b] = ln(1.0 + m).toFloat() // log-scale for visibility
                    }
                    val maxV = (col.maxOrNull() ?: 1f).coerceAtLeast(1e-6f)
                    for (b in col.indices) col[b] = col[b] / maxV

                    val peaks = analyzer.findPeaksInBand(mags, nfft, 60.0, 3000.0)
                    val peak = analyzer.rejectHarmonics(peaks).maxByOrNull { it.magnitude }

                    _state.update { st ->
                        val cols = (st.columns + col).takeLast(maxColumns)
                        st.copy(columns = cols, peakHz = peak?.freqHz)
                    }
                }
            }
        }
    }

    fun stop() { job?.cancel(); job = null; _state.update { it.copy(running = false) } }
    override fun onCleared() { super.onCleared(); job?.cancel() }
}
