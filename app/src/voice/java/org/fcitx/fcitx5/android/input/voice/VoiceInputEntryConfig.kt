package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior

internal data class VoiceInputEntryConfig(
    val showVoiceInputButton: Boolean,
    val spaceLongPressBehavior: SpaceLongPressBehavior,
    val voiceInputMode: VoiceInputMode
) {
    fun hasEnabledEntry(): Boolean =
        showVoiceInputButton || spaceLongPressBehavior == SpaceLongPressBehavior.VoiceInput

    fun shouldPrewarmBuiltInEngine(): Boolean =
        hasEnabledEntry() && voiceInputMode == VoiceInputMode.BuiltInSpeechRecognizer
}
