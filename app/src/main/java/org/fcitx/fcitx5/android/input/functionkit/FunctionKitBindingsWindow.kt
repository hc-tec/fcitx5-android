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

    private var selectedCategoryId: String? = null
    private var hasUncategorizedBindings: Boolean = false
    private var orderedCategories: List<String> = emptyList()
    private var bindings: List<FunctionKitBindingEntry> = emptyList()

    private fun normalizePresentation(value: String?): String =
        value?.trim()?.lowercase().orEmpty()

    private fun shouldOpenPanel(binding: FunctionKitBindingEntry): Boolean =
        normalizePresentation(binding.preferredPresentation).startsWith("panel")

    private fun updateCategoryMetadata() {
        val counts = mutableMapOf<String, Int>()
        val hasAnyCategory = bindings.any { entry -> !entry.categories.isNullOrEmpty() }
        hasUncategorizedBindings = false

        for (binding in bindings) {
            val categories = binding.categories?.filter { it.isNotBlank() }.orEmpty()
            if (categories.isEmpty()) {
                if (hasAnyCategory) {
                    hasUncategorizedBindings = true
                }
                continue
            }
            for (category in categories) {
                counts[category] = (counts[category] ?: 0) + 1
            }
        }

        orderedCategories =
            counts.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
                .map { it.key }
    }

    private fun filterBindings(): List<FunctionKitBindingEntry> {
        val selected = selectedCategoryId
        if (selected.isNullOrBlank()) {
            return bindings
        }
        if (selected == CATEGORY_OTHER) {
            return bindings.filter { it.categories.isNullOrEmpty() }
        }
        return bindings.filter { it.categories?.contains(selected) == true }
    }

    private fun rebuildEntries() {
        val entries = mutableListOf<StatusAreaEntry>()

        val showCategoryFilters = orderedCategories.isNotEmpty()
        if (showCategoryFilters) {
            entries +=
                StatusAreaEntry.FunctionKitBindingCategoryFilter(
                    categoryId = null,
                    label = context.getString(R.string.function_kit_bindings_filter_all),
                    active = selectedCategoryId.isNullOrBlank()
                )
            orderedCategories.forEach { category ->
                entries +=
                    StatusAreaEntry.FunctionKitBindingCategoryFilter(
                        categoryId = category,
                        label = category,
                        active = selectedCategoryId == category
                    )
            }
            if (hasUncategorizedBindings) {
                entries +=
                    StatusAreaEntry.FunctionKitBindingCategoryFilter(
                        categoryId = CATEGORY_OTHER,
                        label = context.getString(R.string.function_kit_bindings_filter_other),
                        active = selectedCategoryId == CATEGORY_OTHER
                    )
            }
        }

        if (trigger == FunctionKitBindingTrigger.Clipboard) {
            val text = clipboardText ?: ClipboardManager.lastEntry?.text
            if (!text.isNullOrBlank()) {
                entries +=
                    StatusAreaEntry.LocalAction(
                        actionId = "clipboard.paste",
                        label = context.getString(android.R.string.paste),
                        icon = R.drawable.ic_baseline_content_paste_24
                    )
            }
        }

        entries += filterBindings().map { binding -> StatusAreaEntry.FunctionKitBindingAction(binding) }
        adapter.entries = entries.toTypedArray()
    }

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
                    is StatusAreaEntry.FunctionKitBindingCategoryFilter -> {
                        selectedCategoryId = entry.categoryId
                        rebuildEntries()
                    }
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
                        val window = requireFunctionKitWindow(bindingEntry.kitId)
                        val openPanel = shouldOpenPanel(bindingEntry)
                        val invocationId =
                            window.enqueueBindingInvocation(
                            binding = bindingEntry,
                            trigger = trigger,
                            clipboardText =
                                if (trigger == FunctionKitBindingTrigger.Clipboard) {
                                    this@FunctionKitBindingsWindow.clipboardText
                                        ?: ClipboardManager.lastEntry?.text
                                } else {
                                    null
                                },
                            startHeadless = !openPanel
                            )
                        if (openPanel) {
                            windowManager.view.post { windowManager.attachWindow(window) }
                            return
                        }

                        windowManager.attachWindow(KeyboardWindow)
                        windowManager.view.post {
                            FunctionKitSnackbars.showBindingTriggered(
                                context = context,
                                windowManager = windowManager,
                                theme = theme,
                                bindingTitle = bindingEntry.title
                            ) {
                                window.requestOpenInvocation(invocationId, bindingEntry.bindingId)
                                windowManager.view.post { windowManager.attachWindow(window) }
                            }
                        }
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
        bindings = FunctionKitBindingRegistry.listForTrigger(context, trigger)
        updateCategoryMetadata()
        rebuildEntries()
    }

    override fun onDetached() {
        // Keep cached Function Kit windows alive so their WebViews preserve runtime state.
    }

    companion object {
        private const val CATEGORY_OTHER = "__other__"
    }
}
