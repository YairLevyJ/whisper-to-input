// JNI glue between LocalWhisperNative.kt and whisper.cpp's public C API (whisper.h).
// Adapted from whisper.cpp's own Android JNI example (examples/whisper.android, v1.7.6),
// trimmed to what this app needs: load a GGML model file, run a single blocking transcription
// over an already-decoded float PCM buffer, and return the concatenated segment text.

#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define TAG "LocalWhisperNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_whispertoinput_local_LocalWhisperNative_initContext(
    JNIEnv *env, jobject /*thiz*/, jstring model_path_str) {
    const char *model_path_chars = env->GetStringUTFChars(model_path_str, nullptr);
    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *context = whisper_init_from_file_with_params(model_path_chars, cparams);
    env->ReleaseStringUTFChars(model_path_str, model_path_chars);
    if (context == nullptr) {
        LOGE("Failed to load model");
    }
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_example_whispertoinput_local_LocalWhisperNative_freeContext(
    JNIEnv * /*env*/, jobject /*thiz*/, jlong context_ptr) {
    auto *context = (struct whisper_context *) context_ptr;
    if (context != nullptr) {
        whisper_free(context);
    }
}

// Runs transcription synchronously; the caller (LocalWhisperEngine) dispatches this off the
// main thread. Returns 0 on success, matching whisper_full's own convention.
JNIEXPORT jint JNICALL
Java_com_example_whispertoinput_local_LocalWhisperNative_fullTranscribe(
    JNIEnv *env, jobject /*thiz*/, jlong context_ptr, jint num_threads,
    jfloatArray audio_data, jstring language_str, jstring initial_prompt_str) {
    auto *context = (struct whisper_context *) context_ptr;
    if (context == nullptr) {
        return -1;
    }

    jfloat *audio_data_arr = env->GetFloatArrayElements(audio_data, nullptr);
    const jsize audio_data_length = env->GetArrayLength(audio_data);

    const char *language_chars = env->GetStringUTFChars(language_str, nullptr);
    const char *initial_prompt_chars = env->GetStringUTFChars(initial_prompt_str, nullptr);
    const bool has_language = language_chars != nullptr && language_chars[0] != '\0';
    const bool has_prompt = initial_prompt_chars != nullptr && initial_prompt_chars[0] != '\0';

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.no_timestamps = true;
    params.single_segment = false;
    params.no_context = true;
    params.n_threads = num_threads;
    params.offset_ms = 0;
    // whisper_full's default (temperature_inc = 0.2) re-runs the decoder up to 6 times at rising
    // temperature whenever its own quality heuristics (entropy/logprob/no-speech thresholds)
    // aren't satisfied - each retry re-decodes the whole segment, so a run that keeps failing the
    // heuristic can take several times as long as a clean one. That fallback exists to rescue
    // genuinely ambiguous audio, but for interactive dictation - where there's already a manual
    // Retry button and slow beats "occasionally not the best possible transcript" - a single
    // greedy pass is the better trade. temperature_inc <= 0 makes whisper_full skip the loop and
    // decode once at params.temperature (0.0) instead of building the [0.0 .. 1.0] ladder.
    params.temperature_inc = 0.0f;
    // Empty language string requests auto-detection; whisper.cpp expects a null pointer for that.
    params.language = has_language ? language_chars : nullptr;
    params.detect_language = !has_language;
    if (has_prompt) {
        params.initial_prompt = initial_prompt_chars;
    }

    LOGI("Starting local transcription: %d samples, %d threads", audio_data_length, num_threads);
    const int result = whisper_full(context, params, audio_data_arr, audio_data_length);
    if (result != 0) {
        LOGE("whisper_full failed with code %d", result);
    }

    env->ReleaseFloatArrayElements(audio_data, audio_data_arr, JNI_ABORT);
    env->ReleaseStringUTFChars(language_str, language_chars);
    env->ReleaseStringUTFChars(initial_prompt_str, initial_prompt_chars);

    return result;
}

// Concatenates every segment produced by the last fullTranscribe() call into one string.
JNIEXPORT jstring JNICALL
Java_com_example_whispertoinput_local_LocalWhisperNative_getFullText(
    JNIEnv *env, jobject /*thiz*/, jlong context_ptr) {
    auto *context = (struct whisper_context *) context_ptr;
    if (context == nullptr) {
        return env->NewStringUTF("");
    }

    std::string full_text;
    const int n_segments = whisper_full_n_segments(context);
    for (int i = 0; i < n_segments; i++) {
        const char *segment_text = whisper_full_get_segment_text(context, i);
        if (segment_text != nullptr) {
            full_text += segment_text;
        }
    }

    return env->NewStringUTF(full_text.c_str());
}

} // extern "C"
