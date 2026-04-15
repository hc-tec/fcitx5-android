package org.fcitx.fcitx5.android.input.voice

sealed class VoiceInputUiState(
    val phase: Phase
) {
    data object Idle : VoiceInputUiState(Phase.Idle)

    data class Listening(
        val transcript: String = "",
        val listeningState: VoiceListeningState = VoiceListeningState.NOT_TALKED_YET
    ) : VoiceInputUiState(Phase.Listening)

    data class Processing(
        val transcript: String = ""
    ) : VoiceInputUiState(Phase.Processing)

    val isIdle: Boolean
        get() = phase == Phase.Idle

    enum class Phase {
        Idle,
        Listening,
        Processing
    }
}
