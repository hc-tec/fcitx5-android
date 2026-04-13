package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class VoiceModelPreference(
    override val stringRes: Int
) : ManagedPreferenceEnum {
    Auto(R.string.voice_model_preference_auto),
    Fast(R.string.voice_model_preference_fast),
    Balanced(R.string.voice_model_preference_balanced),
    Accurate(R.string.voice_model_preference_accurate)
}
