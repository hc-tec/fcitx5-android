/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.preedit.PreeditUi
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.wm.ImeBridgeState
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.horizontalPadding

internal class FunctionKitBindingsWindowController(
    private val context: Context,
    private val theme: Theme,
    private val service: FcitxInputMethodService,
    private val windowManager: InputWindowManager,
    private val kawaiiBar: KawaiiBarComponent,
    private val preedit: PreeditComponent,
    private val windowPool: FunctionKitWindowPool,
    private val trigger: FunctionKitBindingTrigger,
    private val clipboardText: String?
) {
    private enum class Tab {
        Recent,
        Pinned,
        Library
    }

    val title: String =
        when (trigger) {
            FunctionKitBindingTrigger.Clipboard -> context.getString(R.string.function_kit_bindings_clipboard)
            FunctionKitBindingTrigger.Selection -> context.getString(R.string.function_kit_bindings_selection)
            else -> context.getString(R.string.function_kit_bindings)
        }

    private val weChatGreen: Int = 0xFF07C160.toInt()

    private var windowAttached: Boolean = false

    private var activeTab: Tab = Tab.Recent
    private var selectedCategoryId: String? = null
    private var orderedCategories: List<String> = emptyList()
    private var bindings: List<FunctionKitBindingEntry> = emptyList()

    private var searchDraft: ComposerDraftBufferState = ComposerDraftBufferState()
    private var searchFocused: Boolean = false

    private var currentCandidateCount: Int = 0

    private var panelPeekHeightPx: Int = 0
    private var windowManagerHeightBeforeAttach: Int? = null
    private var embeddedKeyboardView: View? = null
    private var embeddedKeyboardWindow: KeyboardWindow? = null

    private var removeBindingSettingsListener: (() -> Unit)? = null

    private fun requireFunctionKitWindow(kitId: String): FunctionKitWindow =
        windowPool.require(kitId)

    fun onCreateBarExtension(): View = barExtension

    fun onCreateView(): View {
        val baseHeight = windowManager.view.layoutParams?.height ?: 0
        panelPeekHeightPx = resolvePanelPeekHeightPx(baseHeight)
        return rootView
    }

    fun onAttached() {
        windowAttached = true

        val baseHeight = windowManager.view.layoutParams?.height ?: 0
        if (baseHeight > 0) {
            windowManagerHeightBeforeAttach = baseHeight
        } else if (windowManagerHeightBeforeAttach == null) {
            resolveKeyboardHeightFromPrefsPx().takeIf { it > 0 }?.let { fallbackHeight ->
                windowManagerHeightBeforeAttach = fallbackHeight
            }
        }
        panelPeekHeightPx = resolvePanelPeekHeightPx(windowManagerHeightBeforeAttach ?: baseHeight)

        val keyboardView = windowManager.requireEssentialWindowView(KeyboardWindow)
        (keyboardView.parent as? ViewGroup)?.removeView(keyboardView)
        embeddedKeyboardContainer.removeAllViews()
        embeddedKeyboardContainer.addView(
            keyboardView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        embeddedKeyboardView = keyboardView
        embeddedKeyboardWindow = windowManager.getEssentialWindow(KeyboardWindow) as KeyboardWindow
        embeddedKeyboardWindow?.onAttached()

        bindings = FunctionKitBindingRegistry.listForTrigger(context, trigger)
        rebuildUi()
        updateSearchBarUi()

        removeBindingSettingsListener =
            FunctionKitBindingSettings.addOnChangeListener { _ ->
                windowManager.view.post { rebuildUi() }
            }

        setSearchFocused(false)
        syncEmbeddedKeyboardLayout()
    }

    fun onDetached() {
        windowAttached = false
        setSearchFocused(false)

        removeBindingSettingsListener?.invoke()
        removeBindingSettingsListener = null

        kawaiiBar.setFunctionKitCandidateDock(null)
        preedit.setFunctionKitSuppressed(false)

        embeddedKeyboardWindow?.onDetached()
        embeddedKeyboardWindow = null

        embeddedKeyboardView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        embeddedKeyboardView = null

        windowManagerHeightBeforeAttach?.let { height ->
            windowManager.view.layoutParams?.let { params ->
                if (params.height != height) {
                    params.height = height
                    windowManager.view.layoutParams = params
                }
            }
        }
    }

    fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        currentCandidateCount = data.candidates.size
        syncEmbeddedCandidateDock(shouldShowEmbeddedKeyboard())
    }

    fun onClientPreeditUpdate(data: FormattedText) {
        if (windowAttached) {
            embeddedPreeditUi.update(
                FcitxEvent.InputPanelEvent.Data(
                    preedit = data,
                    auxUp = FormattedText.Empty,
                    auxDown = FormattedText.Empty
                )
            )
            embeddedPreeditUi.root.visibility =
                if (embeddedPreeditUi.visible) View.VISIBLE else View.INVISIBLE
        }
        syncEmbeddedCandidateDock(shouldShowEmbeddedKeyboard())
    }

    fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        if (windowAttached) {
            embeddedPreeditUi.update(data)
            embeddedPreeditUi.root.visibility =
                if (embeddedPreeditUi.visible) View.VISIBLE else View.INVISIBLE
        }
        syncEmbeddedCandidateDock(shouldShowEmbeddedKeyboard())
    }

    fun isActive(): Boolean = windowAttached && searchFocused

    fun commitText(text: String, cursor: Int): Boolean {
        if (!isActive()) return false
        searchDraft = FunctionKitComposerDraftBuffer.commitText(searchDraft, text, cursor)
        updateSearchBarUi()
        return true
    }

    fun deleteSurrounding(before: Int, after: Int): Boolean {
        if (!isActive()) return false
        searchDraft = FunctionKitComposerDraftBuffer.deleteSurrounding(searchDraft, before, after)
        updateSearchBarUi()
        return true
    }

    fun handleBackspace(): Boolean {
        if (!isActive()) return false
        searchDraft = FunctionKitComposerDraftBuffer.backspace(searchDraft)
        updateSearchBarUi()
        return true
    }

    fun handleEnter(): Boolean {
        if (!isActive()) return false
        setSearchFocused(false)
        return true
    }

    fun handleArrow(keyCode: Int): Boolean {
        if (!isActive()) return false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                searchDraft = FunctionKitComposerDraftBuffer.applySelectionOffset(searchDraft, -1, -1)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                searchDraft = FunctionKitComposerDraftBuffer.applySelectionOffset(searchDraft, 1, 1)
            }
            else -> return false
        }
        updateSearchBarUi()
        return true
    }

    fun deleteSelection(): Boolean {
        if (!isActive()) return false
        val normalized = FunctionKitComposerDraftBuffer.normalize(searchDraft)
        if (normalized.selectionStart == normalized.selectionEnd) {
            return false
        }
        searchDraft = FunctionKitComposerDraftBuffer.commitText(normalized, "", 0)
        updateSearchBarUi()
        return true
    }

    fun applySelectionOffset(offsetStart: Int, offsetEnd: Int): Boolean {
        if (!isActive()) return false
        searchDraft = FunctionKitComposerDraftBuffer.applySelectionOffset(searchDraft, offsetStart, offsetEnd)
        updateSearchBarUi()
        return true
    }

    fun cancelSelection(): Boolean {
        if (!isActive()) return false
        searchDraft = FunctionKitComposerDraftBuffer.cancelSelection(searchDraft)
        updateSearchBarUi()
        return true
    }

    private fun currentSearchQuery(): String = searchDraft.text.trim()

    private fun setSearchFocused(focused: Boolean) {
        if (searchFocused == focused) {
            return
        }
        searchFocused = focused
        updateSearchBarUi()
        syncEmbeddedKeyboardLayout()
    }

    private fun updateSearchBarUi() {
        val query = currentSearchQuery()
        val placeholder = context.getString(R.string.function_kit_bindings_search_placeholder)
        val showPlaceholder = query.isBlank()

        searchTextView.text = if (showPlaceholder) placeholder else query
        searchTextView.setTextColor(
            if (showPlaceholder) theme.altKeyTextColor else theme.keyTextColor
        )
        clearSearchButton.isVisible = query.isNotBlank()
        val borderColor = if (searchFocused) weChatGreen else 0x00000000
        (searchField.background as? GradientDrawable)?.setStroke(context.dp(1), borderColor)

        rebuildRows()
    }

    private fun updateCategoryMetadata() {
        val counts = mutableMapOf<String, Int>()
        for (binding in bindings) {
            val categories = binding.categories?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
            for (category in categories) {
                counts[category] = (counts[category] ?: 0) + 1
            }
        }

        val preferredOrder = listOf("写作重写", "管理/工具", "阅读摘要")
        val sorted =
            counts.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Int>> { it.value }
                        .thenBy { it.key.lowercase() }
                )
                .map { it.key }
        val preferred = preferredOrder.filter { it in sorted }
        orderedCategories = (preferred + sorted.filter { it !in preferred }).take(3)

        val selected = selectedCategoryId
        if (!selected.isNullOrBlank() && selected !in orderedCategories) {
            selectedCategoryId = null
        }
    }

    private fun matchesQuery(entry: FunctionKitBindingEntry, query: String): Boolean {
        if (query.isBlank()) {
            return true
        }
        val needle = query.lowercase()
        if (entry.title.lowercase().contains(needle)) return true
        if (entry.kitLabel.lowercase().contains(needle)) return true
        val categories = entry.categories?.joinToString(" ")?.lowercase().orEmpty()
        if (categories.contains(needle)) return true
        return false
    }

    private fun filterBindings(): List<FunctionKitBindingEntry> {
        val query = currentSearchQuery()
        val base =
            when (activeTab) {
                Tab.Recent ->
                    bindings
                        .map { it to FunctionKitBindingSettings.lastUsedAtEpochMs(it.stableId) }
                        .filter { it.second > 0L }
                        .sortedByDescending { it.second }
                        .map { it.first }
                Tab.Pinned ->
                    bindings.filter { FunctionKitBindingSettings.isBindingPinned(it.stableId) }
                Tab.Library -> bindings
            }

        val selectedCategory = selectedCategoryId?.trim().orEmpty()
        val categoryFiltered =
            if (activeTab == Tab.Library && selectedCategory.isNotBlank()) {
                base.filter { entry ->
                    entry.categories?.any { it.trim() == selectedCategory } == true
                }
            } else {
                base
            }

        return categoryFiltered.filter { matchesQuery(it, query) }
    }

    private fun addCategoryChip(
        label: String,
        categoryId: String?,
        active: Boolean
    ) {
        val cornerRadius = context.dp(999).toFloat()
        val backgroundDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadius = cornerRadius
                setColor(if (active) weChatGreen else theme.popupBackgroundColor)
                if (!active) {
                    setStroke(context.dp(1), theme.dividerColor)
                }
            }
        val maskDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadius = cornerRadius
                setColor(Color.WHITE)
            }
        val view =
            TextView(context).apply {
                text = label
                setTextColor(if (active) Color.WHITE else theme.altKeyTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                includeFontPadding = false
                maxLines = 1
                setPadding(context.dp(12), context.dp(7), context.dp(12), context.dp(7))
                background = backgroundDrawable
                foreground =
                    RippleDrawable(
                        ColorStateList.valueOf(theme.keyPressHighlightColor),
                        null,
                        maskDrawable
                    )
                setOnClickListener {
                    selectedCategoryId = categoryId
                    rebuildCategoryChips()
                    rebuildRows()
                }
            }
        categoryRow.addView(
            view,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = context.dp(8)
            }
        )
    }

    private fun rebuildCategoryChips() {
        val showCategoryFilters = activeTab == Tab.Library && orderedCategories.isNotEmpty()
        categoryScrollView.isVisible = showCategoryFilters
        if (!showCategoryFilters) {
            categoryRow.removeAllViews()
            return
        }

        categoryRow.removeAllViews()
        addCategoryChip(
            label = context.getString(R.string.function_kit_bindings_filter_all),
            categoryId = null,
            active = selectedCategoryId.isNullOrBlank()
        )
        orderedCategories.forEach { category ->
            addCategoryChip(
                label = category,
                categoryId = category,
                active = selectedCategoryId == category
            )
        }
    }

    private fun applyTabUi() {
        applyTabButtonState(recentTabButton, activeTab == Tab.Recent)
        applyTabButtonState(pinnedTabButton, activeTab == Tab.Pinned)
        applyTabButtonState(libraryTabButton, activeTab == Tab.Library)
    }

    private fun applyTabButtonState(button: LinearLayout, active: Boolean) {
        (button.background as? GradientDrawable)?.setColor(
            if (active) theme.popupBackgroundColor else Color.TRANSPARENT
        )
        val icon = button.findViewById<ImageView>(android.R.id.icon)
        val label = button.findViewById<TextView>(android.R.id.text1)
        val iconColor = if (active) weChatGreen else theme.altKeyTextColor
        val textColor = if (active) theme.keyTextColor else theme.altKeyTextColor
        icon.setColorFilter(iconColor)
        label.setTextColor(textColor)
    }

    private fun rebuildRows() {
        val items = mutableListOf<FunctionKitBindingCardItem>()

        val clipboard =
            if (trigger == FunctionKitBindingTrigger.Clipboard) {
                clipboardText ?: ClipboardManager.lastEntry?.text
            } else {
                null
            }
        if (!clipboard.isNullOrBlank()) {
            items += FunctionKitBindingCardItem.LocalPaste(clipboard)
        }

        items += FunctionKitBindingCardItem.OpenDownloadCenter
        val filtered = filterBindings()
        filtered.forEach { entry ->
            items += FunctionKitBindingCardItem.Binding(entry)
        }

        adapter.items = items

        val empty = filtered.isEmpty()
        emptyHint.isVisible = empty
        if (empty) {
            emptyHint.text =
                when (activeTab) {
                    Tab.Recent -> context.getString(R.string.function_kit_bindings_empty_recent)
                    Tab.Pinned -> context.getString(R.string.function_kit_bindings_empty_pinned)
                    Tab.Library -> context.getString(R.string.function_kit_bindings_empty)
                }
        }
    }

    private fun rebuildUi() {
        updateCategoryMetadata()
        rebuildCategoryChips()
        applyTabUi()
        rebuildRows()
    }

    private val backButton: ImageView by lazy {
        ImageView(context).apply {
            setImageResource(R.drawable.ic_baseline_arrow_back_24)
            setColorFilter(theme.altKeyTextColor)
            setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
            setOnClickListener {
                windowManager.attachWindow(KeyboardWindow)
            }
        }
    }

    private val searchTextView: TextView by lazy {
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            includeFontPadding = false
            setTextColor(theme.altKeyTextColor)
            maxLines = 1
        }
    }

    private val clearSearchButton: ImageView by lazy {
        ImageView(context).apply {
            setImageResource(R.drawable.ic_baseline_close_24)
            setColorFilter(theme.altKeyTextColor)
            isVisible = false
            setOnClickListener {
                searchDraft = ComposerDraftBufferState()
                updateSearchBarUi()
            }
        }
    }

    private val searchField: FrameLayout by lazy {
        val cornerRadius = context.dp(12).toFloat()
        val backgroundDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadius = cornerRadius
                setColor(theme.keyBackgroundColor)
                setStroke(context.dp(1), 0x00000000)
            }
        val maskDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadius = cornerRadius
                setColor(Color.WHITE)
            }

        FrameLayout(context).apply {
            background = backgroundDrawable
            foreground =
                RippleDrawable(
                    ColorStateList.valueOf(theme.keyPressHighlightColor),
                    null,
                    maskDrawable
                )
            setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_baseline_search_24)
                    setColorFilter(theme.altKeyTextColor)
                },
                FrameLayout.LayoutParams(context.dp(18), context.dp(18), Gravity.START or Gravity.CENTER_VERTICAL)
            )
            addView(
                searchTextView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL
                ).apply {
                    marginStart = context.dp(26)
                    marginEnd = context.dp(26)
                }
            )
            addView(
                clearSearchButton,
                FrameLayout.LayoutParams(context.dp(18), context.dp(18), Gravity.END or Gravity.CENTER_VERTICAL)
            )
            setOnClickListener {
                setSearchFocused(true)
            }
        }
    }

    private val barExtension: View by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(4), context.dp(2), context.dp(8), context.dp(2))
            addView(
                backButton,
                LinearLayout.LayoutParams(context.dp(40), context.dp(40))
            )
            addView(
                searchField,
                LinearLayout.LayoutParams(
                    0,
                    context.dp(36),
                    1f
                )
            )
        }
    }

    private fun shouldShowEmbeddedKeyboard(): Boolean = searchFocused

    private fun syncEmbeddedKeyboardLayout() {
        val showKeyboard = shouldShowEmbeddedKeyboard()
        syncEmbeddedCandidateDock(showKeyboard)
        embeddedKeyboardContainer.isVisible = showKeyboard

        val baseHeight = windowManagerHeightBeforeAttach ?: 0
        val desiredHeight =
            if (showKeyboard) {
                panelPeekHeightPx + baseHeight
            } else {
                panelPeekHeightPx
            }
        windowManager.view.layoutParams?.let { params ->
            if (params.height != desiredHeight && desiredHeight > 0) {
                params.height = desiredHeight
                windowManager.view.layoutParams = params
            }
        }
    }

    private fun resolvePanelPeekHeightPx(baseHeightPx: Int): Int {
        val preferred = context.dp(360)
        val minHeight = context.dp(220)
        if (baseHeightPx <= 0) {
            return preferred
        }
        val maxHeight = (baseHeightPx * 0.86f).toInt().coerceAtLeast(minHeight)
        return preferred.coerceIn(minHeight, maxHeight)
    }

    private fun resolveKeyboardHeightFromPrefsPx(): Int {
        val keyboardPrefs = AppPrefs.getInstance().keyboard
        val percentPref =
            when (context.resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardPrefs.keyboardHeightPercentLandscape
                else -> keyboardPrefs.keyboardHeightPercent
            }
        val percent = percentPref.getValue()
        return context.resources.displayMetrics.heightPixels * percent / 100
    }

    private val embeddedCandidateDockHeightPx by lazy { context.dp(KawaiiBarComponent.HEIGHT) }
    private val embeddedPreeditDockFallbackHeightPx by lazy { context.dp(56) }
    private val embeddedPreeditUi by lazy {
        val keyBorder = ThemeManager.prefs.keyBorder.getValue()
        val bkgColor =
            if (!keyBorder && theme is Theme.Builtin) theme.barColor else theme.backgroundColor
        PreeditUi(context, theme, setupTextView = {
            backgroundColor = bkgColor
            horizontalPadding = context.dp(8)
        }).apply {
            root.alpha = 0.8f
            root.visibility = View.INVISIBLE
        }
    }

    private fun syncEmbeddedCandidateDock(showKeyboard: Boolean) {
        val dockActive = windowAttached && showKeyboard
        if (dockActive) {
            kawaiiBar.setFunctionKitCandidateDock(embeddedCandidateDockContainer)
        } else {
            kawaiiBar.setFunctionKitCandidateDock(null)
        }
        preedit.setFunctionKitSuppressed(dockActive)

        val showCandidates = dockActive && currentCandidateCount > 0
        val showPreedit = dockActive && embeddedPreeditUi.visible

        val candidateHeightPx = if (showCandidates) embeddedCandidateDockHeightPx else 0
        val preeditHeightPx =
            if (showPreedit) {
                val widthPx =
                    (embeddedDockContainer.width.takeIf { it > 0 }
                        ?: windowManager.view.width.takeIf { it > 0 }
                        ?: context.resources.displayMetrics.widthPixels)
                        .coerceAtLeast(1)
                val preeditRoot = embeddedPreeditUi.root
                val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                preeditRoot.measure(widthSpec, heightSpec)
                preeditRoot.measuredHeight.takeIf { it > 0 } ?: embeddedPreeditDockFallbackHeightPx
            } else {
                0
            }

        val dockHeightPx = preeditHeightPx + candidateHeightPx
        val shouldShowDock = dockActive && dockHeightPx > 0

        embeddedDockContainer.isVisible = shouldShowDock
        embeddedDockContainer.updateLayoutParams<LinearLayout.LayoutParams> {
            height = dockHeightPx
        }

        embeddedPreeditDockContainer.isVisible = preeditHeightPx > 0
        embeddedPreeditDockContainer.updateLayoutParams<LinearLayout.LayoutParams> {
            height = preeditHeightPx
        }

        embeddedCandidateDockContainer.isVisible = candidateHeightPx > 0
        embeddedCandidateDockContainer.updateLayoutParams<LinearLayout.LayoutParams> {
            height = candidateHeightPx
        }

        val panelHeightPx = (panelPeekHeightPx - dockHeightPx).coerceAtLeast(0)
        panelContainer.updateLayoutParams<LinearLayout.LayoutParams> {
            height = panelHeightPx
        }
    }

    private fun normalizePresentation(value: String?): String =
        value?.trim()?.lowercase().orEmpty()

    private fun shouldOpenPanel(binding: FunctionKitBindingEntry): Boolean =
        normalizePresentation(binding.preferredPresentation).startsWith("panel")

    private fun handlePaste(text: String) {
        if (ImeBridgeState.isActive()) {
            service.queueImeBridgeCommit(text, FcitxInputMethodService.PendingImeBridgeCommitMode.Insert)
            Toast.makeText(context, R.string.ime_bridge_pending_insert, Toast.LENGTH_SHORT).show()
        } else {
            service.commitText(text)
        }
        windowManager.attachWindow(KeyboardWindow)
    }

    private fun handleBinding(bindingEntry: FunctionKitBindingEntry) {
        FunctionKitBindingSettings.recordUsedNow(bindingEntry.stableId)
        val window = requireFunctionKitWindow(bindingEntry.kitId)
        val openPanel = shouldOpenPanel(bindingEntry)
        val invocationId =
            window.enqueueBindingInvocation(
                binding = bindingEntry,
                trigger = trigger,
                clipboardText =
                    if (trigger == FunctionKitBindingTrigger.Clipboard) {
                        clipboardText ?: ClipboardManager.lastEntry?.text
                    } else {
                        null
                    },
                startHeadless = !openPanel
            )

        if (invocationId == null) {
            windowManager.attachWindow(KeyboardWindow)
            return
        }

        if (openPanel) {
            window.requestOpenInvocation(invocationId, bindingEntry.bindingId, bindingEntry.title)
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
                window.requestOpenInvocation(invocationId, bindingEntry.bindingId, bindingEntry.title)
                windowManager.view.post { windowManager.attachWindow(window) }
            }
        }
    }

    private fun handleOpenDownloadCenter() {
        AppUtil.launchMainToFunctionKitDownloadCenter(context)
        windowManager.attachWindow(KeyboardWindow)
    }

    private fun togglePinned(entry: FunctionKitBindingEntry) {
        val stableId = entry.stableId
        val pinned = FunctionKitBindingSettings.isBindingPinned(stableId)
        FunctionKitBindingSettings.setBindingPinned(stableId, !pinned)
        rebuildRows()
        applyTabUi()
    }

    private val adapter: FunctionKitBindingsAdapter by lazy {
        FunctionKitBindingsAdapter(
            theme = theme,
            accentColor = weChatGreen,
            onClick = { item ->
                when (item) {
                    is FunctionKitBindingCardItem.Binding -> handleBinding(item.entry)
                    is FunctionKitBindingCardItem.LocalPaste -> handlePaste(item.text)
                    FunctionKitBindingCardItem.OpenDownloadCenter -> handleOpenDownloadCenter()
                }
            },
            onPinToggle = ::togglePinned,
            isPinned = { entry -> FunctionKitBindingSettings.isBindingPinned(entry.stableId) }
        )
    }

    private val emptyHint: TextView by lazy {
        TextView(context).apply {
            setTextColor(theme.altKeyTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(10))
            isVisible = false
        }
    }

    private class GridSpacingDecoration(
        private val spanCount: Int,
        private val spacingPx: Int
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) {
                return
            }
            val column = position % spanCount
            val half = spacingPx / 2
            outRect.left = if (column == 0) 0 else half
            outRect.right = if (column == spanCount - 1) 0 else half
            outRect.top = half
            outRect.bottom = half
        }
    }

    private val recyclerView: RecyclerView by lazy {
        RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 2)
            adapter = this@FunctionKitBindingsWindowController.adapter
            clipToPadding = false
            setPadding(context.dp(16), context.dp(0), context.dp(16), context.dp(16))
            addItemDecoration(GridSpacingDecoration(spanCount = 2, spacingPx = context.dp(12)))
        }
    }

    private val contentColumn: LinearLayout by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                emptyHint,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                recyclerView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }
    }

    private val recentTabButton: LinearLayout by lazy {
        createTabButton(
            iconRes = R.drawable.ic_baseline_history_24,
            labelRes = R.string.function_kit_bindings_tab_recent
        ) {
            activeTab = Tab.Recent
            selectedCategoryId = null
            rebuildUi()
        }
    }

    private val pinnedTabButton: LinearLayout by lazy {
        createTabButton(
            iconRes = R.drawable.ic_baseline_star_border_24,
            labelRes = R.string.function_kit_bindings_tab_pinned
        ) {
            activeTab = Tab.Pinned
            selectedCategoryId = null
            rebuildUi()
        }
    }

    private val libraryTabButton: LinearLayout by lazy {
        createTabButton(
            iconRes = R.drawable.ic_baseline_grid_view_24,
            labelRes = R.string.function_kit_bindings_tab_library
        ) {
            activeTab = Tab.Library
            rebuildUi()
        }
    }

    private fun createTabButton(
        iconRes: Int,
        labelRes: Int,
        onClick: () -> Unit
    ): LinearLayout {
        val cornerRadius = context.dp(12).toFloat()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    this.cornerRadius = cornerRadius
                    setColor(Color.TRANSPARENT)
                }
            val icon =
                ImageView(context).apply {
                    id = android.R.id.icon
                    setImageResource(iconRes)
                    setColorFilter(theme.altKeyTextColor)
                }
            val label =
                TextView(context).apply {
                    id = android.R.id.text1
                    text = context.getString(labelRes)
                    setTextColor(theme.altKeyTextColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    includeFontPadding = false
                }
            addView(icon, LinearLayout.LayoutParams(context.dp(16), context.dp(16)).apply { marginEnd = context.dp(6) })
            addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnClickListener { onClick() }
        }
    }

    private val tabRow: LinearLayout by lazy {
        val backgroundDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = context.dp(14).toFloat()
                setColor(theme.keyBackgroundColor)
            }
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = backgroundDrawable
            setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
            addView(recentTabButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pinnedTabButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = context.dp(4)
                marginEnd = context.dp(4)
            })
            addView(libraryTabButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private val categoryRow: LinearLayout by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(context.dp(0), context.dp(10), context.dp(0), context.dp(6))
        }
    }

    private val categoryScrollView: HorizontalScrollView by lazy {
        HorizontalScrollView(context).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                categoryRow,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            isVisible = false
        }
    }

    private val panelHeader: LinearLayout by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(0))
            addView(
                tabRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                categoryScrollView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private val panelBody: LinearLayout by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(panelHeader)
            addView(
                contentColumn,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }
    }

    private val panelContainer: LinearLayout by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.barColor)
            addView(
                panelBody,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    private val embeddedKeyboardContainer: FrameLayout by lazy {
        FrameLayout(context).apply {
            setBackgroundColor(theme.barColor)
            isVisible = false
        }
    }

    private val embeddedPreeditDockContainer: FrameLayout by lazy {
        FrameLayout(context).apply {
            setBackgroundColor(theme.barColor)
            isVisible = false
            addView(
                embeddedPreeditUi.root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private val embeddedCandidateDockContainer: FrameLayout by lazy {
        FrameLayout(context).apply {
            setBackgroundColor(theme.barColor)
            isVisible = false
        }
    }

    private val embeddedDockContainer: LinearLayout by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.barColor)
            isVisible = false
            addView(
                embeddedPreeditDockContainer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0
                )
            )
            addView(
                embeddedCandidateDockContainer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0
                )
            )
        }
    }

    private val rootView: View by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.barColor)
            addView(
                panelContainer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    panelPeekHeightPx.takeIf { it > 0 } ?: context.dp(360)
                )
            )
            addView(
                embeddedDockContainer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0
                )
            )
            addView(
                embeddedKeyboardContainer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }
    }
}
