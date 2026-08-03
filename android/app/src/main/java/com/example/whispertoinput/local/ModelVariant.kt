package com.example.whispertoinput.local

import com.example.whispertoinput.R

// GGML quantized Whisper models published by the whisper.cpp project. Filenames and the
// resolve/main download path follow whisper.cpp's own models/download-ggml-model.sh (as of
// v1.7.6), so `id` doubles as both the persisted settings value and the source filename suffix.
// Rough estimated speed factor (seconds of audio processed per second, on a mid-range phone CPU)
// is only used as a fallback for the auto-mode heuristic before a device has been benchmarked.
data class ModelVariant(
    val id: String,
    val labelRes: Int,
    val approxSizeMb: Int,
    val estimatedSpeedFactor: Float
) {
    val fileName: String get() = "ggml-$id.bin"
    val downloadUrl: String get() =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"

    companion object {
        val SMALL = ModelVariant("small", R.string.local_model_variant_small, 466, 4.0f)
        val MEDIUM = ModelVariant("medium", R.string.local_model_variant_medium, 1500, 1.5f)
        val LARGE_V3_TURBO_Q5 = ModelVariant(
            "large-v3-turbo-q5_0", R.string.local_model_variant_large_v3_turbo_q5, 547, 2.2f
        )
        val LARGE_V3_Q5 = ModelVariant(
            "large-v3-q5_0", R.string.local_model_variant_large_v3_q5, 1080, 0.6f
        )

        val ALL = listOf(SMALL, MEDIUM, LARGE_V3_TURBO_Q5, LARGE_V3_Q5)
        val DEFAULT = LARGE_V3_TURBO_Q5

        fun byId(id: String): ModelVariant = ALL.find { it.id == id } ?: DEFAULT
    }
}
