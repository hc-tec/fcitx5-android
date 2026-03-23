/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import org.fcitx.fcitx5.android.R
import org.junit.Assert.assertEquals
import org.junit.Test

class FunctionKitQuickAccessSpecTest {
    @Test
    fun `toolbar order inserts function kits before secondary actions`() {
        assertEquals(
            listOf(
                FunctionKitQuickAccessSpec.ToolbarSlot.FunctionKit("chat-auto-reply"),
                FunctionKitQuickAccessSpec.ToolbarSlot.FunctionKit("note-capture"),
                FunctionKitQuickAccessSpec.ToolbarSlot.Fixed(FunctionKitQuickAccessSpec.ToolbarShortcut.Clipboard),
                FunctionKitQuickAccessSpec.ToolbarSlot.Fixed(FunctionKitQuickAccessSpec.ToolbarShortcut.CursorMove),
                FunctionKitQuickAccessSpec.ToolbarSlot.Fixed(FunctionKitQuickAccessSpec.ToolbarShortcut.Undo),
                FunctionKitQuickAccessSpec.ToolbarSlot.Fixed(FunctionKitQuickAccessSpec.ToolbarShortcut.Redo),
                FunctionKitQuickAccessSpec.ToolbarSlot.Fixed(FunctionKitQuickAccessSpec.ToolbarShortcut.More)
            ),
            FunctionKitQuickAccessSpec.buildToolbarSlots(listOf("chat-auto-reply", "note-capture"))
        )
    }

    @Test
    fun `function kit toolbar button uses generic extension icon`() {
        assertEquals(R.drawable.ic_baseline_extension_24, FunctionKitQuickAccessSpec.functionKitIcon)
    }

    @Test
    fun `fixed toolbar order keeps clipboard editing undo redo and more`() {
        assertEquals(
            listOf(
                FunctionKitQuickAccessSpec.ToolbarShortcut.Clipboard,
                FunctionKitQuickAccessSpec.ToolbarShortcut.CursorMove,
                FunctionKitQuickAccessSpec.ToolbarShortcut.Undo,
                FunctionKitQuickAccessSpec.ToolbarShortcut.Redo,
                FunctionKitQuickAccessSpec.ToolbarShortcut.More
            ),
            FunctionKitQuickAccessSpec.fixedToolbarOrder
        )
    }

    @Test
    fun `more button keeps status area semantics`() {
        val spec = FunctionKitQuickAccessSpec.toolbarButton(FunctionKitQuickAccessSpec.ToolbarShortcut.More)

        assertEquals(R.drawable.ic_baseline_more_horiz_24, spec.icon)
        assertEquals(R.string.status_area, spec.label)
    }
}
