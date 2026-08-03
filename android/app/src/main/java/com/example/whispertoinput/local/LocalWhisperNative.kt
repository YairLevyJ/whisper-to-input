package com.example.whispertoinput.local

import android.util.Log

// Thin JNI bridge to local_whisper_jni.cpp / whisper.cpp. Declared as a plain object (not a
// companion object) so the generated JNI symbols are unmangled: Java_..._LocalWhisperNative_foo.
object LocalWhisperNative {
    private const val TAG = "LocalWhisperNative"
    // The library is built for an ARM baseline above the ABI minimum, so a chip without those
    // instructions must never load it - see CpuFeatures.
    val isAvailable: Boolean = if (!CpuFeatures.supportsLocalInference) {
        Log.w(TAG, "CPU does not support the required instruction set; local inference disabled")
        false
    } else try {
        System.loadLibrary("local_whisper")
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "Native local-whisper library unavailable: ${e.message}")
        false
    }

    external fun initContext(modelPath: String): Long
    external fun freeContext(contextPtr: Long)

    // Returns 0 on success, matching whisper_full()'s own convention.
    external fun fullTranscribe(
        contextPtr: Long,
        numThreads: Int,
        audioData: FloatArray,
        language: String,
        initialPrompt: String
    ): Int

    external fun getFullText(contextPtr: Long): String
}
