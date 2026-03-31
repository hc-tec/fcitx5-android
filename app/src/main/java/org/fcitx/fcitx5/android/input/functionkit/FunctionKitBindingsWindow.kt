/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.ImeBridgeState
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.mechdancer.dependency.manager.must
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.padding
import splitties.views.recyclerview.verticalLayoutManager
import splitties.dimensions.dp

internal class FunctionKitBindingsWindow(
    private val trigger: FunctionKitBindingTrigger = FunctionKitBindingTrigger.Manual,
    private val clipboardText: String? = null
) : InputWindow.ExtendedInputWindow<FunctionKitBindingsWindow>(),
    InputBroadcastReceiver {

    private val theme by manager.theme()
    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()

    private val functionKitWindowPool: FunctionKitWindowPool by manager.must()

    private val keyBorder by ThemeManager.prefs.keyBorder

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

        val selected = selectedCategoryId
        if (!selected.isNullOrBlank() && selected != CATEGORY_OTHER && selected !in orderedCategories) {
            selectedCategoryId = null
        } else if (selected == CATEGORY_OTHER && !hasUncategorizedBindings) {
            selectedCategoryId = null
        }
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

    private fun rebuildCategoryChips() {
        val showCategoryFilters = orderedCategories.isNotEmpty()
        categoryScrollView.visibility = if (showCategoryFilters) View.VISIBLE else View.GONE
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
        if (hasUncategorizedBindings) {
            addCategoryChip(
                label = context.getString(R.string.function_kit_bindings_filter_other),
                categoryId = CATEGORY_OTHER,
                active = selectedCategoryId == CATEGORY_OTHER
            )
        }
    }

    private fun rebuildRows() {
        val rows = mutableListOf<RowItem>()
        if (trigger == FunctionKitBindingTrigger.Clipboard) {
            val text = clipboardText ?: ClipboardManager.lastEntry?.text
            if (!text.isNullOrBlank()) {
                rows += RowItem.LocalPaste
            }
        }
        val filtered = filterBindings()
        if (filtered.isEmpty()) {
            rows += RowItem.Empty(context.getString(R.string.function_kit_bindings_empty))
        } else {
            filtered.forEach { binding -> rows += RowItem.Binding(binding) }
        }
        adapter.items = rows
    }

    private fun rebuildUi() {
        updateCategoryMetadata()
        rebuildCategoryChips()
        rebuildRows()
    }

    override val title: String by lazy {
        when (trigger) {
            FunctionKitBindingTrigger.Clipboard -> context.getString(R.string.function_kit_bindings_clipboard)
            FunctionKitBindingTrigger.Selection -> context.getString(R.string.function_kit_bindings_selection)
            else -> context.getString(R.string.function_kit_bindings)
        }
    }

    private sealed class RowItem {
        data object LocalPaste : RowItem()
        data class Binding(val entry: FunctionKitBindingEntry) : RowItem()
        data class Empty(val label: String) : RowItem()
    }

    private val adapter: BindingsAdapter by lazy {
        BindingsAdapter(
            theme = theme,
            onRowClick = { item ->
                when (item) {
                    is RowItem.LocalPaste -> handlePaste()
                    is RowItem.Binding -> handleBinding(item.entry)
                    is RowItem.Empty -> {}
                }
            }
        )
    }

    private fun handlePaste() {
        val text = clipboardText ?: ClipboardManager.lastEntry?.text
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

    private fun handleBinding(bindingEntry: FunctionKitBindingEntry) {
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

    private val categoryRow: FlexboxLayout by lazy {
        FlexboxLayout(context).apply {
            alignItems = AlignItems.CENTER
            flexWrap = FlexWrap.NOWRAP
            justifyContent = JustifyContent.FLEX_START
            setPadding(dp(12), dp(8), dp(12), dp(4))
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
        }
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
                setColor(if (active) theme.genericActiveBackgroundColor else theme.keyBackgroundColor)
            }
        val maskDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadius = cornerRadius
                setColor(Color.WHITE)
            }
        val view =
            AppCompatTextView(context).apply {
                text = label
                setTextColor(if (active) theme.genericActiveForegroundColor else theme.keyTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                includeFontPadding = false
                maxLines = 1
                setPadding(dp(12), dp(8), dp(12), dp(8))
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
            FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, context.dp(8), 0)
            }
        )
    }

    private val listView: RecyclerView by lazy {
        context.recyclerView {
            layoutManager = verticalLayoutManager()
            adapter = this@FunctionKitBindingsWindow.adapter
        }
    }

    private val rootView: View by lazy {
        context.verticalLayout {
            if (!keyBorder) {
                backgroundColor = theme.barColor
            }
            add(categoryScrollView, lParams(matchParent, wrapContent))
            add(listView, lParams(matchParent, matchParent))
        }
    }

    override fun onCreateView() = rootView

    override fun onAttached() {
        bindings = FunctionKitBindingRegistry.listForTrigger(context, trigger)
        rebuildUi()
    }

    override fun onDetached() {
        // Keep cached Function Kit windows alive so their WebViews preserve runtime state.
    }

    companion object {
        private const val CATEGORY_OTHER = "__other__"
    }

    private class BindingsAdapter(
        private val theme: org.fcitx.fcitx5.android.data.theme.Theme,
        private val onRowClick: (RowItem) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        var items: List<RowItem> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getItemCount(): Int = items.size

        override fun getItemViewType(position: Int): Int =
            when (items[position]) {
                is RowItem.LocalPaste -> VIEW_TYPE_ACTION
                is RowItem.Binding -> VIEW_TYPE_ACTION
                is RowItem.Empty -> VIEW_TYPE_EMPTY
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            when (viewType) {
                VIEW_TYPE_ACTION -> ActionHolder(createActionView(parent))
                VIEW_TYPE_EMPTY -> EmptyHolder(createEmptyView(parent))
                else -> throw IllegalStateException("Unsupported viewType=$viewType")
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            when {
                holder is ActionHolder -> holder.bind(item)
                holder is EmptyHolder -> holder.bind(item as RowItem.Empty)
            }
        }

        private fun createEmptyView(parent: ViewGroup): TextView =
            TextView(parent.context).apply {
                setTextColor(theme.altKeyTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                padding = dp(12)
            }

        private fun createActionView(parent: ViewGroup): View {
            val iconContainer =
                FrameLayout(parent.context).apply {
                    id = android.R.id.background
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(theme.keyBackgroundColor)
                        }
                }
            val iconView =
                ImageView(parent.context).apply {
                    id = android.R.id.icon
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
            iconContainer.addView(
                iconView,
                FrameLayout.LayoutParams(parent.context.dp(22), parent.context.dp(22), Gravity.CENTER)
            )

            val titleView =
                TextView(parent.context).apply {
                    id = android.R.id.text1
                    setTextColor(theme.keyTextColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 2
                }
            val subtitleView =
                TextView(parent.context).apply {
                    id = android.R.id.text2
                    setTextColor(theme.altKeyTextColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    maxLines = 2
                }

            val textColumn =
                LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        titleView,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                    addView(
                        subtitleView,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = dp(2)
                        }
                    )
                }

            val content =
                LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    padding = dp(12)
                    addView(
                        iconContainer,
                        LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                            marginEnd = dp(12)
                        }
                    )
                    addView(
                        textColumn,
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                    )
                }

            return content.apply {
                background = null
                isClickable = true
                foreground =
                    RippleDrawable(
                        ColorStateList.valueOf(theme.keyPressHighlightColor),
                        null,
                        ColorDrawableCompat.mask()
                    )
            }
        }

        private inner class ActionHolder(private val view: View) : RecyclerView.ViewHolder(view) {
            private val iconView: ImageView = view.findViewById(android.R.id.icon)
            private val titleView: TextView = view.findViewById(android.R.id.text1)
            private val subtitleView: TextView = view.findViewById(android.R.id.text2)

            fun bind(item: RowItem) {
                when (item) {
                    is RowItem.LocalPaste -> {
                        titleView.text = view.context.getString(android.R.string.paste)
                        subtitleView.text = view.context.getString(R.string.function_kit_bindings_clipboard)
                        iconView.setImageResource(R.drawable.ic_baseline_content_paste_24)
                        iconView.setColorFilter(theme.altKeyTextColor)
                    }
                    is RowItem.Binding -> {
                        val entry = item.entry
                        titleView.text = entry.title
                        subtitleView.text = buildSubtitle(entry)
                        val icon = FunctionKitIconLoader.loadDrawable(view.context, entry.kitIconAssetPath)
                        if (icon != null) {
                            iconView.clearColorFilter()
                            iconView.setImageDrawable(icon)
                        } else {
                            iconView.setImageResource(R.drawable.ic_baseline_auto_awesome_24)
                            iconView.setColorFilter(theme.altKeyTextColor)
                        }
                    }
                    is RowItem.Empty -> {}
                }
                view.setOnClickListener {
                    onRowClick(item)
                }
            }

            private fun buildSubtitle(entry: FunctionKitBindingEntry): String {
                val parts = mutableListOf(entry.kitLabel)
                val categories =
                    entry.categories
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() }
                        ?.sortedWith(compareBy { it.lowercase() })
                        .orEmpty()
                if (categories.isNotEmpty()) {
                    parts += categories.joinToString(", ")
                }
                return parts.joinToString(" · ")
            }
        }

        private inner class EmptyHolder(private val view: TextView) : RecyclerView.ViewHolder(view) {
            fun bind(item: RowItem.Empty) {
                view.text = item.label
            }
        }

        private object ColorDrawableCompat {
            fun mask(): android.graphics.drawable.Drawable =
                android.graphics.drawable.ColorDrawable(Color.WHITE)
        }

        private companion object {
            private const val VIEW_TYPE_ACTION = 1
            private const val VIEW_TYPE_EMPTY = 2
        }
    }
}
