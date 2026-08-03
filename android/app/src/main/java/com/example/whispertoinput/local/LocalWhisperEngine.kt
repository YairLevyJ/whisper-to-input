package com.example.whispertoinput.local

import android.content.Context
import com.example.whispertoinput.R
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

// Runs local transcription on a dedicated single thread, since a whisper_context is not safe to
// use from multiple threads concurrently and reloading the model on every call would be far too
// slow. The loaded context is kept around across calls and only swapped when the selected model
// variant changes.
object LocalWhisperEngine {
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "local-whisper")
    }.asCoroutineDispatcher()

    private var loadedModelPath: String? = null
    private var contextPtr: Long = 0L

    suspend fun transcribe(
        context: Context,
        audioFile: File,
        modelPath: String,
        languageCode: String,
        prompt: String,
        numThreads: Int
    ): String = withContext(dispatcher) {
        if (!LocalWhisperNative.isAvailable) {
            throw Exception(context.getString(R.string.error_local_native_unavailable))
        }
        ensureLoaded(context, modelPath)

        val samples = WavAudio.readMonoFloatPcm16k(audioFile)
        if (samples.isEmpty()) {
            throw Exception(context.getString(R.string.error_local_transcription_failed))
        }

        val resultCode = LocalWhisperNative.fullTranscribe(
            contextPtr, numThreads, samples, languageCode, prompt
        )
        if (resultCode != 0) {
            throw Exception(context.getString(R.string.error_local_transcription_failed))
        }

        LocalWhisperNative.getFullText(contextPtr).trim()
    }

    // Frees the loaded model, e.g. after the user deletes it from settings.
    suspend fun unload() = withContext(dispatcher) {
        unloadLocked()
    }

    private fun ensureLoaded(context: Context, modelPath: String) {
        if (loadedModelPath == modelPath && contextPtr != 0L) return
        unloadLocked()

        if (!File(modelPath).exists()) {
            throw Exception(context.getString(R.string.error_local_model_not_downloaded))
        }

        val ptr = LocalWhisperNative.initContext(modelPath)
        if (ptr == 0L) {
            throw Exception(context.getString(R.string.error_local_model_load_failed))
        }
        contextPtr = ptr
        loadedModelPath = modelPath
    }

    private fun unloadLocked() {
        if (contextPtr != 0L) {
            LocalWhisperNative.freeContext(contextPtr)
            contextPtr = 0L
            loadedModelPath = null
        }
    }
}
