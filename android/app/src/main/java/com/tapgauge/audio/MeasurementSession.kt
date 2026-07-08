package com.tapgauge.audio

import android.Manifest
import androidx.annotation.RequiresPermission
import com.tapgauge.dsp.AudioCaptureEngine
import com.tapgauge.dsp.KnockDetector
import com.tapgauge.dsp.ReadingResult
import com.tapgauge.dsp.SpectralAnalyzer
import com.tapgauge.dsp.TapResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlin.math.sqrt

/** Events streamed to the UI during a measurement session (spec section 3.4). */
sealed interface MeasureEvent {
    /** Live RMS input level for the meter (spec section 3.4 / 5.1). */
    data class Level(val rms: Double) : MeasureEvent
    /** A knock was detected -> UI should fire haptic feedback immediately (spec section 3.4/7). */
    data class TapDetected(val index: Int) : MeasureEvent
    /** A detected knock was analysed into a (maybe-null) fundamental. */
    data class TapAnalyzed(val index: Int, val result: TapResult) : MeasureEvent
    /** All taps collected and combined (spec section 5.5). */
    data class Completed(val reading: ReadingResult) : MeasureEvent
}

/**
 * Ties [AudioCaptureEngine] -> [KnockDetector] -> [SpectralAnalyzer] together
 * into one measurement session that collects [tapsNeeded] knocks and combines
 * them (spec sections 3.4, 5.2-5.5). Pure coroutine/Flow; the mic is only open while
 * the returned flow is collected.
 */
class MeasurementSession(
    private val capture: AudioCaptureEngine,
    private val analyzer: SpectralAnalyzer,
    private val sampleRate: Int,
    private val thresholdRatio: Double = 6.0,
) {
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun run(
        fLo: Double,
        fHi: Double,
        tapsNeeded: Int = 3,
        windowMs: Int = 100,
    ): Flow<MeasureEvent> = channelFlow {
        val detector = KnockDetector(sampleRate, thresholdRatio = thresholdRatio)
        val windowSamples = sampleRate * windowMs / 1000
        val taps = ArrayList<TapResult>(tapsNeeded)
        val window = ArrayList<Double>(windowSamples)
        var collecting = false

        capture.frames().collect { frame ->
            // Live level meter.
            var acc = 0.0
            for (x in frame) acc += x * x
            trySend(MeasureEvent.Level(sqrt(acc / frame.size)))

            if (collecting) {
                for (x in frame) window.add(x)
                if (window.size >= windowSamples) {
                    val seg = DoubleArray(windowSamples) { window[it] }
                    val res = analyzer.analyzeTap(seg, fLo, fHi)
                    taps.add(res)
                    trySend(MeasureEvent.TapAnalyzed(taps.size - 1, res))
                    collecting = false
                    window.clear()
                    if (taps.size >= tapsNeeded) {
                        trySend(MeasureEvent.Completed(analyzer.combineTaps(taps)))
                        close()
                    }
                }
            } else if (detector.accept(frame)) {
                trySend(MeasureEvent.TapDetected(taps.size))
                collecting = true
                window.clear()
                for (x in frame) window.add(x) // include the onset frame itself
            }
        }
        awaitClose { }
    }
}
