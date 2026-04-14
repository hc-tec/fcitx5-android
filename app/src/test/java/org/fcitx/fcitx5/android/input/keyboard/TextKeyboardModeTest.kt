package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextKeyboardModeTest {

    @Test
    fun pinyinImeUsesSegmentKey() {
        val ime = InputMethodEntry(
            uniqueName = "pinyin",
            name = "Pinyin",
            icon = "",
            nativeName = "",
            label = "",
            languageCode = "zh_CN",
            addon = "pinyin",
            isConfigurable = true
        )

        assertTrue(supportsPinyinSegmentation(ime))
        assertTrue(canTriggerPinyinSegmentation(ime, hasComposition = true))
        assertFalse(canTriggerPinyinSegmentation(ime, hasComposition = false))
        assertEquals(
            TextKeyboardLeadingActionMode.PinyinSegment,
            resolveLeadingActionMode(ime, TextKeyboard.CapsState.None)
        )
        assertEquals(
            TextKeyboardLeadingActionMode.PinyinSegment,
            resolveLeadingActionMode(ime, TextKeyboard.CapsState.Lock)
        )
    }

    @Test
    fun nonPinyinImeKeepsShiftModes() {
        val ime = InputMethodEntry(
            uniqueName = "keyboard-us",
            name = "English",
            icon = "",
            nativeName = "",
            label = "",
            languageCode = "en",
            addon = "keyboard",
            isConfigurable = false
        )

        assertFalse(supportsPinyinSegmentation(ime))
        assertEquals(
            TextKeyboardLeadingActionMode.ShiftUnlocked,
            resolveLeadingActionMode(ime, TextKeyboard.CapsState.None)
        )
        assertEquals(
            TextKeyboardLeadingActionMode.ShiftUnlocked,
            resolveLeadingActionMode(ime, TextKeyboard.CapsState.Once)
        )
        assertEquals(
            TextKeyboardLeadingActionMode.ShiftLocked,
            resolveLeadingActionMode(ime, TextKeyboard.CapsState.Lock)
        )
    }
}
