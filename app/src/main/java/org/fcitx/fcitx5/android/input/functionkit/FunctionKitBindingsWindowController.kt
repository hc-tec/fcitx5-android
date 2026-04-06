/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.horizontalPadding
import kotlin.math.abs

private const val DownloadCenterKitId = "kit-store"
private const val SearchCursorChar = "│"
private const val CursorBlinkIntervalMs = 530L

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

    private val weChatGreen: Int = 0xFF129C67.toInt()
    private val uiSelectionColor: Int = (weChatGreen and 0x00FFFFFF) or 0x33000000

    private val uiBackgroundColor: Int = 0xFFF3F5F7.toInt()
    private val uiSurfaceColor: Int = Color.WHITE
    private val uiSurfaceMutedColor: Int = 0xFFECF0F3.toInt()
    private val uiSurfaceBorderColor: Int = 0xFFDCE3E8.toInt()
    private val uiControlActiveColor: Int = 0xFFE5F3EC.toInt()
    private val uiCardColor: Int = uiSurfaceColor
    private val uiCardBorderColor: Int = 0xFFE3E8ED.toInt()
    private val uiAccentSoftColor: Int = 0xFFE6F6EE.toInt()
    private val uiTextPrimaryColor: Int = 0xFF10161D.toInt()
    private val uiTextSecondaryColor: Int = 0xFF66727F.toInt()
    private val uiTextTertiaryColor: Int = 0xFF8A97A3.toInt()

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
    private var embeddedExpandedCandidateHeightPx: Int = 0
    private var cursorVisible: Boolean = false

    private val cursorBlinkRunnable =
        object : Runnable {
            override fun run() {
                if (!windowAttached || !searchFocused) {
                    cursorVisible = false
                    return
                }
                cursorVisible = !cursorVisible
                updateSearchBarUi(rebuildRows = false)
                windowManager.view.postDelayed(this, CursorBlinkIntervalMs)
            }
        }

    private var removeBindingSettingsListener: (() -> Unit)? = null

    private fun roundedDrawable(
        color: Int,
        cornerDp: Int,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 1
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dp(cornerDp).toFloat()
        setColor(color)
        if (strokeColor != null) {
            setStroke(context.dp(strokeWidthDp), strokeColor)
        }
    }

    private fun topSheetDrawable() =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii =
                floatArrayOf(
                    context.dp(24).toFloat(),
                    context.dp(24).toFloat(),
                    context.dp(24).toFloat(),
                    context.dp(24).toFloat(),
                    0f,
                    0f,
                    0f,
                    0f
                )
            setColor(uiBackgroundColor)
        }

    private fun pillMask(cornerDp: Int) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(cornerDp).toFloat()
            setColor(Color.WHITE)
        }

    private fun requireFunctionKitWindow(kitId: String): FunctionKitWindow =
        windowPool.require(kitId)

    fun onCreateBarExtension(): View = barExtension

    fun onCreateView(): View {
        val baseHeight = resolveKeyboardBaseHeightPx()
        panelPeekHeightPx = resolvePanelPeekHeightPx(baseHeight)
        return rootView
    }

    fun onAttached() {
        windowAttached = true

        val baseHeight = resolveKeyboardBaseHeightPx()
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
        setEmbeddedExpandedCandidateHeight(0)

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
        if (focused) {
            cursorVisible = true
            windowManager.view.removeCallbacks(cursorBlinkRunnable)
            windowManager.view.postDelayed(cursorBlinkRunnable, CursorBlinkIntervalMs)
        } else {
            cursorVisible = false
            windowManager.view.removeCallbacks(cursorBlinkRunnable)
        }
        updateSearchBarUi(rebuildRows = false)
        syncEmbeddedKeyboardLayout()
    }

    private fun updateSearchBarUi(rebuildRows: Boolean = true) {
        val normalizedSearch = FunctionKitComposerDraftBuffer.normalize(searchDraft)
        val rawText = normalizedSearch.text
        val query = rawText.trim()
        val placeholder = context.getString(R.string.function_kit_bindings_search_placeholder)
        if (searchFocused) {
            val sb = SpannableStringBuilder()
            if (query.isBlank()) {
                if (cursorVisible) {
                    val start = sb.length
                    sb.append(SearchCursorChar)
                    sb.setSpan(
                        ForegroundColorSpan(weChatGreen),
                        start,
                        sb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                val hintStart = sb.length
                sb.append(placeholder)
                sb.setSpan(
                    ForegroundColorSpan(uiTextSecondaryColor),
                    hintStart,
                    sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else {
                sb.append(rawText)
                val selectionStart = normalizedSearch.selectionStart.coerceIn(0, rawText.length)
                val selectionEnd = normalizedSearch.selectionEnd.coerceIn(0, rawText.length)
                if (selectionStart != selectionEnd) {
                    sb.setSpan(
                        BackgroundColorSpan(uiSelectionColor),
                        selectionStart,
                        selectionEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                if (cursorVisible) {
                    val caretIndex = selectionEnd
                    sb.insert(caretIndex, SearchCursorChar)
                    sb.setSpan(
                        ForegroundColorSpan(weChatGreen),
                        caretIndex,
                        caretIndex + SearchCursorChar.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            searchTextView.text = sb
            searchTextView.setTextColor(uiTextPrimaryColor)
        } else {
            val showPlaceholder = query.isBlank()
            searchTextView.text = if (showPlaceholder) placeholder else query
            searchTextView.setTextColor(
                if (showPlaceholder) uiTextSecondaryColor else uiTextPrimaryColor
            )
        }
        clearSearchButton.isVisible = query.isNotBlank()
        val borderColor = if (searchFocused) weChatGreen else uiSurfaceBorderColor
        val fieldColor = if (searchFocused) uiAccentSoftColor else uiSurfaceColor
        (searchField.background as? GradientDrawable)?.setStroke(context.dp(1), borderColor)
        (searchField.background as? GradientDrawable)?.setColor(fieldColor)
        searchIconView.setColorFilter(if (searchFocused) weChatGreen else uiTextSecondaryColor)
        clearSearchButton.setColorFilter(if (searchFocused) weChatGreen else uiTextSecondaryColor)

        if (rebuildRows) {
            rebuildRows()
        }
    }

    private fun updateCategoryMetadata() {
        val counts = mutableMapOf<String, Int>()
        val firstSeenOrder = mutableMapOf<String, Int>()
        var nextOrder = 0
        for (binding in bindings) {
            val categories = binding.categories?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
            for (category in categories) {
                if (category !in firstSeenOrder) {
                    firstSeenOrder[category] = nextOrder++
                }
                counts[category] = (counts[category] ?: 0) + 1
            }
        }

        val sorted =
            counts.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Int>> { it.value }
                        .thenBy { firstSeenOrder[it.key] ?: Int.MAX_VALUE }
                        .thenBy { it.key.lowercase() }
                )
                .map { it.key }
        orderedCategories = sorted.take(6)

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
        val backgroundDrawable =
            roundedDrawable(
                color = if (active) uiAccentSoftColor else uiSurfaceColor,
                cornerDp = 999,
                strokeColor = if (active) weChatGreen else uiSurfaceBorderColor
            )
        val view =
            TextView(context).apply {
                text = label
                setTextColor(if (active) weChatGreen else uiTextSecondaryColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                includeFontPadding = false
                maxLines = 1
                setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
                background = backgroundDrawable
                foreground =
                    RippleDrawable(
                        ColorStateList.valueOf(theme.keyPressHighlightColor),
                        null,
                        pillMask(cornerDp = 999)
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
            if (active) uiControlActiveColor else Color.TRANSPARENT
        )
        val icon = button.findViewById<ImageView>(android.R.id.icon)
        val label = button.findViewById<TextView>(android.R.id.text1)
        val iconColor = if (active) weChatGreen else uiTextSecondaryColor
        val textColor = if (active) uiTextPrimaryColor else uiTextSecondaryColor
        icon.setColorFilter(iconColor)
        label.setTextColor(textColor)
        button.elevation = if (active) context.dp(1).toFloat() else 0f
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
            setColorFilter(uiTextPrimaryColor)
            background = roundedDrawable(uiSurfaceColor, cornerDp = 999, strokeColor = uiSurfaceBorderColor)
            elevation = context.dp(1).toFloat()
            foreground =
                RippleDrawable(
                    ColorStateList.valueOf(theme.keyPressHighlightColor),
                    null,
                    pillMask(cornerDp = 999)
                )
            setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
            setOnClickListener {
                windowManager.attachWindow(KeyboardWindow)
            }
        }
    }

    private val searchIconView: ImageView by lazy {
        ImageView(context).apply {
            setImageResource(R.drawable.ic_baseline_search_24)
            setColorFilter(uiTextSecondaryColor)
        }
    }

    private val searchTextView: TextView by lazy {
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            includeFontPadding = false
            setTextColor(uiTextSecondaryColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    private val clearSearchButton: ImageView by lazy {
        ImageView(context).apply {
            setImageResource(R.drawable.ic_baseline_close_24)
            setColorFilter(uiTextSecondaryColor)
            isVisible = false
            background = roundedDrawable(uiSurfaceMutedColor, cornerDp = 999)
            foreground =
                RippleDrawable(
                    ColorStateList.valueOf(theme.keyPressHighlightColor),
                    null,
                    pillMask(cornerDp = 999)
                )
            setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
            setOnClickListener {
                searchDraft = ComposerDraftBufferState()
                updateSearchBarUi()
            }
        }
    }

    private val searchField: FrameLayout by lazy {
        val backgroundDrawable =
            roundedDrawable(
                color = uiSurfaceColor,
                cornerDp = 14,
                strokeColor = uiSurfaceBorderColor
            )

        FrameLayout(context).apply {
            background = backgroundDrawable
            elevation = context.dp(1).toFloat()
            foreground =
                RippleDrawable(
                    ColorStateList.valueOf(theme.keyPressHighlightColor),
                    null,
                    pillMask(cornerDp = 14)
                )
            setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
            addView(
                searchIconView,
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
                    marginEnd = context.dp(30)
                }
            )
            addView(
                clearSearchButton,
                FrameLayout.LayoutParams(context.dp(24), context.dp(24), Gravity.END or Gravity.CENTER_VERTICAL)
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
            setBackgroundColor(uiBackgroundColor)
            setPadding(context.dp(8), context.dp(2), context.dp(8), context.dp(2))
            addView(
                backButton,
                LinearLayout.LayoutParams(context.dp(36), context.dp(36))
            )
            addView(
                searchField,
                LinearLayout.LayoutParams(
                    0,
                    context.dp(36),
                    1f
                ).apply {
                    marginStart = context.dp(8)
                }
            )
        }
    }

    private val sheetHandle: View by lazy {
        View(context).apply {
            background = roundedDrawable(uiSurfaceBorderColor, cornerDp = 999)
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
            }.coerceAtMost(resolveWindowHeightCapPx())
        windowManager.view.layoutParams?.let { params ->
            if (params.height != desiredHeight && desiredHeight > 0) {
                params.height = desiredHeight
                windowManager.view.layoutParams = params
            }
        }
    }

    private fun resolvePanelPeekHeightPx(baseHeightPx: Int): Int {
        val orientation = context.resources.configuration.orientation
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val screenHeightPx = context.resources.displayMetrics.heightPixels
        val heightReduction = context.dp(50)
        val preferred = if (isLandscape) context.dp(270) else context.dp(346)
        val minHeight = if (isLandscape) context.dp(182) else context.dp(206)
        val maxHeight =
            (screenHeightPx * if (isLandscape) 0.7f else 0.54f)
                .toInt()
                .coerceAtLeast(minHeight)
        val boostedBaseHeight =
            if (baseHeightPx > 0) {
                ((baseHeightPx * 1.08f).toInt() - heightReduction).coerceAtLeast(minHeight)
            } else {
                preferred
            }
        return maxOf(preferred, boostedBaseHeight).coerceIn(minHeight, maxHeight)
    }

    private fun resolveWindowHeightCapPx(): Int =
        (context.resources.displayMetrics.heightPixels * 0.96f)
            .toInt()
            .coerceAtLeast(panelPeekHeightPx)

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

    private fun resolveKeyboardBaseHeightPx(): Int {
        val prefHeight = resolveKeyboardHeightFromPrefsPx()
        if (prefHeight > 0) {
            return prefHeight
        }
        return windowManager.view.layoutParams?.height ?: 0
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
        val expandedHeightPx = if (dockActive) embeddedExpandedCandidateHeightPx else 0

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

        val dockHeightPx = preeditHeightPx + candidateHeightPx + expandedHeightPx
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

        embeddedExpandedCandidateDockContainer.isVisible = expandedHeightPx > 0
        embeddedExpandedCandidateDockContainer.updateLayoutParams<LinearLayout.LayoutParams> {
            height = expandedHeightPx
        }

        val panelHeightPx = (panelPeekHeightPx - dockHeightPx).coerceAtLeast(0)
        panelContainer.updateLayoutParams<LinearLayout.LayoutParams> {
            height = panelHeightPx
        }
    }

    fun getEmbeddedExpandedCandidateContainer(): ViewGroup = embeddedExpandedCandidateDockContainer

    fun setEmbeddedExpandedCandidateHeight(heightPx: Int) {
        val normalizedHeight = heightPx.coerceAtLeast(0)
        if (embeddedExpandedCandidateHeightPx == normalizedHeight) {
            return
        }
        embeddedExpandedCandidateHeightPx = normalizedHeight
        syncEmbeddedCandidateDock(shouldShowEmbeddedKeyboard())
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
        val window = requireFunctionKitWindow(DownloadCenterKitId)
        windowManager.view.post { windowManager.attachWindow(window) }
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
            cardBackgroundColor = uiCardColor,
            cardBorderColor = uiCardBorderColor,
            iconSurfaceColor = uiSurfaceColor,
            mutedSurfaceColor = uiSurfaceMutedColor,
            accentSoftColor = uiAccentSoftColor,
            primaryTextColor = uiTextPrimaryColor,
            secondaryTextColor = uiTextSecondaryColor,
            tertiaryTextColor = uiTextTertiaryColor,
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
            setTextColor(uiTextSecondaryColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            includeFontPadding = false
            gravity = Gravity.CENTER
            background = roundedDrawable(uiSurfaceColor, cornerDp = 18, strokeColor = uiSurfaceBorderColor)
            elevation = context.dp(1).toFloat()
            setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
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
            val layoutParams = view.layoutParams as? GridLayoutManager.LayoutParams
            val spanSize = layoutParams?.spanSize ?: 1
            val spanIndex = layoutParams?.spanIndex ?: 0
            val half = spacingPx / 2
            if (spanSize >= spanCount) {
                outRect.left = 0
                outRect.right = 0
            } else {
                outRect.left = if (spanIndex == 0) 0 else half
                outRect.right = if (spanIndex == spanCount - 1) 0 else half
            }
            outRect.top = half
            outRect.bottom = half
        }
    }

    private val recyclerView: RecyclerView by lazy {
        RecyclerView(context).apply {
            val gridLayoutManager = GridLayoutManager(context, 2)
            gridLayoutManager.spanSizeLookup =
                object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        val count = this@FunctionKitBindingsWindowController.adapter.itemCount
                        if (count <= 0) return 1
                        val spanCount = gridLayoutManager.spanCount.coerceAtLeast(1)
                        val isLast = position == count - 1
                        val hasSingleInLastRow = count % spanCount != 0
                        return if (isLast && hasSingleInLastRow) spanCount else 1
                    }
                }
            layoutManager = gridLayoutManager
            adapter = this@FunctionKitBindingsWindowController.adapter
            clipToPadding = false
            setPadding(context.dp(16), context.dp(4), context.dp(16), context.dp(16))
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
                ).apply {
                    marginStart = context.dp(16)
                    topMargin = context.dp(2)
                    marginEnd = context.dp(16)
                    bottomMargin = context.dp(12)
                }
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

    private val filterCard: LinearLayout by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(uiSurfaceColor, cornerDp = 20, strokeColor = uiSurfaceBorderColor)
            elevation = context.dp(1).toFloat()
            setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(8))
            addView(
                tabRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.dp(38)
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
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedDrawable(Color.TRANSPARENT, cornerDp = 999)
            foreground =
                RippleDrawable(
                    ColorStateList.valueOf(theme.keyPressHighlightColor),
                    null,
                    pillMask(cornerDp = 999)
                )
            setPadding(context.dp(12), context.dp(7), context.dp(12), context.dp(7))
            val icon =
                ImageView(context).apply {
                    id = android.R.id.icon
                    setImageResource(iconRes)
                    setColorFilter(uiTextSecondaryColor)
                }
            val label =
                TextView(context).apply {
                    id = android.R.id.text1
                    text = context.getString(labelRes)
                    setTextColor(uiTextSecondaryColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    includeFontPadding = false
                }
            addView(icon, LinearLayout.LayoutParams(context.dp(16), context.dp(16)).apply { marginEnd = context.dp(6) })
            addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnClickListener { onClick() }
        }
    }

    private val tabRow: LinearLayout by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedDrawable(uiSurfaceMutedColor, cornerDp = 999)
            setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
            minimumHeight = context.dp(38)
            addView(recentTabButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(pinnedTabButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = context.dp(4)
                marginEnd = context.dp(4)
            })
            addView(libraryTabButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private val categoryRow: LinearLayout by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(context.dp(0), context.dp(8), context.dp(0), context.dp(2))
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
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(context.dp(16), context.dp(6), context.dp(16), context.dp(0))
            addView(
                sheetHandle,
                LinearLayout.LayoutParams(
                    context.dp(38),
                    context.dp(4)
                )
            )
            addView(
                filterCard,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = context.dp(8)
                }
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
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        object : LinearLayout(context) {
            private var tapStartX = 0f
            private var tapStartY = 0f
            private var maybeTap = false

            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                val focused = searchFocused
                if (focused) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            tapStartX = event.x
                            tapStartY = event.y
                            maybeTap = true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (maybeTap &&
                                (abs(event.x - tapStartX) > touchSlop ||
                                    abs(event.y - tapStartY) > touchSlop)
                            ) {
                                maybeTap = false
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            maybeTap = false
                        }
                        MotionEvent.ACTION_UP -> {
                            val tap = maybeTap
                            maybeTap = false
                            val handled = super.dispatchTouchEvent(event)
                            if (tap) {
                                post { setSearchFocused(false) }
                            }
                            return handled
                        }
                    }
                }
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = topSheetDrawable()
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

    private val embeddedExpandedCandidateDockContainer: FrameLayout by lazy {
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
            addView(
                embeddedExpandedCandidateDockContainer,
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
            setBackgroundColor(uiBackgroundColor)
            addView(
                panelContainer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    panelPeekHeightPx.takeIf { it > 0 }
                        ?: resolvePanelPeekHeightPx(windowManager.view.layoutParams?.height ?: 0)
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
