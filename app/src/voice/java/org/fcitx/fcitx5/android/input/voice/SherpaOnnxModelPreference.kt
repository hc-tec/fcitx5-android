package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class SherpaOnnxModelPreference(
    override val stringRes: Int
) : ManagedPreferenceEnum {
    Auto(R.string.voice_sherpa_model_preference_auto),
    MixedZhEn(R.string.voice_sherpa_model_preference_mixed_zh_en),
    HotwordEnhanced(R.string.voice_sherpa_model_preference_hotword),
    // Retained only so legacy preference values can be decoded and migrated safely.
    FastCtc(R.string.voice_sherpa_model_preference_fast_ctc)
}
