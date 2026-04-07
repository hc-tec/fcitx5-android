/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.view.View
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must

internal class FunctionKitBindingsWindow(
    trigger: FunctionKitBindingTrigger = FunctionKitBindingTrigger.Manual,
    clipboardText: String? = null
) : InputWindow.ExtendedInputWindow<FunctionKitBindingsWindow>(),
    InputBroadcastReceiver,
    FcitxInputMethodService.LocalInputTarget {

    override val type = FunctionKitBindingsWindow::class

    private val theme: Theme by manager.theme()
    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val kawaiiBar: KawaiiBarComponent by manager.must()
    private val preedit: PreeditComponent by manager.must()
    private val windowPool: FunctionKitWindowPool by manager.must()

    private val controller by lazy(LazyThreadSafetyMode.NONE) {
        FunctionKitBindingsWindowController(
            context = context,
            theme = theme,
            service = service,
            windowManager = windowManager,
            kawaiiBar = kawaiiBar,
            preedit = preedit,
            windowPool = windowPool,
            trigger = trigger,
            clipboardText = clipboardText
        )
    }

    override val showTitle: Boolean = false

    override val title: String
        get() = controller.title

    override fun onCreateBarExtension(): View = controller.onCreateBarExtension()

    override fun onCreateView(): View = controller.onCreateView()

    override fun onAttached() {
        service.localInputTarget = this
        controller.onAttached()
    }

    override fun onDetached() {
        controller.onDetached()
        if (service.localInputTarget === this) {
            service.localInputTarget = null
        }
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) =
        controller.onCandidateUpdate(data)

    override fun onClientPreeditUpdate(data: FormattedText) =
        controller.onClientPreeditUpdate(data)

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) =
        controller.onInputPanelUpdate(data)

    override fun isActive(): Boolean = controller.isActive()

    override fun commitText(text: String, cursor: Int): Boolean =
        controller.commitText(text, cursor)

    override fun deleteSurrounding(before: Int, after: Int): Boolean =
        controller.deleteSurrounding(before, after)

    override fun handleBackspace(): Boolean = controller.handleBackspace()

    override fun handleEnter(): Boolean = controller.handleEnter()

    override fun handleArrow(keyCode: Int): Boolean = controller.handleArrow(keyCode)

    override fun deleteSelection(): Boolean = controller.deleteSelection()

    override fun applySelectionOffset(offsetStart: Int, offsetEnd: Int): Boolean =
        controller.applySelectionOffset(offsetStart, offsetEnd)

    override fun cancelSelection(): Boolean = controller.cancelSelection()
}
