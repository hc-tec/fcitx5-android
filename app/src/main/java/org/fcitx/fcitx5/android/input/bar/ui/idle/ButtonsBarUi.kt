/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitIconLoader
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitQuickAccessSpec
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.view

class ButtonsBarUi(
    override val ctx: Context,
    private val theme: Theme,
    functionKitEntries: List<FunctionKitToolbarButtonEntry>
) : Ui {
    data class FunctionKitToolbarButtonEntry(
        val kitId: String,
        val label: String,
        val iconAssetPath: String? = null
    )

    data class FunctionKitToolbarButtonUi(
        val entry: FunctionKitToolbarButtonEntry,
        val button: ToolButton
    )

    override val root = view(::FlexboxLayout) {
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.SPACE_AROUND
    }

    private fun fixedToolButton(shortcut: FunctionKitQuickAccessSpec.ToolbarShortcut) =
        FunctionKitQuickAccessSpec.toolbarButton(shortcut).let { spec ->
            ToolButton(ctx, spec.icon, theme).apply {
                contentDescription = ctx.getString(spec.label)
            }
        }

    private fun functionKitToolButton(entry: FunctionKitToolbarButtonEntry) =
        ToolButton(ctx, FunctionKitQuickAccessSpec.functionKitIcon, theme).apply {
            FunctionKitIconLoader.loadDrawable(ctx, entry.iconAssetPath)?.let(::setAssetIcon)
                ?: setMonogram(FunctionKitQuickAccessSpec.functionKitMonogram(entry.label))
            contentDescription = entry.label
        }

    private fun addToolButton(button: ToolButton) {
        val size = ctx.dp(40)
        root.addView(button, FlexboxLayout.LayoutParams(size, size))
    }

    val undoButton = fixedToolButton(FunctionKitQuickAccessSpec.ToolbarShortcut.Undo)

    val redoButton = fixedToolButton(FunctionKitQuickAccessSpec.ToolbarShortcut.Redo)

    val cursorMoveButton = fixedToolButton(FunctionKitQuickAccessSpec.ToolbarShortcut.CursorMove)

    val clipboardButton = fixedToolButton(FunctionKitQuickAccessSpec.ToolbarShortcut.Clipboard)

    val functionKitButtons =
        functionKitEntries.map { entry ->
            FunctionKitToolbarButtonUi(entry, functionKitToolButton(entry))
        }

    val moreButton = fixedToolButton(FunctionKitQuickAccessSpec.ToolbarShortcut.More)

    init {
        val functionKitButtonsById = functionKitButtons.associateBy { it.entry.kitId }
        FunctionKitQuickAccessSpec.buildToolbarSlots(functionKitEntries.map { it.kitId }).forEach { slot ->
            when (slot) {
                is FunctionKitQuickAccessSpec.ToolbarSlot.FunctionKit -> {
                    functionKitButtonsById[slot.kitId]?.button?.let(::addToolButton)
                }
                is FunctionKitQuickAccessSpec.ToolbarSlot.Fixed -> {
                    val button =
                        when (slot.shortcut) {
                            FunctionKitQuickAccessSpec.ToolbarShortcut.Clipboard -> clipboardButton
                            FunctionKitQuickAccessSpec.ToolbarShortcut.CursorMove -> cursorMoveButton
                            FunctionKitQuickAccessSpec.ToolbarShortcut.Undo -> undoButton
                            FunctionKitQuickAccessSpec.ToolbarShortcut.Redo -> redoButton
                            FunctionKitQuickAccessSpec.ToolbarShortcut.More -> moreButton
                        }
                    addToolButton(button)
                }
            }
        }
    }
}
