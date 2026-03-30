/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.view.View
import android.widget.Toast
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.ImeBridgeState
import org.fcitx.fcitx5.android.input.status.StatusAreaAdapter
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.mechdancer.dependency.manager.must
import splitties.views.backgroundColor
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.recyclerview.gridLayoutManager

internal class FunctionKitBindingsWindow(
    private val trigger: FunctionKitBindingTrigger = FunctionKitBindingTrigger.Manual,
    private val clipboardText: String? = null
) : InputWindow.ExtendedInputWindow<FunctionKitBindingsWindow>(),
    InputBroadcastReceiver {

    private val theme by manager.theme()
    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()

    private val functionKitWindowPool: FunctionKitWindowPool by manager.must()

    private fun requireFunctionKitWindow(kitId: String): FunctionKitWindow =
        functionKitWindowPool.require(kitId)

    override val title: String by lazy {
        when (trigger) {
            FunctionKitBindingTrigger.Clipboard -> context.getString(R.string.function_kit_bindings_clipboard)
            FunctionKitBindingTrigger.Selection -> context.getString(R.string.function_kit_bindings_selection)
            else -> context.getString(R.string.function_kit_bindings)
        }
    }

    private val adapter: StatusAreaAdapter by lazy {
        object : StatusAreaAdapter() {
            override fun onItemClick(view: View, entry: StatusAreaEntry) {
                when (entry) {
                    is StatusAreaEntry.LocalAction -> {
                        when (entry.actionId) {
                            "clipboard.paste" -> {
                                val text = this@FunctionKitBindingsWindow.clipboardText
                                    ?: ClipboardManager.lastEntry?.text
                                if (!text.isNullOrBlank()) {
                                    if (ImeBridgeState.isActive()) {
                                        service.queueImeBridgeCommit(text, FcitxInputMethodService.PendingImeBridgeCommitMode.Insert)
                                        Toast.makeText(context, R.string.ime_bridge_pending_insert, Toast.LENGTH_SHORT).show()
                                    } else {
                                        service.commitText(text)
                                    }
                                }
                                windowManager.attachWindow(KeyboardWindow)
                            }
                            else -> {}
                        }
                    }
                    is StatusAreaEntry.FunctionKitBindingAction -> {
                        val bindingEntry = entry.binding
                        val clipboardText =
                            if ("clipboard.text" in bindingEntry.requestedPayloads) {
                                this@FunctionKitBindingsWindow.clipboardText
                                    ?: ClipboardManager.lastEntry?.text
                            } else {
                                null
                            }

                        val window = requireFunctionKitWindow(bindingEntry.kitId)
                        window.enqueueBindingInvocation(
                            binding = bindingEntry,
                            trigger = trigger,
                            clipboardText = clipboardText,
                            capturedContext =
                                FunctionKitBindingInvocationContext.capture(
                                    service = service,
                                    requestedPayloads = bindingEntry.requestedPayloads
                                ),
                            startHeadless = true
                        )
                        Toast.makeText(context, bindingEntry.title, Toast.LENGTH_SHORT).show()
                        windowManager.attachWindow(KeyboardWindow)
                    }
                    else -> {}
                }
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
        val bindings =
            FunctionKitBindingRegistry.listForTrigger(context, trigger)
                .map { binding -> StatusAreaEntry.FunctionKitBindingAction(binding) }

        val entries = mutableListOf<StatusAreaEntry>()
        if (trigger == FunctionKitBindingTrigger.Clipboard) {
            val text = clipboardText ?: ClipboardManager.lastEntry?.text
            if (!text.isNullOrBlank()) {
                entries.add(
                    StatusAreaEntry.LocalAction(
                        actionId = "clipboard.paste",
                        label = context.getString(android.R.string.paste),
                        icon = R.drawable.ic_baseline_content_paste_24
                    )
                )
            }
        }
        entries.addAll(bindings)
        adapter.entries = entries.toTypedArray()
    }

    override fun onDetached() {
        // Keep cached Function Kit windows alive so their WebViews preserve runtime state.
    }
}
