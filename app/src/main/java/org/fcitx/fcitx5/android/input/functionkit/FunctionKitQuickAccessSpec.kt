/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R

internal object FunctionKitQuickAccessSpec {
    enum class ToolbarShortcut {
        Clipboard,
        CursorMove,
        Undo,
        Redo,
        More
    }

    sealed class ToolbarSlot {
        data class FunctionKit(val kitId: String) : ToolbarSlot()

        data class Fixed(val shortcut: ToolbarShortcut) : ToolbarSlot()
    }

    data class FixedToolbarButtonSpec(
        @DrawableRes val icon: Int,
        @StringRes val label: Int
    )

    @DrawableRes
    val functionKitIcon: Int = R.drawable.ic_baseline_extension_24

    val fixedToolbarOrder =
        listOf(
            ToolbarShortcut.Clipboard,
            ToolbarShortcut.CursorMove,
            ToolbarShortcut.Undo,
            ToolbarShortcut.Redo,
            ToolbarShortcut.More
        )

    fun buildToolbarSlots(functionKitIds: Collection<String>): List<ToolbarSlot> =
        functionKitIds
            .filter { it.isNotBlank() }
            .distinct()
            .map(ToolbarSlot::FunctionKit) +
            fixedToolbarOrder.map(ToolbarSlot::Fixed)

    fun toolbarButton(shortcut: ToolbarShortcut): FixedToolbarButtonSpec =
        when (shortcut) {
            ToolbarShortcut.Clipboard ->
                FixedToolbarButtonSpec(
                    icon = R.drawable.ic_clipboard,
                    label = R.string.clipboard
                )
            ToolbarShortcut.CursorMove ->
                FixedToolbarButtonSpec(
                    icon = R.drawable.ic_cursor_move,
                    label = R.string.text_editing
                )
            ToolbarShortcut.Undo ->
                FixedToolbarButtonSpec(
                    icon = R.drawable.ic_baseline_undo_24,
                    label = R.string.undo
                )
            ToolbarShortcut.Redo ->
                FixedToolbarButtonSpec(
                    icon = R.drawable.ic_baseline_redo_24,
                    label = R.string.redo
                )
            ToolbarShortcut.More ->
                FixedToolbarButtonSpec(
                    icon = R.drawable.ic_baseline_more_horiz_24,
                    label = R.string.status_area
                )
        }
}
