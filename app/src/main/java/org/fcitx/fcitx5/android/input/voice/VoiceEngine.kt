package org.fcitx.fcitx5.android.input.voice

internal data class VoiceEngineCapabilities(
    val supportsStreamingPartial: Boolean,
    val partialInitialDelayMs: Long,
    val partialPollIntervalMs: Long,
    val partialMinSamples: Int,
    val partialStepSamples: Int,
    val partialMaxSamples: Int
)

internal data class VoiceEngineResult(
    val text: String,
    val latencyMs: Long,
    val sampleCount: Int,
    val modelId: String
)

internal interface VoiceEngine {
    val engineId: String
    val capabilities: VoiceEngineCapabilities

    suspend fun warmup(force: Boolean = false)

    suspend fun transcribePartial(
        audioData: FloatArray,
        localeTag: String
    ): VoiceEngineResult

    suspend fun transcribeFinal(
        audioData: FloatArray,
        localeTag: String
    ): VoiceEngineResult
}
