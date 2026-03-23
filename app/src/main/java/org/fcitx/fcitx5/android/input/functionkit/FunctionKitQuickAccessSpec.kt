/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry

internal object FunctionKitQuickAccessSpec {
    enum class ToolbarShortcut {
        FunctionKit,
        Clipboard,
        CursorMove,
        Undo,
        Redo,
        More
    }

    data class ToolbarButtonSpec(
        @DrawableRes val icon: Int,
        @StringRes val label: Int
    )

    data class StatusEntrySpec(
        @StringRes val label: Int,
        @DrawableRes val icon: Int,
        val type: StatusAreaEntry.Android.Type
    )

    val toolbarOrder =
        listOf(
            ToolbarShortcut.FunctionKit,
            ToolbarShortcut.Clipboard,
            ToolbarShortcut.CursorMove,
            ToolbarShortcut.Undo,
            ToolbarShortcut.Redo,
            ToolbarShortcut.More
        )

    fun toolbarButton(shortcut: ToolbarShortcut): ToolbarButtonSpec =
        when (shortcut) {
            ToolbarShortcut.FunctionKit ->
                ToolbarButtonSpec(
                    icon = R.drawable.ic_baseline_extension_24,
                    label = R.string.function_kit
                )
            ToolbarShortcut.Clipboard ->
                ToolbarButtonSpec(
                    icon = R.drawable.ic_clipboard,
                    label = R.string.clipboard
                )
            ToolbarShortcut.CursorMove ->
                ToolbarButtonSpec(
                    icon = R.drawable.ic_cursor_move,
                    label = R.string.text_editing
                )
            ToolbarShortcut.Undo ->
                ToolbarButtonSpec(
                    icon = R.drawable.ic_baseline_undo_24,
                    label = R.string.undo
                )
            ToolbarShortcut.Redo ->
                ToolbarButtonSpec(
                    icon = R.drawable.ic_baseline_redo_24,
                    label = R.string.redo
                )
            ToolbarShortcut.More ->
                ToolbarButtonSpec(
                    icon = R.drawable.ic_baseline_more_horiz_24,
                    label = R.string.status_area
                )
        }

    fun statusEntries(): List<StatusEntrySpec> =
        listOf(
            StatusEntrySpec(
                label = R.string.function_kit,
                icon = R.drawable.ic_baseline_extension_24,
                type = StatusAreaEntry.Android.Type.FunctionKit
            ),
            StatusEntrySpec(
                label = R.string.function_kit_settings,
                icon = R.drawable.ic_baseline_settings_24,
                type = StatusAreaEntry.Android.Type.FunctionKitSettings
            )
        )
}
