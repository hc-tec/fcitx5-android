/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.status.StatusAreaAdapter
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.views.backgroundColor
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.recyclerview.gridLayoutManager

internal class FunctionKitBindingsWindow(
    private val trigger: FunctionKitBindingTrigger = FunctionKitBindingTrigger.Manual
) : InputWindow.ExtendedInputWindow<FunctionKitBindingsWindow>(),
    InputBroadcastReceiver {

    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()

    // Keep Function Kit WebViews alive across window switching so runtime / UI state is not reset.
    private val functionKitWindowCache = mutableMapOf<String, FunctionKitWindow>()

    private fun requireFunctionKitWindow(kitId: String): FunctionKitWindow =
        functionKitWindowCache.getOrPut(kitId) { FunctionKitWindow(kitId) }

    override val title: String by lazy { context.getString(R.string.function_kit_bindings) }

    private val adapter: StatusAreaAdapter by lazy {
        object : StatusAreaAdapter() {
            override fun onItemClick(view: View, entry: StatusAreaEntry) {
                val bindingEntry = (entry as? StatusAreaEntry.FunctionKitBindingAction)?.binding ?: return
                val clipboardText =
                    if ("clipboard.text" in bindingEntry.requestedPayloads) {
                        ClipboardManager.lastEntry?.text
                    } else {
                        null
                    }

                val window = requireFunctionKitWindow(bindingEntry.kitId)
                window.enqueueBindingInvocation(
                    binding = bindingEntry,
                    trigger = trigger,
                    clipboardText = clipboardText
                )
                windowManager.attachWindow(window)
            }

            override val theme = this@FunctionKitBindingsWindow.theme
        }
    }

    private val view by lazy {
        context.recyclerView {
            backgroundColor = theme.barColor
            layoutManager = gridLayoutManager(4)
            adapter = this@FunctionKitBindingsWindow.adapter
        }
    }

    override fun onCreateView() = view

    override fun onAttached() {
        adapter.entries =
            FunctionKitBindingRegistry.listForTrigger(context, trigger)
                .map { binding ->
                    StatusAreaEntry.FunctionKitBindingAction(binding)
                }
                .toTypedArray()
    }

    override fun onDetached() {
        // Keep cached Function Kit windows alive so their WebViews preserve runtime state.
    }
}
