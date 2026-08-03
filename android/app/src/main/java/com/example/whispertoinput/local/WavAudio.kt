package com.example.whispertoinput.local

import java.io.File
import java.io.RandomAccessFile

// Decodes the app's own recordings: canonical 44-byte RIFF/WAVE header, 16 kHz mono, 16-bit PCM
// (see RecorderManager.buildWavHeader). whisper.cpp's whisper_full() takes mono float32 samples
// in [-1, 1] at 16 kHz, so this is the only conversion needed.
object WavAudio {
    private const val WAV_HEADER_SIZE = 44
    private const val SAMPLE_RATE = 16000
    private const val BYTES_PER_SAMPLE = 2

    fun durationSeconds(file: File): Float {
        val dataBytes = (file.length() - WAV_HEADER_SIZE).coerceAtLeast(0)
        return dataBytes.toFloat() / (SAMPLE_RATE * BYTES_PER_SAMPLE)
    }

    fun readMonoFloatPcm16k(file: File): FloatArray {
        RandomAccessFile(file, "r").use { input ->
            val dataBytes = (input.length() - WAV_HEADER_SIZE).toInt()
            if (dataBytes <= 0) return FloatArray(0)

            input.seek(WAV_HEADER_SIZE.toLong())
            val bytes = ByteArray(dataBytes)
            input.readFully(bytes)

            val sampleCount = dataBytes / 2
            val samples = FloatArray(sampleCount)
            for (i in 0 until sampleCount) {
                val low = bytes[i * 2].toInt() and 0xFF
                val high = bytes[i * 2 + 1].toInt()
                val sample = (high shl 8) or low
                samples[i] = sample / 32768.0f
            }
            return samples
        }
    }
}
