package org.fcitx.fcitx5.android.input.voice

import android.content.Context

internal object VoiceEngineFactory {
    fun create(context: Context): VoiceEngine = SherpaOnnxVoiceEngine(context.applicationContext)
}
