/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.input.voice.VoiceInputEntryConfig
import org.fcitx.fcitx5.android.input.voice.VoiceInputLauncher

internal enum class TextKeyboardLeadingActionMode {
    ShiftUnlocked,
    ShiftLocked,
    PinyinSegment
}

internal fun supportsPinyinSegmentation(ime: InputMethodEntry): Boolean {
    return ime.uniqueName == "pinyin"
}

internal fun canTriggerPinyinSegmentation(ime: InputMethodEntry, hasComposition: Boolean): Boolean {
    return supportsPinyinSegmentation(ime) && hasComposition
}

internal fun shouldShowSpaceVoiceIndicator(
    config: VoiceInputEntryConfig = VoiceInputLauncher.currentConfig(),
    voiceInputAvailable: Boolean = VoiceInputLauncher.isPreferredVoiceInputAvailable()
): Boolean {
    return config.spaceLongPressBehavior == SpaceLongPressBehavior.VoiceInput && voiceInputAvailable
}

internal fun resolveLeadingActionMode(
    ime: InputMethodEntry?,
    capsState: TextKeyboard.CapsState
): TextKeyboardLeadingActionMode {
    return when {
        ime != null && supportsPinyinSegmentation(ime) -> TextKeyboardLeadingActionMode.PinyinSegment
        capsState == TextKeyboard.CapsState.Lock -> TextKeyboardLeadingActionMode.ShiftLocked
        else -> TextKeyboardLeadingActionMode.ShiftUnlocked
    }
}
