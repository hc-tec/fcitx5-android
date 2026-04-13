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
            partialInitialDelayMs = 450,
            partialPollIntervalMs = 250,
            partialMinSamples = WhisperAudioRecorder.SAMPLE_RATE / 2,
            partialStepSamples = WhisperAudioRecorder.SAMPLE_RATE / 5,
            partialMaxSamples = WhisperAudioRecorder.SAMPLE_RATE * 2
        )

    override suspend fun warmup(force: Boolean) {
        val state = WhisperModelManager.awaitReady(context, force)
        Log.i(LOG_TAG, "warmup force=$force state=${state::class.simpleName}")
        require(state is WhisperModelManager.State.Ready) {
            (state as? WhisperModelManager.State.Error)?.message ?: "whisper.cpp engine is unavailable"
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

        val preparedAudio =
            if (partial && audioData.size > capabilities.partialMaxSamples) {
                audioData.copyOfRange(audioData.size - capabilities.partialMaxSamples, audioData.size)
            } else {
                audioData
        }

        val start = SystemClock.elapsedRealtime()
        val text =
            runCatching {
                state.whisperContext.transcribeData(
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
                    retryState.whisperContext.transcribeData(
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
            "engine=$engineId partial=$partial model=${state.model.modelId} samples=${preparedAudio.size} latencyMs=$latencyMs"
        )
        return VoiceEngineResult(
            text = text,
            latencyMs = latencyMs,
            sampleCount = preparedAudio.size,
            modelId = state.model.modelId
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
