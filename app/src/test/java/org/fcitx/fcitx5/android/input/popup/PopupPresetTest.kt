package org.fcitx.fcitx5.android.input.popup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupPresetTest {

    @Test
    fun latinLongPressPopupIsChineseImeFriendly() {
        assertArrayEquals(arrayOf("1", "Q"), PopupPreset["q"])
        assertArrayEquals(arrayOf("3", "E"), PopupPreset["e"])
        assertArrayEquals(arrayOf("@", "A"), PopupPreset["a"])
        assertArrayEquals(arrayOf("@", "a"), PopupPreset["A"])
        assertArrayEquals(arrayOf("?", "V"), PopupPreset["v"])

        val asciiOnlyKeys = listOf("e", "a", "A", "v", "Z")
        asciiOnlyKeys.forEach { key ->
            assertTrue(PopupPreset.getValue(key).all { candidate -> candidate.all { ch -> ch.code < 128 } })
        }
    }
}
