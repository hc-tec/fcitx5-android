package org.fcitx.fcitx5.android.input.voice

internal enum class VoiceListeningState {
    NOT_TALKED_YET,
    MIC_MAY_BE_BLOCKED,
    TALKING,
    ENDING_SOON_VAD
}

internal data class VoiceEndpointDecision(
    val state: VoiceListeningState,
    val hasSpeech: Boolean,
    val shouldAutoStop: Boolean,
    val trailingSilenceMs: Long,
    val source: String
)
