package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class SherpaOnnxModelPreference(
    override val stringRes: Int
) : ManagedPreferenceEnum {
    HotwordEnhanced(R.string.voice_sherpa_model_preference_hotword),
    FastCtc(R.string.voice_sherpa_model_preference_fast_ctc)
}
