package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.voice.core.VoiceSessionManager

internal object VoiceInputRuntime {
    val sessionManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        VoiceSessionManager()
    }
}
