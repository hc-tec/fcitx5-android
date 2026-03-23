/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitQuickAccessSpec.ToolbarShortcut
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class FunctionKitQuickAccessSpecTest {
    @Test
    fun `toolbar order prioritizes function kit ahead of secondary actions`() {
        assertEquals(
            listOf(
                ToolbarShortcut.FunctionKit,
                ToolbarShortcut.Clipboard,
                ToolbarShortcut.CursorMove,
                ToolbarShortcut.Undo,
                ToolbarShortcut.Redo,
                ToolbarShortcut.More
            ),
            FunctionKitQuickAccessSpec.toolbarOrder
        )
    }

    @Test
    fun `function kit toolbar button uses generic extension semantics`() {
        val spec = FunctionKitQuickAccessSpec.toolbarButton(ToolbarShortcut.FunctionKit)

        assertEquals(R.drawable.ic_baseline_extension_24, spec.icon)
        assertEquals(R.string.function_kit, spec.label)
    }

    @Test
    fun `status area starts with function kit entry and settings`() {
        val entries = FunctionKitQuickAccessSpec.statusEntries()

        assertEquals(2, entries.size)
        assertEquals(R.string.function_kit, entries[0].label)
        assertEquals(R.drawable.ic_baseline_extension_24, entries[0].icon)
        assertEquals(StatusAreaEntry.Android.Type.FunctionKit, entries[0].type)
        assertEquals(R.string.function_kit_settings, entries[1].label)
        assertEquals(R.drawable.ic_baseline_settings_24, entries[1].icon)
        assertEquals(StatusAreaEntry.Android.Type.FunctionKitSettings, entries[1].type)
    }
}
