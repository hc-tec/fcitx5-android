package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log

internal class WhisperCppVoiceEngine(
    private val context: Context
) : VoiceEngine {
    override val engineId: String = "whisper.cpp"

    override val capabilities =
        VoiceEngineCapabilities(
            supportsStreamingPartial = true,
            partialInitialDelayMs = 300,
            partialPollIntervalMs = 180,
            partialMinSamples = WhisperAudioRecorder.SAMPLE_RATE / 3,
            partialStepSamples = WhisperAudioRecorder.SAMPLE_RATE / 8,
            partialMaxSamples = WhisperAudioRecorder.SAMPLE_RATE * 6 / 5,
            partialAudioMode = VoicePartialAudioMode.RecentWindow
        )

    override suspend fun warmup(force: Boolean): VoiceEngineWarmupResult {
        val state = WhisperModelManager.awaitReady(context, force)
        Log.i(LOG_TAG, "warmup force=$force state=${state::class.simpleName}")
        return when (state) {
            is WhisperModelManager.State.Ready ->
                VoiceEngineWarmupResult(
                    ready = true,
                    modelId = state.model.modelId
                )
            is WhisperModelManager.State.Error ->
                VoiceEngineWarmupResult(
                    ready = false,
                    modelMissing = state.modelMissing,
                    message = state.message
                )
            else ->
                VoiceEngineWarmupResult(
                    ready = false,
                    message = "whisper.cpp engine is unavailable"
                )
        }
    }

    override suspend fun transcribePartial(
        audioData: FloatArray,
        localeTag: String
    ): VoiceEngineResult = transcribe(audioData, localeTag, partial = true)

    override suspend fun transcribeFinal(
        audioData: FloatArray,
        localeTag: String
    ): VoiceEngineResult = transcribe(audioData, localeTag, partial = false)

    private suspend fun transcribe(
        audioData: FloatArray,
        localeTag: String,
        partial: Boolean
    ): VoiceEngineResult {
        val state = WhisperModelManager.awaitReady(context)
        require(state is WhisperModelManager.State.Ready) {
            (state as? WhisperModelManager.State.Error)?.message ?: "whisper.cpp engine is unavailable"
        }
        val readyState = state as WhisperModelManager.State.Ready
        var activeState = readyState

        val preparedAudio =
            if (partial && audioData.size > capabilities.partialMaxSamples) {
                audioData.copyOfRange(audioData.size - capabilities.partialMaxSamples, audioData.size)
            } else {
                audioData
        }

        val start = SystemClock.elapsedRealtime()
        val text =
            runCatching {
                readyState.whisperContext.transcribeData(
                    preparedAudio,
                    WhisperLanguageResolver.resolve(localeTag)
                )
            }.recoverCatching { error ->
                if (shouldRetryOnCpu(error)) {
                    Log.w(LOG_TAG, "GPU inference failed, retrying on CPU", error)
                    WhisperModelManager.noteGpuInferenceFailure(error.message.orEmpty())
                    val retryState = WhisperModelManager.awaitReady(context, force = true)
                    require(retryState is WhisperModelManager.State.Ready) {
                        (retryState as? WhisperModelManager.State.Error)?.message ?: "whisper.cpp CPU fallback is unavailable"
                    }
                    val readyRetryState = retryState as WhisperModelManager.State.Ready
                    activeState = readyRetryState
                    readyRetryState.whisperContext.transcribeData(
                        preparedAudio,
                        WhisperLanguageResolver.resolve(localeTag)
                    )
                } else {
                    throw error
                }
            }.getOrThrow()
        val latencyMs = SystemClock.elapsedRealtime() - start
        Log.i(
            LOG_TAG,
            "engine=$engineId backend=${activeState.backend} partial=$partial model=${activeState.model.modelId} samples=${preparedAudio.size} latencyMs=$latencyMs"
        )
        return VoiceEngineResult(
            text = text,
            latencyMs = latencyMs,
            sampleCount = preparedAudio.size,
            modelId = activeState.model.modelId
        )
    }

    private companion object {
        private const val LOG_TAG = "WhisperCppEngine"

        private fun shouldRetryOnCpu(error: Throwable): Boolean {
            val message = error.message.orEmpty()
            return message.contains("ErrorDeviceLost", ignoreCase = true) ||
                message.contains("DeviceLost", ignoreCase = true) ||
                message.contains("vk::Queue::submit", ignoreCase = true)
        }
    }
}
