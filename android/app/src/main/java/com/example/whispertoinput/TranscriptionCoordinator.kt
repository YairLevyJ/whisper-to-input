package com.example.whispertoinput

import android.content.Context
import com.example.whispertoinput.local.CpuInfo
import com.example.whispertoinput.local.LocalWhisperEngine
import com.example.whispertoinput.local.ModelManager
import com.example.whispertoinput.local.ModelVariant
import com.example.whispertoinput.local.NetworkEstimator
import com.example.whispertoinput.local.WavAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Picks between the network backend ([WhisperTranscriber]) and the on-device model
 * ([LocalWhisperEngine]) according to the "Transcription Mode" setting, and is the only thing
 * [WhisperInputService] talks to for transcription - the interface mirrors [WhisperTranscriber]'s
 * own startAsync/stop so swapping it in is a one-line change there.
 *
 * "Auto" mode does not literally race both backends: that would double network/CPU/battery cost
 * for every recording, which defeats the point of an automatic, low-effort choice. Instead it
 * estimates which one is likely faster from figures already available (link bandwidth estimate,
 * audio size/duration, a rough per-model speed factor) and falls back to the other backend if the
 * chosen one fails or the network turns out to be unusable.
 */
class TranscriptionCoordinator {
    private val apiTranscriber = WhisperTranscriber()
    private var currentJob: Job? = null

    fun startAsync(
        context: Context,
        filename: String,
        mediaType: String,
        attachToEnd: String,
        callback: (String?) -> Unit,
        exceptionCallback: (String) -> Unit,
        noticeCallback: (String) -> Unit
    ) {
        currentJob?.cancel()
        currentJob = CoroutineScope(Dispatchers.Main).launch {
            val (text, exceptionMessage) = try {
                Pair(runTranscription(context, filename, mediaType, attachToEnd, noticeCallback), null)
            } catch (e: CancellationException) {
                Pair(null, null)
            } catch (e: Exception) {
                Pair(null, e.message)
            }

            callback(text)
            if (!exceptionMessage.isNullOrEmpty()) {
                exceptionCallback(exceptionMessage)
            }
        }
    }

    fun stop() {
        currentJob?.cancel()
        currentJob = null
        apiTranscriber.stop()
    }

    private suspend fun runTranscription(
        context: Context,
        filename: String,
        mediaType: String,
        attachToEnd: String,
        noticeCallback: (String) -> Unit
    ): String {
        val preferences = context.dataStore.data.first()
        val mode = preferences[TRANSCRIPTION_MODE] ?: TRANSCRIPTION_MODE_API
        val variant = ModelVariant.byId(preferences[LOCAL_MODEL_VARIANT] ?: ModelVariant.DEFAULT.id)
        val languageCode = preferences[LANGUAGE_CODE] ?: ""
        val prompt = preferences[PROMPT] ?: ""
        val addTrailingSpace = preferences[ADD_TRAILING_SPACE] ?: false

        val modelManager = ModelManager(context)
        val localReady = modelManager.isDownloaded(variant)
        val threads = CpuInfo.recommendedThreadCount
        val audioFile = File(filename)

        suspend fun local(): String = applySuffix(
            LocalWhisperEngine.transcribe(
                context,
                audioFile,
                modelManager.modelFile(variant).absolutePath,
                languageCode,
                prompt,
                threads
            ),
            attachToEnd,
            addTrailingSpace
        )

        suspend fun api(): String = runApiTranscription(context, filename, mediaType, attachToEnd)

        return when (mode) {
            TRANSCRIPTION_MODE_LOCAL -> local()
            TRANSCRIPTION_MODE_AUTO -> {
                val networkUsable = NetworkEstimator.isNetworkUsable(context)
                val preferLocal = choosePreferredBackend(
                    context, audioFile, variant, localReady, networkUsable
                )

                if (preferLocal) {
                    try {
                        local()
                    } catch (e: Exception) {
                        if (networkUsable) {
                            noticeCallback(context.getString(R.string.notice_fell_back_to_api))
                            api()
                        } else {
                            throw e
                        }
                    }
                } else {
                    try {
                        api()
                    } catch (e: Exception) {
                        if (localReady) {
                            noticeCallback(context.getString(R.string.notice_fell_back_to_local))
                            local()
                        } else {
                            throw e
                        }
                    }
                }
            }
            else -> api()
        }
    }

    private fun choosePreferredBackend(
        context: Context,
        audioFile: File,
        variant: ModelVariant,
        localReady: Boolean,
        networkUsable: Boolean
    ): Boolean {
        if (!localReady) return false
        if (!networkUsable) return true

        val audioDurationSeconds = WavAudio.durationSeconds(audioFile)
        val estimatedLocalSeconds = NetworkEstimator.estimateLocalSeconds(audioDurationSeconds, variant)
        val estimatedApiSeconds = NetworkEstimator.estimateApiSeconds(context, audioFile.length())
            ?: return false // Unknown bandwidth: keep the current default (network) rather than guess.

        return estimatedLocalSeconds < estimatedApiSeconds
    }

    private fun applySuffix(text: String, attachToEnd: String, addTrailingSpace: Boolean): String =
        if (attachToEnd.isEmpty()) text + (if (addTrailingSpace) " " else "") else text + attachToEnd

    // Adapts WhisperTranscriber's callback-based startAsync() into a suspend call, so "auto" mode
    // can catch a network failure and fall back to the local model.
    //
    // WhisperTranscriber always invokes `callback` first (with null on both failure and
    // cancellation) and only then, in the same synchronous frame, conditionally invokes
    // `exceptionCallback`. The success resume is posted one dispatcher turn later so that an
    // exceptionCallback firing right after in that same frame gets to resumeWithException first;
    // by the time the posted resume runs, the continuation is already completed and it's a no-op.
    private suspend fun runApiTranscription(
        context: Context,
        filename: String,
        mediaType: String,
        attachToEnd: String
    ): String = suspendCancellableCoroutine { cont ->
        apiTranscriber.startAsync(
            context,
            filename,
            mediaType,
            attachToEnd,
            callback = { result ->
                CoroutineScope(Dispatchers.Main).launch {
                    if (cont.isActive) cont.resume(result ?: "")
                }
            },
            exceptionCallback = { message ->
                if (cont.isActive) cont.resumeWithException(Exception(message))
            }
        )
        cont.invokeOnCancellation { apiTranscriber.stop() }
    }
}
