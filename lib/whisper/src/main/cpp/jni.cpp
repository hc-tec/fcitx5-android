#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <cstring>
#include <exception>
#include <string>
#include "whisper.h"

#define UNUSED(x) (void)(x)
#define TAG "WhisperJni"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void android_whisper_log_callback(enum ggml_log_level level, const char *text, void *user_data) {
    UNUSED(user_data);

    const int priority = level >= GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR :
                         level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN :
                         ANDROID_LOG_INFO;

    if (text == nullptr || text[0] == '\0') {
        return;
    }

    __android_log_write(priority, TAG, text);
}

static struct whisper_context_params create_context_params(bool use_gpu) {
    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = use_gpu;
    params.gpu_device = 0;

    whisper_log_set(android_whisper_log_callback, nullptr);
    LOGI("whisper.cpp context params prepared: use_gpu=%d gpu_device=%d", params.use_gpu, params.gpu_device);
    LOGI("whisper.cpp system info: %s", whisper_print_system_info());

    return params;
}

static struct whisper_context *try_init_with_loader(whisper_model_loader *loader, bool use_gpu) {
    struct whisper_context_params params = create_context_params(use_gpu);
    try {
        return whisper_init_with_params(loader, params);
    } catch (const std::exception &error) {
        LOGW("whisper_init_with_params failed (use_gpu=%d): %s", use_gpu ? 1 : 0, error.what());
        return nullptr;
    } catch (...) {
        LOGW("whisper_init_with_params failed (use_gpu=%d): unknown exception", use_gpu ? 1 : 0);
        return nullptr;
    }
}

static struct whisper_context *try_init_from_file(const char *model_path, bool use_gpu) {
    struct whisper_context_params params = create_context_params(use_gpu);
    try {
        return whisper_init_from_file_with_params(model_path, params);
    } catch (const std::exception &error) {
        LOGW("whisper_init_from_file_with_params failed (use_gpu=%d): %s", use_gpu ? 1 : 0, error.what());
        return nullptr;
    } catch (...) {
        LOGW("whisper_init_from_file_with_params failed (use_gpu=%d): unknown exception", use_gpu ? 1 : 0);
        return nullptr;
    }
}

static struct whisper_context *init_from_file_fallback(const char *model_path) {
    struct whisper_context *context = try_init_from_file(model_path, true);
    if (context != nullptr) {
        return context;
    }

    LOGW("Falling back to CPU whisper file initialization");
    return try_init_from_file(model_path, false);
}

static size_t asset_read(void *ctx, void *output, size_t read_size) {
    return static_cast<size_t>(AAsset_read(reinterpret_cast<AAsset *>(ctx), output, read_size));
}

static bool asset_is_eof(void *ctx) {
    return AAsset_getRemainingLength64(reinterpret_cast<AAsset *>(ctx)) <= 0;
}

static void asset_close(void *ctx) {
    AAsset_close(reinterpret_cast<AAsset *>(ctx));
}

static bool should_detect_language(const char *language) {
    return language == nullptr || language[0] == '\0' || strcmp(language, "auto") == 0;
}

static void throw_illegal_state_exception(JNIEnv *env, const std::string &message) {
    jclass exception_class = env->FindClass("java/lang/IllegalStateException");
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message.c_str());
    }
}

static struct whisper_context *try_init_from_asset(
        JNIEnv *env,
        jobject asset_manager,
        const char *asset_path,
        bool use_gpu
) {
    AAssetManager *manager = AAssetManager_fromJava(env, asset_manager);
    if (manager == nullptr) {
        LOGE("Failed to resolve Android asset manager for '%s'", asset_path);
        return nullptr;
    }

    AAsset *asset = AAssetManager_open(manager, asset_path, AASSET_MODE_STREAMING);
    if (asset == nullptr) {
        LOGW("Failed to open model asset '%s'", asset_path);
        return nullptr;
    }

    whisper_model_loader loader{};
    loader.context = asset;
    loader.read = &asset_read;
    loader.eof = &asset_is_eof;
    loader.close = &asset_close;

    return try_init_with_loader(&loader, use_gpu);
}

static struct whisper_context *whisper_init_from_asset(
        JNIEnv *env,
        jobject asset_manager,
        const char *asset_path,
        bool prefer_gpu
) {
    struct whisper_context *context = try_init_from_asset(env, asset_manager, asset_path, prefer_gpu);
    if (context != nullptr) {
        return context;
    }

    if (!prefer_gpu) {
        LOGE("Failed to initialize whisper context from asset '%s'", asset_path);
        return nullptr;
    }

    LOGW("Falling back to CPU whisper asset initialization");
    context = try_init_from_asset(env, asset_manager, asset_path, false);
    if (context == nullptr) {
        LOGE("Failed to initialize whisper context from asset '%s'", asset_path);
    }
    return context;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContextFromAsset(
        JNIEnv *env,
        jobject thiz,
        jobject asset_manager,
        jstring asset_path_str,
        jboolean use_gpu) {
    UNUSED(thiz);
    const char *asset_path = env->GetStringUTFChars(asset_path_str, nullptr);
    struct whisper_context *context = whisper_init_from_asset(env, asset_manager, asset_path, use_gpu == JNI_TRUE);
    env->ReleaseStringUTFChars(asset_path_str, asset_path);
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContext(
        JNIEnv *env,
        jobject thiz,
        jstring model_path_str,
        jboolean use_gpu) {
    UNUSED(thiz);
    const char *model_path = env->GetStringUTFChars(model_path_str, nullptr);
    struct whisper_context *context =
            use_gpu == JNI_TRUE ? init_from_file_fallback(model_path) : try_init_from_file(model_path, false);
    if (context == nullptr) {
        LOGE("Failed to initialize whisper context from file '%s'", model_path);
    }
    env->ReleaseStringUTFChars(model_path_str, model_path);
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_freeContext(
        JNIEnv *env,
        jobject thiz,
        jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    whisper_free(reinterpret_cast<struct whisper_context *>(context_ptr));
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env,
        jobject thiz,
        jlong context_ptr,
        jint num_threads,
        jstring language_str,
        jfloatArray audio_data) {
    UNUSED(thiz);

    struct whisper_context *context = reinterpret_cast<struct whisper_context *>(context_ptr);
    const char *language = env->GetStringUTFChars(language_str, nullptr);
    jfloat *audio_data_arr = env->GetFloatArrayElements(audio_data, nullptr);
    const jsize audio_data_length = env->GetArrayLength(audio_data);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.no_timestamps = true;
    params.language = should_detect_language(language) ? nullptr : language;
    params.detect_language = should_detect_language(language);
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;
    params.temperature = 0.0f;
    params.max_initial_ts = 1.0f;

    std::string inference_error;
    whisper_reset_timings(context);
    try {
        if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
            LOGW("Failed to run whisper_full");
        } else {
            whisper_print_timings(context);
        }
    } catch (const std::exception &error) {
        inference_error = error.what();
        LOGE("whisper_full threw exception: %s", inference_error.c_str());
    } catch (...) {
        inference_error = "whisper_full failed with unknown native exception";
        LOGE("%s", inference_error.c_str());
    }

    env->ReleaseFloatArrayElements(audio_data, audio_data_arr, JNI_ABORT);
    env->ReleaseStringUTFChars(language_str, language);
    if (!inference_error.empty()) {
        throw_illegal_state_exception(env, inference_error);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv *env,
        jobject thiz,
        jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    return whisper_full_n_segments(reinterpret_cast<struct whisper_context *>(context_ptr));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegment(
        JNIEnv *env,
        jobject thiz,
        jlong context_ptr,
        jint index) {
    UNUSED(thiz);
    const char *text = whisper_full_get_segment_text(reinterpret_cast<struct whisper_context *>(context_ptr), index);
    return env->NewStringUTF(text);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv *env,
        jobject thiz) {
    UNUSED(thiz);
    const char *system_info = whisper_print_system_info();
    return env->NewStringUTF(system_info);
}
