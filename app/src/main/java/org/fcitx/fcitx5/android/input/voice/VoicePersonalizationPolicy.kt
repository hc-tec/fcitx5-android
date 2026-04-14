package org.fcitx.fcitx5.android.input.voice

import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags

internal object VoicePersonalizationPolicy {
    fun isLearningAllowed(editorInfo: EditorInfo): Boolean {
        val capabilityFlags = CapabilityFlags.fromEditorInfo(editorInfo)
        return !capabilityFlags.has(CapabilityFlag.Sensitive)
    }
}
