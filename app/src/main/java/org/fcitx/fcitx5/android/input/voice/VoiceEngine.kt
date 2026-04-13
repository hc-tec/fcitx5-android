package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.voice.core.VoiceSessionRequest

internal enum class VoicePartialAudioMode {
    RecentWindow,
    FullSession,
    IncrementalSession
}

internal data class VoiceEngineCapabilities(
    val supportsStreamingPartial: Boolean,
    val partialInitialDelayMs: Long,
    val partialPollIntervalMs: Long,
    val partialMinSamples: Int,
    val partialStepSamples: Int,
    val partialMaxSamples: Int,
    val partialAudioMode: VoicePartialAudioMode = VoicePartialAudioMode.RecentWindow
)

internal data class VoiceEngineResult(
    val text: String,
    val latencyMs: Long,
    val sampleCount: Int,
    val modelId: String
)

internal data class VoiceEngineWarmupResult(
    val ready: Boolean,
    val modelMissing: Boolean = false,
    val message: String? = null,
    val modelId: String? = null
)

internal interface VoiceEngine {
    val engineId: String
    val capabilities: VoiceEngineCapabilities

    suspend fun warmup(force: Boolean = false): VoiceEngineWarmupResult

    fun beginSession(request: VoiceSessionRequest) {}

    fun endSession(cancelled: Boolean) {}

    suspend fun transcribePartial(
        audioData: FloatArray,
        localeTag: String
    ): VoiceEngineResult

    suspend fun transcribeFinal(
        audioData: FloatArray,
        localeTag: String
    ): VoiceEngineResult
}
