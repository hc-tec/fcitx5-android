package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.voice.core.VoiceCorrectionFeedback
import org.fcitx.fcitx5.android.voice.core.VoiceSessionManager
import org.fcitx.fcitx5.android.voice.core.VoiceSessionRequest

internal object VoiceInputRuntime {
    val personalizationStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        VoicePersonalizationStore.create()
    }

    val sessionManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        VoiceSessionManager(correctionStore = personalizationStore)
    }

    fun captureRequest(service: FcitxInputMethodService): VoiceSessionRequest =
        enrichRequest(VoiceInputContextReader.capture(service))

    fun enrichRequest(request: VoiceSessionRequest): VoiceSessionRequest =
        request.copy(
            hotwords =
                personalizationStore.suggestHotwords(
                    locale = request.locale,
                    packageName = request.packageName,
                    seedHotwords = request.hotwords
                )
        )

    fun rememberAcceptedPhrases(
        locale: String,
        packageName: String,
        phrases: List<String>
    ) {
        personalizationStore.rememberAcceptedPhrases(locale, packageName, phrases)
    }

    fun recordCorrection(feedback: VoiceCorrectionFeedback) {
        sessionManager.recordCorrection(feedback)
    }
}
