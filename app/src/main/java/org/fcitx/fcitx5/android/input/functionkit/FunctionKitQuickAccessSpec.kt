/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R

internal object FunctionKitQuickAccessSpec {
    private val ToolbarLabelDelimiterRegex = Regex("""[\s_-]+""")

    enum class ToolbarShortcut {
        Clipboard,
        Bindings,
        CursorMove,
        Undo,
        Redo,
        TaskCenter,
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
            ToolbarShortcut.Bindings,
            ToolbarShortcut.TaskCenter,
            ToolbarShortcut.Clipboard,
            ToolbarShortcut.CursorMove,
            ToolbarShortcut.Undo,
            ToolbarShortcut.Redo,
            ToolbarShortcut.More
        )

    fun buildToolbarSlots(functionKitIds: Collection<String>): List<ToolbarSlot> =
        buildList {
            // Keep the most frequently used shortcuts closest to the left edge.
            // User expectation: Clipboard should be immediately after Bindings + Task Center,
            // and NOT pushed behind dynamic kit icons.
            val primary = setOf(ToolbarShortcut.Bindings, ToolbarShortcut.TaskCenter, ToolbarShortcut.Clipboard)
            addAll(fixedToolbarOrder.filter { it in primary }.map(ToolbarSlot::Fixed))
            addAll(
                functionKitIds
                    .filter { it.isNotBlank() }
                    .distinct()
                    .map(ToolbarSlot::FunctionKit)
            )
            addAll(fixedToolbarOrder.filterNot { it in primary }.map(ToolbarSlot::Fixed))
        }

    fun functionKitMonogram(label: String): String {
        val trimmed = label.trim()
        if (trimmed.isBlank()) {
            return "FK"
        }

        val words =
            trimmed.split(ToolbarLabelDelimiterRegex)
                .filter { it.isNotBlank() }
        if (words.size >= 2) {
            return words.take(2).joinToString("") { it.take(1).uppercase() }
        }

        val compact = trimmed.filterNot(Char::isWhitespace)
        if (compact.isBlank()) {
            return "FK"
        }

        return compact.take(2).uppercase()
    }

    fun toolbarButton(shortcut: ToolbarShortcut): FixedToolbarButtonSpec =
        when (shortcut) {
            ToolbarShortcut.Clipboard ->
                FixedToolbarButtonSpec(
                    icon = R.drawable.ic_clipboard,
                    label = R.string.clipboard
                )
            ToolbarShortcut.Bindings ->
                FixedToolbarButtonSpec(
                    icon = R.drawable.ic_baseline_auto_awesome_24,
                    label = R.string.function_kit_bindings
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
            ToolbarShortcut.TaskCenter ->
                FixedToolbarButtonSpec(
                    icon = R.drawable.ic_baseline_access_time_24,
                    label = R.string.function_kit_task_center
                )
            ToolbarShortcut.More ->
                FixedToolbarButtonSpec(
                    icon = R.drawable.ic_baseline_more_horiz_24,
                    label = R.string.status_area
                )
        }
}
