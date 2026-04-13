package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class WhisperCppVoiceEngine(
    private val context: Context
) : VoiceEngine {
    override val engineId: String = "whisper.cpp"

    override val capabilities =
        VoiceEngineCapabilities(
            supportsStreamingPartial = true,
            partialInitialDelayMs = 900,
            partialPollIntervalMs = 350,
            partialMinSamples = WhisperAudioRecorder.SAMPLE_RATE,
            partialStepSamples = WhisperAudioRecorder.SAMPLE_RATE / 2,
            partialMaxSamples = WhisperAudioRecorder.SAMPLE_RATE * 3
        )

    override suspend fun warmup(force: Boolean) {
        val state = WhisperModelManager.awaitReady(context, force)
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
            state.whisperContext.transcribeData(
                preparedAudio,
                WhisperLanguageResolver.resolve(localeTag)
            )
        val latencyMs = SystemClock.elapsedRealtime() - start
        Log.d(
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
    }
}
