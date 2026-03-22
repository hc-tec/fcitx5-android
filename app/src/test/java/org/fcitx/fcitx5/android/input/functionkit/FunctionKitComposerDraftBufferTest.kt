/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import org.junit.Assert.assertEquals
import org.junit.Test

class FunctionKitComposerDraftBufferTest {
    @Test
    fun `commit text replaces current selection`() {
        val state =
            ComposerDraftBufferState(
                text = "hello world",
                selectionStart = 6,
                selectionEnd = 11
            )

        val next = FunctionKitComposerDraftBuffer.commitText(state, "kit", 3)

        assertEquals("hello kit", next.text)
        assertEquals(9, next.selectionStart)
        assertEquals(9, next.selectionEnd)
    }

    @Test
    fun `backspace deletes previous code point`() {
        val state =
            ComposerDraftBufferState(
                text = "A😀B",
                selectionStart = 3,
                selectionEnd = 3
            )

        val next = FunctionKitComposerDraftBuffer.backspace(state)

        assertEquals("AB", next.text)
        assertEquals(1, next.selectionStart)
        assertEquals(1, next.selectionEnd)
    }

    @Test
    fun `delete surrounding removes content around selection`() {
        val state =
            ComposerDraftBufferState(
                text = "0123456789",
                selectionStart = 4,
                selectionEnd = 6
            )

        val next = FunctionKitComposerDraftBuffer.deleteSurrounding(state, before = 2, after = 2)

        assertEquals("0189", next.text)
        assertEquals(2, next.selectionStart)
        assertEquals(2, next.selectionEnd)
    }

    @Test
    fun `apply selection offset clamps inside bounds`() {
        val state =
            ComposerDraftBufferState(
                text = "draft",
                selectionStart = 2,
                selectionEnd = 4
            )

        val next =
            FunctionKitComposerDraftBuffer.applySelectionOffset(
                state = state,
                offsetStart = -10,
                offsetEnd = 10
            )

        assertEquals(0, next.selectionStart)
        assertEquals(5, next.selectionEnd)
    }
}
