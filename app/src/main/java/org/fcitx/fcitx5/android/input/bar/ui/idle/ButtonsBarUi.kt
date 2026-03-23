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
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitQuickAccessSpec
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitQuickAccessSpec.ToolbarShortcut
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.view

class ButtonsBarUi(override val ctx: Context, private val theme: Theme) : Ui {

    override val root = view(::FlexboxLayout) {
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.SPACE_AROUND
    }

    private fun toolButton(spec: FunctionKitQuickAccessSpec.ToolbarButtonSpec) =
        ToolButton(ctx, spec.icon, theme).apply {
            contentDescription = ctx.getString(spec.label)
        }

    private fun addToolButton(button: ToolButton) {
        val size = ctx.dp(40)
        root.addView(button, FlexboxLayout.LayoutParams(size, size))
    }

    val undoButton = toolButton(FunctionKitQuickAccessSpec.toolbarButton(ToolbarShortcut.Undo))

    val redoButton = toolButton(FunctionKitQuickAccessSpec.toolbarButton(ToolbarShortcut.Redo))

    val cursorMoveButton =
        toolButton(FunctionKitQuickAccessSpec.toolbarButton(ToolbarShortcut.CursorMove))

    val clipboardButton = toolButton(FunctionKitQuickAccessSpec.toolbarButton(ToolbarShortcut.Clipboard))

    val functionKitButton =
        toolButton(FunctionKitQuickAccessSpec.toolbarButton(ToolbarShortcut.FunctionKit))

    val moreButton = toolButton(FunctionKitQuickAccessSpec.toolbarButton(ToolbarShortcut.More))

    init {
        FunctionKitQuickAccessSpec.toolbarOrder.forEach { shortcut ->
            val button =
                when (shortcut) {
                    ToolbarShortcut.FunctionKit -> functionKitButton
                    ToolbarShortcut.Clipboard -> clipboardButton
                    ToolbarShortcut.CursorMove -> cursorMoveButton
                    ToolbarShortcut.Undo -> undoButton
                    ToolbarShortcut.Redo -> redoButton
                    ToolbarShortcut.More -> moreButton
                }
            addToolButton(button)
        }
    }
}
