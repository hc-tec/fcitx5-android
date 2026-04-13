package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

internal object VoiceEngineFactory {
    fun create(
        context: Context,
        prefs: AppPrefs.Keyboard = AppPrefs.getInstance().keyboard
    ): VoiceEngine =
        when (prefs.builtInVoiceEngine.getValue()) {
            BuiltInVoiceEngine.SherpaOnnx -> SherpaOnnxVoiceEngine(context.applicationContext)
            BuiltInVoiceEngine.WhisperCpp -> WhisperCppVoiceEngine(context.applicationContext)
        }
}
