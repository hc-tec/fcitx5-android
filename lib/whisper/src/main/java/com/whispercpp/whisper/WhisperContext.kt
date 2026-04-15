package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

private const val LOG_TAG = "LibWhisper"

class WhisperContext private constructor(private var ptr: Long) {
    // whisper.cpp contexts are not thread-safe. Keep all inference on one worker.
    private val scope =
        CoroutineScope(
            Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        )

    suspend fun transcribeData(
        data: FloatArray,
        language: String,
        printTimestamp: Boolean = false
    ): String =
        withContext(scope.coroutineContext) {
            require(ptr != 0L)
            val numThreads = WhisperCpuConfig.preferredThreadCount
            Log.i(LOG_TAG, "Using $numThreads threads for whisper.cpp")
            WhisperLib.fullTranscribe(ptr, numThreads, language, data)
            val textCount = WhisperLib.getTextSegmentCount(ptr)
            buildString {
                for (index in 0 until textCount) {
                    if (printTimestamp) {
                        append("[segment:$index] ")
                    }
                    append(WhisperLib.getTextSegment(ptr, index))
                }
            }.trim()
        }

    suspend fun release() =
        withContext(scope.coroutineContext) {
            if (ptr != 0L) {
                WhisperLib.freeContext(ptr)
                ptr = 0
            }
        }

    protected fun finalize() {
        runBlocking {
            release()
        }
    }

    companion object {
        fun createContextFromFile(
            filePath: String,
            useGpu: Boolean = true
        ): WhisperContext {
            WhisperLib.assertLoaded()
            val ptr = WhisperLib.initContext(filePath, useGpu)
            if (ptr == 0L) {
                throw IllegalStateException("Failed to load whisper model from $filePath")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromAsset(
            assetManager: AssetManager,
            assetPath: String,
            useGpu: Boolean = true
        ): WhisperContext {
            WhisperLib.assertLoaded()
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath, useGpu)
            if (ptr == 0L) {
                throw IllegalStateException("Failed to load whisper model from asset $assetPath")
            }
            return WhisperContext(ptr)
        }

        fun getSystemInfo(): String = WhisperLib.getSystemInfo()
    }
}

private class WhisperLib {
    companion object {
        private var nativeLoadError: Throwable? = null

        init {
            Log.i(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()} sdk=${Build.VERSION.SDK_INT}")
            nativeLoadError =
                runCatching {
                    when {
                        isArmEabiV7a() && cpuInfo()?.contains("vfpv4") == true -> {
                            Log.i(LOG_TAG, "Loading libwhisper_vfpv4.so")
                            System.loadLibrary("whisper_vfpv4")
                        }
                        isArmEabiV8a() && cpuInfo()?.contains("fphp") == true -> {
                            Log.i(LOG_TAG, "Loading libwhisper_v8fp16_va.so")
                            System.loadLibrary("whisper_v8fp16_va")
                        }
                        else -> {
                            Log.i(LOG_TAG, "Loading libwhisper.so")
                            System.loadLibrary("whisper")
                        }
                    }
                }.exceptionOrNull()?.also { error ->
                    Log.e(LOG_TAG, "Failed to load whisper native library", error)
                }
        }

        fun assertLoaded() {
            nativeLoadError?.let { error ->
                throw IllegalStateException(
                    "Failed to load whisper native library. Vulkan acceleration requires a compatible device/driver and Android 7.0+.",
                    error
                )
            }
        }

        external fun initContextFromAsset(
            assetManager: AssetManager,
            assetPath: String,
            useGpu: Boolean
        ): Long

        external fun initContext(
            modelPath: String,
            useGpu: Boolean
        ): Long

        external fun freeContext(contextPtr: Long)

        external fun fullTranscribe(
            contextPtr: Long,
            numThreads: Int,
            language: String,
            audioData: FloatArray
        )

        external fun getTextSegmentCount(contextPtr: Long): Int

        external fun getTextSegment(
            contextPtr: Long,
            index: Int
        ): String

        external fun getSystemInfo(): String
    }
}

private fun isArmEabiV7a(): Boolean = Build.SUPPORTED_ABIS.firstOrNull() == "armeabi-v7a"

private fun isArmEabiV8a(): Boolean = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"

private fun cpuInfo(): String? =
    try {
        File("/proc/cpuinfo").inputStream().bufferedReader().use { it.readText() }
    } catch (exception: Exception) {
        Log.w(LOG_TAG, "Unable to read /proc/cpuinfo", exception)
        null
    }
