/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.padding
import splitties.views.recyclerview.verticalLayoutManager

internal class FunctionKitTaskCenterWindow :
    InputWindow.ExtendedInputWindow<FunctionKitTaskCenterWindow>() {

    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()

    private val keyBorder by ThemeManager.prefs.keyBorder

    private var removeListener: (() -> Unit)? = null

    private val kitLabels = LinkedHashMap<String, String>()

    private fun refreshKitLabels() {
        kitLabels.clear()
        FunctionKitRegistry
            .listInstalled(context)
            .forEach { manifest ->
                kitLabels[manifest.id] = FunctionKitRegistry.displayName(context, manifest)
            }
    }

    private fun kitLabel(kitId: String): String = kitLabels[kitId] ?: kitId

    private val adapter: TaskCenterAdapter by lazy {
        TaskCenterAdapter(
            theme = theme,
            kitLabel = ::kitLabel,
            onTaskClick = { taskId ->
                windowManager.attachWindow(FunctionKitTaskDetailWindow(taskId))
            }
        )
    }

    private val view: RecyclerView by lazy {
        context.recyclerView {
            if (!keyBorder) {
                backgroundColor = theme.barColor
            }
            layoutManager = verticalLayoutManager()
            adapter = this@FunctionKitTaskCenterWindow.adapter
        }
    }

    override fun onCreateView(): View = view

    override fun onAttached() {
        refreshKitLabels()
        removeListener =
            FunctionKitTaskHub.addListener {
                windowManager.view.post {
                    render()
                }
            }
        render()
    }

    override fun onDetached() {
        removeListener?.invoke()
        removeListener = null
    }

    override val title: String by lazy {
        context.getString(R.string.function_kit_task_center)
    }

    private fun render() {
        val snapshot = FunctionKitTaskHub.snapshot()
        val rows = mutableListOf<RowItem>()

        rows.add(RowItem.Header(context.getString(R.string.function_kit_task_center_running)))
        if (snapshot.running.isEmpty()) {
            rows.add(RowItem.Empty(context.getString(R.string.function_kit_task_center_empty)))
        } else {
            snapshot.running.forEach { rows.add(RowItem.Task(it)) }
        }

        rows.add(RowItem.Header(context.getString(R.string.function_kit_task_center_history)))
        if (snapshot.history.isEmpty()) {
            rows.add(RowItem.Empty(context.getString(R.string.function_kit_task_center_empty)))
        } else {
            snapshot.history.forEach { rows.add(RowItem.Task(it)) }
        }

        adapter.items = rows
    }

    private sealed class RowItem {
        data class Header(val label: String) : RowItem()
        data class Empty(val label: String) : RowItem()
        data class Task(val preview: FunctionKitTaskHub.TaskPreview) : RowItem()
    }

    private class TaskCenterAdapter(
        private val theme: org.fcitx.fcitx5.android.data.theme.Theme,
        private val kitLabel: (String) -> String,
        private val onTaskClick: (String) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        var items: List<RowItem> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getItemCount(): Int = items.size

        override fun getItemViewType(position: Int): Int =
            when (items[position]) {
                is RowItem.Header -> VIEW_TYPE_HEADER
                is RowItem.Empty -> VIEW_TYPE_EMPTY
                is RowItem.Task -> VIEW_TYPE_TASK
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            when (viewType) {
                VIEW_TYPE_HEADER -> HeaderHolder(createHeaderView(parent))
                VIEW_TYPE_EMPTY -> EmptyHolder(createEmptyView(parent))
                VIEW_TYPE_TASK -> TaskHolder(createTaskView(parent))
                else -> throw IllegalStateException("Unsupported viewType=$viewType")
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is RowItem.Header -> (holder as HeaderHolder).bind(item.label)
                is RowItem.Empty -> (holder as EmptyHolder).bind(item.label)
                is RowItem.Task -> (holder as TaskHolder).bind(item.preview)
            }
        }

        private fun createHeaderView(parent: ViewGroup): TextView =
            TextView(parent.context).apply {
                setTextColor(theme.keyTextColor)
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                padding = dp(12)
            }

        private fun createEmptyView(parent: ViewGroup): TextView =
            TextView(parent.context).apply {
                setTextColor(theme.altKeyTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                padding = dp(12)
            }

        private fun createTaskView(parent: ViewGroup): View =
            LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                padding = dp(12)

                addView(
                    TextView(context).apply {
                        id = android.R.id.text1
                        setTextColor(theme.keyTextColor)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        setTypeface(typeface, Typeface.BOLD)
                    },
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )

                addView(
                    TextView(context).apply {
                        id = android.R.id.text2
                        setTextColor(theme.altKeyTextColor)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(4)
                    }
                )
            }

        private inner class HeaderHolder(private val view: TextView) : RecyclerView.ViewHolder(view) {
            fun bind(label: String) {
                view.text = label
            }
        }

        private inner class EmptyHolder(private val view: TextView) : RecyclerView.ViewHolder(view) {
            fun bind(label: String) {
                view.text = label
            }
        }

        private inner class TaskHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val title = view.findViewById<TextView>(android.R.id.text1)
            private val subtitle = view.findViewById<TextView>(android.R.id.text2)

            init {
                view.setOnClickListener {
                    val item = items.getOrNull(bindingAdapterPosition) as? RowItem.Task ?: return@setOnClickListener
                    onTaskClick(item.preview.taskId)
                }
            }

            fun bind(preview: FunctionKitTaskHub.TaskPreview) {
                title.text = "${preview.kind} (${preview.status})"
                val kit = kitLabel(preview.kitId)
                val meta = if (preview.surface.isBlank()) kit else "$kit / ${preview.surface}"
                subtitle.text =
                    if (preview.summary.isBlank()) {
                        meta
                    } else {
                        "$meta - ${preview.summary}"
                    }
            }
        }

        private companion object {
            private const val VIEW_TYPE_HEADER = 1
            private const val VIEW_TYPE_EMPTY = 2
            private const val VIEW_TYPE_TASK = 3
        }
    }
}
