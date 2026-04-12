package org.fcitx.fcitx5.android.input.voice

import android.os.Build
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.voice.core.VoiceSessionRequest
import java.util.Locale

internal object VoiceInputContextReader {
    fun capture(service: FcitxInputMethodService): VoiceSessionRequest {
        val inputConnection = service.currentInputConnection
        val selectedText = inputConnection?.getSelectedText(0)?.toString().orEmpty()
        val beforeCursor = inputConnection?.getTextBeforeCursor(64, 0)?.toString().orEmpty()

        return VoiceSessionRequest(
            locale = resolveLocaleTag(service),
            packageName = service.currentInputEditorInfo.packageName.orEmpty(),
            leftContext = beforeCursor,
            selectedText = selectedText,
            composingText = "",
            hotwords = emptyList()
        )
    }

    fun resolveLocaleTag(service: FcitxInputMethodService): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            service.currentInputEditorInfo.hintLocales?.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()?.takeIf {
                it.isNotBlank()
            }?.let { return it }
            service.resources.configuration.locales.get(0)?.toLanguageTag()?.takeIf {
                it.isNotBlank()
            }?.let { return it }
        }
        return Locale.getDefault().toLanguageTag()
    }
}
