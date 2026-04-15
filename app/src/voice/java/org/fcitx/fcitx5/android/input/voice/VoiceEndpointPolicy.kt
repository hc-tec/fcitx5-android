package org.fcitx.fcitx5.android.input.voice

internal object VoiceEndpointPolicy {
    fun decide(
        stats: VoiceActivityStats,
        sampleCount: Int,
        sampleRate: Int = WhisperAudioRecorder.SAMPLE_RATE,
        source: String
    ): VoiceEndpointDecision {
        val durationMs = if (sampleRate > 0) sampleCount * 1000L / sampleRate else 0L
        val micMayBeBlocked =
            !stats.hasSpeech &&
                durationMs >= MIC_BLOCKED_MIN_DURATION_MS &&
                stats.peak <= MIC_BLOCKED_PEAK_THRESHOLD &&
                stats.rms <= MIC_BLOCKED_RMS_THRESHOLD

        val state =
            when {
                micMayBeBlocked -> VoiceListeningState.MIC_MAY_BE_BLOCKED
                !stats.hasSpeech -> VoiceListeningState.NOT_TALKED_YET
                stats.trailingSilenceMs >= ENDING_SOON_VAD_MS -> VoiceListeningState.ENDING_SOON_VAD
                else -> VoiceListeningState.TALKING
            }

        val shouldAutoStop =
            stats.hasSpeech &&
                durationMs >= MIN_AUTOSTOP_AUDIO_MS &&
                stats.trailingSilenceMs >= AUTO_STOP_TRAILING_SILENCE_MS

        return VoiceEndpointDecision(
            state = state,
            hasSpeech = stats.hasSpeech,
            shouldAutoStop = shouldAutoStop,
            trailingSilenceMs = stats.trailingSilenceMs,
            source = source
        )
    }

    private const val MIC_BLOCKED_MIN_DURATION_MS = 2_000L
    private const val MIN_AUTOSTOP_AUDIO_MS = 1_200L
    private const val ENDING_SOON_VAD_MS = 900L
    private const val AUTO_STOP_TRAILING_SILENCE_MS = 1_800L
    private const val MIC_BLOCKED_PEAK_THRESHOLD = 0.0025f
    private const val MIC_BLOCKED_RMS_THRESHOLD = 0.00025f
}
