package com.example.whispertoinput.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.coroutines.coroutineContext

// Downloads and manages GGML model files for the local transcription backend. Models are not
// bundled with the app: the user picks a variant in settings and it is fetched on demand into
// the app's private storage, so the APK stays small and the user only pays for what they use.
class ModelManager(private val context: Context) {
    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    fun modelFile(variant: ModelVariant): File = File(modelsDir, variant.fileName)

    fun isDownloaded(variant: ModelVariant): Boolean = modelFile(variant).exists()

    fun downloadedSizeBytes(variant: ModelVariant): Long =
        modelFile(variant).let { if (it.exists()) it.length() else 0L }

    fun delete(variant: ModelVariant) {
        modelFile(variant).delete()
    }

    // Reports progress as (bytesRead, totalBytes); totalBytes is -1 if the server didn't send a
    // Content-Length. Safe to cancel: the coroutine's cancellation removes the partial file.
    suspend fun download(variant: ModelVariant, onProgress: suspend (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            val partFile = File(modelsDir, "${variant.fileName}.part")
            val client = OkHttpClient()
            val request = Request.Builder().url(variant.downloadUrl).build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("HTTP ${response.code} while downloading ${variant.fileName}")
                    }
                    val body = response.body ?: throw Exception("Empty response body")
                    val totalBytes = body.contentLength()

                    partFile.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var readTotal = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                out.write(buffer, 0, read)
                                readTotal += read
                                onProgress(readTotal, totalBytes)
                            }
                        }
                    }
                }

                val finalFile = modelFile(variant)
                finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    throw Exception("Failed to finalize downloaded model file")
                }
            } catch (e: Exception) {
                partFile.delete()
                throw e
            }
        }
    }
}
