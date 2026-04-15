package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class BuiltInVoiceEngine(
    override val stringRes: Int
) : ManagedPreferenceEnum {
    SherpaOnnx(R.string.voice_engine_sherpa_onnx),
    WhisperCpp(R.string.voice_engine_whisper_cpp)
}
