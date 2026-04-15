package org.fcitx.fcitx5.android.input.voice

import android.os.Build
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitInputSnapshotReader
import org.fcitx.fcitx5.android.voice.core.VoiceSessionRequest
import java.util.Locale

internal object VoiceInputContextReader {
    private const val CursorContextChars = 256
    private const val SelectionTextMaxChars = 256

    fun capture(service: FcitxInputMethodService): VoiceSessionRequest {
        val snapshot =
            FunctionKitInputSnapshotReader.capture(
                service = service,
                cursorContextChars = CursorContextChars,
                selectionTextMaxChars = SelectionTextMaxChars
            )
        val selectedText = snapshot.selectedText
        val beforeCursor = snapshot.beforeCursor
        val afterCursor = snapshot.afterCursor
        val hotwords =
            VoiceContextHotwords.extract(
                selectedText = selectedText,
                composingText = "",
                leftContext = beforeCursor,
                rightContext = afterCursor
            )

        return VoiceSessionRequest(
            locale = resolveLocaleTag(service),
            packageName = service.currentInputEditorInfo.packageName.orEmpty(),
            leftContext = beforeCursor,
            selectedText = selectedText,
            composingText = "",
            hotwords = hotwords
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
