#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <string.h>
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

    if (text == NULL || text[0] == '\0') {
        return;
    }

    __android_log_write(priority, TAG, text);
}

static struct whisper_context_params create_context_params(void) {
    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = true;
    params.gpu_device = 0;

    whisper_log_set(android_whisper_log_callback, NULL);
    LOGI("whisper.cpp context params prepared: use_gpu=%d gpu_device=%d", params.use_gpu, params.gpu_device);
    LOGI("whisper.cpp system info: %s", whisper_print_system_info());

    return params;
}

static size_t asset_read(void *ctx, void *output, size_t read_size) {
    return AAsset_read((AAsset *) ctx, output, read_size);
}

static bool asset_is_eof(void *ctx) {
    return AAsset_getRemainingLength64((AAsset *) ctx) <= 0;
}

static void asset_close(void *ctx) {
    AAsset_close((AAsset *) ctx);
}

static bool should_detect_language(const char *language) {
    return language == NULL || language[0] == '\0' || strcmp(language, "auto") == 0;
}

static struct whisper_context *whisper_init_from_asset(
        JNIEnv *env,
        jobject asset_manager,
        const char *asset_path
) {
    AAssetManager *manager = AAssetManager_fromJava(env, asset_manager);
    AAsset *asset = AAssetManager_open(manager, asset_path, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGW("Failed to open model asset '%s'", asset_path);
        return NULL;
    }

    whisper_model_loader loader = {
            .context = asset,
            .read = &asset_read,
            .eof = &asset_is_eof,
            .close = &asset_close
    };

    struct whisper_context_params params = create_context_params();
    struct whisper_context *context = whisper_init_with_params(&loader, params);
    if (!context) {
        LOGE("Failed to initialize whisper context from asset '%s'", asset_path);
    }
    return context;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContextFromAsset(
        JNIEnv *env,
        jobject thiz,
        jobject asset_manager,
        jstring asset_path_str) {
    UNUSED(thiz);
    const char *asset_path = (*env)->GetStringUTFChars(env, asset_path_str, NULL);
    struct whisper_context *context = whisper_init_from_asset(env, asset_manager, asset_path);
    (*env)->ReleaseStringUTFChars(env, asset_path_str, asset_path);
    return (jlong) context;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContext(
        JNIEnv *env,
        jobject thiz,
        jstring model_path_str) {
    UNUSED(thiz);
    const char *model_path = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    struct whisper_context_params params = create_context_params();
    struct whisper_context *context =
            whisper_init_from_file_with_params(model_path, params);
    if (!context) {
        LOGE("Failed to initialize whisper context from file '%s'", model_path);
    }
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_freeContext(
        JNIEnv *env,
        jobject thiz,
        jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    whisper_free((struct whisper_context *) context_ptr);
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env,
        jobject thiz,
        jlong context_ptr,
        jint num_threads,
        jstring language_str,
        jfloatArray audio_data) {
    UNUSED(thiz);

    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *language = (*env)->GetStringUTFChars(env, language_str, NULL);
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.no_timestamps = true;
    params.language = should_detect_language(language) ? NULL : language;
    params.detect_language = should_detect_language(language);
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;
    params.temperature = 0.0f;
    params.max_initial_ts = 1.0f;

    whisper_reset_timings(context);
    if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
        LOGW("Failed to run whisper_full");
    } else {
        whisper_print_timings(context);
    }

    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, language_str, language);
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv *env,
        jobject thiz,
        jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    return whisper_full_n_segments((struct whisper_context *) context_ptr);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegment(
        JNIEnv *env,
        jobject thiz,
        jlong context_ptr,
        jint index) {
    UNUSED(thiz);
    const char *text = whisper_full_get_segment_text((struct whisper_context *) context_ptr, index);
    return (*env)->NewStringUTF(env, text);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv *env,
        jobject thiz) {
    UNUSED(thiz);
    const char *system_info = whisper_print_system_info();
    return (*env)->NewStringUTF(env, system_info);
}
