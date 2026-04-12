package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class VoiceInputMode(
    override val stringRes: Int
) : ManagedPreferenceEnum {
    BuiltInSpeechRecognizer(R.string.voice_input_mode_builtin),
    SystemVoiceIme(R.string.voice_input_mode_system_ime)
}
