package com.tapgauge.dsp

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext

/**
 * Owns the [AudioRecord] and exposes a cold [Flow] of PCM frames (spec section 4/5.1).
 *
 * Design points from the spec:
 *   * Mono, 16-bit PCM, configurable sample rate (44100 default, 48000 option).
 *   * Frames are ~20 ms so onset detection stays low-latency (spec section 5.1).
 *   * The mic is opened only while the returned Flow is being collected -- i.e.
 *     only during an explicit measurement session. There is NO background or
 *     always-on listener (spec section 5.1 / 8, battery + privacy).
 *   * Raw audio never touches disk: frames are emitted downstream, analysed in
 *     memory, and discarded. Nothing here writes a file (spec section 4 privacy stance).
 */
class AudioCaptureEngine(
    private val sampleRate: Int = 44100,
    private val frameMillis: Int = 20,
) {
    /** Number of samples in one emitted frame. */
    val frameSize: Int = (sampleRate * frameMillis / 1000).coerceAtLeast(64)

    /**
     * Cold flow of normalized mono PCM frames (values in roughly [-1, 1]).
     * Collection opens the mic; cancellation/completion releases it. The flow
     * runs until the collecting coroutine is cancelled.
     *
     * Emits [DoubleArray] frames of [frameSize] samples. Requires RECORD_AUDIO;
     * the caller must have obtained the runtime permission first.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @SuppressLint("MissingPermission")
    fun frames(): Flow<DoubleArray> = flow {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Size the internal buffer generously (several frames) to avoid overruns,
        // but read in small frame-sized chunks for low-latency onset detection.
        val bufferBytes = maxOf(minBuf, frameSize * 2 * 4)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        require(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize"
        }

        val shortBuf = ShortArray(frameSize)
        try {
            recorder.startRecording()
            while (currentCoroutineContext().isActive) {
                var read = 0
                // Fill exactly one frame (AudioRecord may return partial reads).
                while (read < frameSize && currentCoroutineContext().isActive) {
                    val n = recorder.read(shortBuf, read, frameSize - read)
                    if (n <= 0) break
                    read += n
                }
                if (read <= 0) continue
                val frame = DoubleArray(read) { i -> shortBuf[i] / 32768.0 }
                emit(frame)
            }
        } finally {
            // Always release the mic promptly when collection ends/cancels.
            try { recorder.stop() } catch (_: IllegalStateException) {}
            recorder.release()
        }
    }
}
