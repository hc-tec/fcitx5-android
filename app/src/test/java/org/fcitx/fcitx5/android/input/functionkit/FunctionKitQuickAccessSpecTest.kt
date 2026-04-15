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
    fun `toolbar order keeps bindings and task center before function kits`() {
        assertEquals(
            listOf(
                FunctionKitQuickAccessSpec.ToolbarSlot.Fixed(FunctionKitQuickAccessSpec.ToolbarShortcut.Bindings),
                FunctionKitQuickAccessSpec.ToolbarSlot.Fixed(FunctionKitQuickAccessSpec.ToolbarShortcut.TaskCenter),
                FunctionKitQuickAccessSpec.ToolbarSlot.Fixed(FunctionKitQuickAccessSpec.ToolbarShortcut.Clipboard),
                FunctionKitQuickAccessSpec.ToolbarSlot.FunctionKit("chat-auto-reply"),
                FunctionKitQuickAccessSpec.ToolbarSlot.FunctionKit("quick-phrases"),
                FunctionKitQuickAccessSpec.ToolbarSlot.Fixed(FunctionKitQuickAccessSpec.ToolbarShortcut.CursorMove),
                FunctionKitQuickAccessSpec.ToolbarSlot.Fixed(FunctionKitQuickAccessSpec.ToolbarShortcut.Undo),
                FunctionKitQuickAccessSpec.ToolbarSlot.Fixed(FunctionKitQuickAccessSpec.ToolbarShortcut.Redo),
                FunctionKitQuickAccessSpec.ToolbarSlot.Fixed(FunctionKitQuickAccessSpec.ToolbarShortcut.More)
            ),
            FunctionKitQuickAccessSpec.buildToolbarSlots(listOf("chat-auto-reply", "quick-phrases"))
        )
    }

    @Test
    fun `function kit toolbar button uses generic extension icon`() {
        assertEquals(R.drawable.ic_baseline_extension_24, FunctionKitQuickAccessSpec.functionKitIcon)
    }

    @Test
    fun `function kit monogram uses leading tokens when available`() {
        assertEquals("CA", FunctionKitQuickAccessSpec.functionKitMonogram("Chat Auto Reply"))
        assertEquals("QP", FunctionKitQuickAccessSpec.functionKitMonogram("Quick Phrases"))
    }

    @Test
    fun `function kit monogram falls back to visible characters`() {
        assertEquals("聊天", FunctionKitQuickAccessSpec.functionKitMonogram("聊天自动回复"))
        assertEquals("FK", FunctionKitQuickAccessSpec.functionKitMonogram("   "))
    }

    @Test
    fun `fixed toolbar order keeps clipboard editing undo redo task center and more`() {
        assertEquals(
            listOf(
                FunctionKitQuickAccessSpec.ToolbarShortcut.Bindings,
                FunctionKitQuickAccessSpec.ToolbarShortcut.TaskCenter,
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
    fun `task center button uses access time icon and task center label`() {
        val spec = FunctionKitQuickAccessSpec.toolbarButton(FunctionKitQuickAccessSpec.ToolbarShortcut.TaskCenter)

        assertEquals(R.drawable.ic_baseline_access_time_24, spec.icon)
        assertEquals(R.string.function_kit_task_center, spec.label)
    }

    @Test
    fun `more button keeps status area semantics`() {
        val spec = FunctionKitQuickAccessSpec.toolbarButton(FunctionKitQuickAccessSpec.ToolbarShortcut.More)

        assertEquals(R.drawable.ic_baseline_more_horiz_24, spec.icon)
        assertEquals(R.string.status_area, spec.label)
    }
}
