/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023-2025 Yan-Bin Diau, Johnson Sun
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.whispertoinput.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.whispertoinput.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max

// Uncompressed 16 kHz mono PCM is what Whisper-style models consume internally,
// so recording directly in that format avoids a lossy encode/decode round trip.
const val SAMPLE_RATE = 16000
const val CHANNEL_COUNT = 1
const val BITS_PER_SAMPLE = 16

private const val TAG = "whisper-input"
private const val WAV_HEADER_SIZE = 44
private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
private const val MIN_BUFFER_DURATION_MS = 200
private const val RECORDING_THREAD_JOIN_TIMEOUT_MS = 2000L

class RecorderManager(context: Context) {
    companion object {
        fun requiredPermissions() = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    // Mirrors the states the (previously unused) recorder_fsm_* constants describe.
    private enum class VadState {
        Idle,       // No speech has been detected yet
        Speaking,   // Speech has been detected at least once
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording: AtomicBoolean = AtomicBoolean(false)

    // Peak absolute sample value observed since the last amplitude report.
    // Read-and-reset, mimicking the semantics of MediaRecorder.getMaxAmplitude().
    private val peakAmplitude: AtomicInteger = AtomicInteger(0)

    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null

    private var onUpdateMicrophoneAmplitude: (Int) -> Unit = { }
    private var onAutoStopRecording: () -> Unit = { }
    private var onAutoCancelRecording: () -> Unit = { }
    private var microphoneAmplitudeUpdateJob: Job? = null

    private val amplitudeReportPeriod: Long
    private val idleSpeakingThreshold: Int
    private val idleCancelThreshold: Int
    private val idleCancelTime: Int
    private val speakingFinishThreshold: Int
    private val speakingFinishTime: Int

    init {
        val resources = context.resources
        this.amplitudeReportPeriod =
            resources.getInteger(R.integer.recorder_amplitude_report_period).toLong()
        this.idleSpeakingThreshold =
            resources.getInteger(R.integer.recorder_fsm_idle_speaking_threshold)
        this.idleCancelThreshold =
            resources.getInteger(R.integer.recorder_fsm_idle_cancel_threshold)
        this.idleCancelTime = resources.getInteger(R.integer.recorder_fsm_idle_cancel_time)
        this.speakingFinishThreshold =
            resources.getInteger(R.integer.recorder_fsm_speaking_finish_threshold)
        this.speakingFinishTime = resources.getInteger(R.integer.recorder_fsm_speaking_finish_time)
    }

    /**
     * Starts capturing raw PCM from the microphone and streams it into [filename] as a WAV file.
     * The 44-byte RIFF/WAVE header is written up-front as placeholder bytes and rewritten with the
     * real sizes once recording ends, so the audio is never buffered in memory or copied twice.
     * [stop] blocks until that rewrite is done, hence the file is complete as soon as it returns.
     */
    fun start(
        filename: String,
        useVoiceActivityDetection: Boolean = false,
        useAudioEffects: Boolean = false,
    ) {
        // Make sure any previous session is fully torn down first.
        stop()

        val file = File(filename)
        if (file.exists()) {
            file.delete()
            Log.e(TAG, "File should not exist")
        }

        val minBufferSize =
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Unable to determine AudioRecord buffer size")
            return
        }
        // Keep a comfortable margin over the minimum so that a busy main thread cannot cause overruns.
        val bufferSize = max(
            minBufferSize * 2,
            SAMPLE_RATE * BYTES_PER_SAMPLE * MIN_BUFFER_DURATION_MS / 1000
        )

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            // Most commonly a SecurityException when RECORD_AUDIO was revoked mid-session.
            Log.e(TAG, "AudioRecord creation failed: ${e.message}")
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            record.release()
            return
        }

        if (useAudioEffects) {
            enableAudioEffects(record.audioSessionId)
        }

        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "startRecording() failed: ${e.message}")
            releaseAudioEffects()
            record.release()
            return
        }

        audioRecord = record
        isRecording.set(true)
        peakAmplitude.set(0)

        recordingThread = Thread({ writeAudioToWavFile(record, file, bufferSize) }, "whisper-recorder")
        recordingThread!!.start()

        startAmplitudeReporting(useVoiceActivityDetection)
    }

    fun stop() {
        microphoneAmplitudeUpdateJob?.cancel()
        microphoneAmplitudeUpdateJob = null

        isRecording.set(false)

        // Stop the hardware first so that a blocking read() in the writer thread returns promptly,
        // then join before releasing, since release() during an in-flight read() would crash.
        val record = audioRecord
        if (record != null && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                record.stop()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "stop() failed: ${e.message}")
            }
        }

        recordingThread?.join(RECORDING_THREAD_JOIN_TIMEOUT_MS)
        recordingThread = null

        releaseAudioEffects()
        audioRecord?.release()
        audioRecord = null
    }

    // Assign onUpdateMicrophoneAmplitude callback
    fun setOnUpdateMicrophoneAmplitude(onUpdateMicrophoneAmplitude: (Int) -> Unit) {
        this.onUpdateMicrophoneAmplitude = onUpdateMicrophoneAmplitude
    }

    // Invoked when voice activity detection decides the user has finished speaking.
    fun setOnAutoStopRecording(onAutoStopRecording: () -> Unit) {
        this.onAutoStopRecording = onAutoStopRecording
    }

    // Invoked when voice activity detection decides the user never started speaking.
    fun setOnAutoCancelRecording(onAutoCancelRecording: () -> Unit) {
        this.onAutoCancelRecording = onAutoCancelRecording
    }

    // Returns whether all of the permissions are granted.
    fun allPermissionsGranted(context: Context): Boolean {
        for (permission in requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }

        return true
    }

    // Reads PCM frames until stopped, then rewinds and writes the final WAV header.
    private fun writeAudioToWavFile(record: AudioRecord, file: File, bufferSize: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val buffer = ByteArray(bufferSize)
        var totalAudioBytes = 0L

        try {
            RandomAccessFile(file, "rw").use { output ->
                output.setLength(0)
                output.write(ByteArray(WAV_HEADER_SIZE))

                while (isRecording.get()) {
                    val bytesRead = record.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        updatePeakAmplitude(buffer, bytesRead)
                        output.write(buffer, 0, bytesRead)
                        totalAudioBytes += bytesRead
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "AudioRecord.read() returned $bytesRead")
                        break
                    }
                }

                output.seek(0)
                output.write(buildWavHeader(totalAudioBytes))
            }

            // A header-only file is not a usable recording; drop it so that the retry button
            // stays hidden and no pointless request is sent.
            if (totalAudioBytes == 0L) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write WAV file: ${e.message}")
        }
    }

    // PCM 16-bit little-endian, as produced by AudioRecord on all supported ABIs.
    private fun updatePeakAmplitude(buffer: ByteArray, bytesRead: Int) {
        var peak = 0
        var i = 0
        while (i + 1 < bytesRead) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val magnitude = abs(sample)
            if (magnitude > peak) {
                peak = magnitude
            }
            i += BYTES_PER_SAMPLE
        }

        // Accumulate the peak across reports, since reads happen more often than reports.
        peakAmplitude.updateAndGet { previous -> max(previous, peak) }
    }

    // Canonical 44-byte RIFF/WAVE header for uncompressed PCM.
    private fun buildWavHeader(totalAudioBytes: Long): ByteArray {
        val dataSize = totalAudioBytes.toInt()
        val byteRate = SAMPLE_RATE * CHANNEL_COUNT * BYTES_PER_SAMPLE
        val blockAlign = CHANNEL_COUNT * BYTES_PER_SAMPLE

        return ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(WAV_HEADER_SIZE - 8 + dataSize)  // Size of everything after this field
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)                              // Size of the fmt chunk body
            putShort(1.toShort())                   // Audio format: 1 = PCM (uncompressed)
            putShort(CHANNEL_COUNT.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(BITS_PER_SAMPLE.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()
    }

    // Periodically reports the microphone amplitude (for the UI ripples) and, when enabled,
    // runs the voice activity detection FSM on top of the same signal.
    private fun startAmplitudeReporting(useVoiceActivityDetection: Boolean) {
        microphoneAmplitudeUpdateJob = CoroutineScope(Dispatchers.Main).launch {
            var vadState = VadState.Idle
            var silenceDuration = 0

            while (isRecording.get()) {
                val amplitude = peakAmplitude.getAndSet(0)
                onUpdateMicrophoneAmplitude(amplitude)

                if (useVoiceActivityDetection) {
                    when (vadState) {
                        VadState.Idle -> {
                            if (amplitude > idleSpeakingThreshold) {
                                vadState = VadState.Speaking
                                silenceDuration = 0
                            } else if (amplitude <= idleCancelThreshold) {
                                silenceDuration += amplitudeReportPeriod.toInt()
                                if (silenceDuration >= idleCancelTime) {
                                    onAutoCancelRecording()
                                    return@launch
                                }
                            }
                        }

                        VadState.Speaking -> {
                            if (amplitude <= speakingFinishThreshold) {
                                silenceDuration += amplitudeReportPeriod.toInt()
                                if (silenceDuration >= speakingFinishTime) {
                                    onAutoStopRecording()
                                    return@launch
                                }
                            } else {
                                silenceDuration = 0
                            }
                        }
                    }
                }

                delay(amplitudeReportPeriod)
            }
        }
    }

    // Both effects are optional device features; a device without them must keep recording fine.
    private fun enableAudioEffects(audioSessionId: Int) {
        try {
            if (NoiseSuppressor.isAvailable()) {
                // AudioEffect.setEnabled() returns a status code rather than throwing.
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply { setEnabled(true) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "NoiseSuppressor unavailable: ${e.message}")
            noiseSuppressor = null
        }

        try {
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl =
                    AutomaticGainControl.create(audioSessionId)?.apply { setEnabled(true) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AutomaticGainControl unavailable: ${e.message}")
            automaticGainControl = null
        }
    }

    private fun releaseAudioEffects() {
        try {
            noiseSuppressor?.release()
            automaticGainControl?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release audio effects: ${e.message}")
        }
        noiseSuppressor = null
        automaticGainControl = null
    }
}
