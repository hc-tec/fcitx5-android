package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.InputMethodUtil

internal object VoiceInputLauncher {
    fun currentConfig(prefs: AppPrefs.Keyboard = AppPrefs.getInstance().keyboard): VoiceInputEntryConfig =
        VoiceInputEntryConfig(
            showVoiceInputButton = prefs.showVoiceInputButton.getValue(),
            spaceLongPressBehavior = prefs.spaceKeyLongPressBehavior.getValue(),
            voiceInputMode = prefs.voiceInputMode.getValue()
        )

    fun shouldPrewarm(): Boolean = currentConfig().shouldPrewarmBuiltInEngine()

    fun isPreferredVoiceInputAvailable(prefs: AppPrefs.Keyboard = AppPrefs.getInstance().keyboard): Boolean =
        when (prefs.voiceInputMode.getValue()) {
            VoiceInputMode.BuiltInSpeechRecognizer -> true
            VoiceInputMode.SystemVoiceIme -> resolveSystemVoiceSubtype(prefs) != null
        }

    fun isToolbarVoiceInputAvailable(
        service: FcitxInputMethodService,
        prefs: AppPrefs.Keyboard = AppPrefs.getInstance().keyboard
    ): Boolean = false

    fun launchPreferredVoiceInput(
        service: FcitxInputMethodService,
        windowManager: InputWindowManager,
        startListeningImmediately: Boolean = false,
        prefs: AppPrefs.Keyboard = AppPrefs.getInstance().keyboard
    ): Boolean {
        if (isPasswordField(service)) {
            return false
        }

        return when (prefs.voiceInputMode.getValue()) {
            VoiceInputMode.BuiltInSpeechRecognizer -> {
                windowManager.attachWindow(VoiceInputWindow(autoStartListening = startListeningImmediately))
                true
            }
            VoiceInputMode.SystemVoiceIme -> {
                val (id, subtype) = resolveSystemVoiceSubtype(prefs) ?: return false
                InputMethodUtil.switchInputMethod(service, id, subtype)
                true
            }
        }
    }

    private fun resolveSystemVoiceSubtype(
        prefs: AppPrefs.Keyboard = AppPrefs.getInstance().keyboard
    ) = InputMethodUtil.findVoiceSubtype(prefs.preferredVoiceInput.getValue())

    private fun isPasswordField(service: FcitxInputMethodService): Boolean =
        CapabilityFlags.fromEditorInfo(service.currentInputEditorInfo).has(CapabilityFlag.Password)
}
