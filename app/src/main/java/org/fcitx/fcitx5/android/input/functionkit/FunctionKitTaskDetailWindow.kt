/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.ClipData
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.json.JSONObject
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
import splitties.views.padding

internal class FunctionKitTaskDetailWindow(
    private val taskId: String
) : InputWindow.ExtendedInputWindow<FunctionKitTaskDetailWindow>() {

    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()

    private val functionKitWindowPool: FunctionKitWindowPool by manager.must()

    private var removeListener: (() -> Unit)? = null

    private val headerView: TextView by lazy {
        TextView(context).apply {
            setTextColor(theme.keyTextColor)
            textSize = 13f
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            padding = dp(12)
        }
    }

    private val jsonView: TextView by lazy {
        TextView(context).apply {
            setTextColor(theme.altKeyTextColor)
            textSize = 12f
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextIsSelectable(true)
            padding = dp(12)
        }
    }

    private val contentView: View by lazy {
        val content =
            context.verticalLayout {
                add(headerView, lParams(matchParent, wrapContent))
                add(jsonView, lParams(matchParent, wrapContent))
            }

        ScrollView(context).apply {
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private val backButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_arrow_back_24, theme).apply {
            contentDescription = context.getString(R.string.back_to_keyboard)
            setOnClickListener {
                windowManager.attachWindow(FunctionKitTaskCenterWindow())
            }
        }
    }

    private val openKitButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_extension_24, theme).apply {
            contentDescription = context.getString(R.string.function_kit)
            setOnClickListener {
                val task = FunctionKitTaskHub.getTaskJson(taskId) ?: return@setOnClickListener
                val kitId = task.optString("kitId").trim()
                if (kitId.isBlank()) return@setOnClickListener
                try {
                    val window = functionKitWindowPool.require(kitId)
                    windowManager.attachWindow(window)
                } catch (error: Throwable) {
                    Toast.makeText(
                        context,
                        error.message ?: context.getString(R.string.function_kit),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private val cancelButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_delete_24, theme).apply {
            contentDescription = context.getString(R.string.function_kit_task_center_cancel)
            setOnClickListener {
                val decision = FunctionKitTaskHub.requestCancel(taskId = taskId)
                val message = decision.message ?: decision.code
                Toast.makeText(context, message ?: context.getString(R.string.done), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val copyJsonButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_code_24, theme).apply {
            contentDescription = context.getString(R.string.function_kit_task_center_copy_json)
            setOnClickListener {
                val json = FunctionKitTaskHub.getTaskJson(taskId)?.toPrettyString() ?: return@setOnClickListener
                context.clipboardManager.setPrimaryClip(ClipData.newPlainText("task.json", json))
                Toast.makeText(context, R.string.done, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val barExtension: View by lazy {
        context.horizontalLayout {
            add(backButton, lParams(dp(40), dp(40)))
            add(openKitButton, lParams(dp(40), dp(40)))
            add(cancelButton, lParams(dp(40), dp(40)))
            add(copyJsonButton, lParams(dp(40), dp(40)))
        }
    }

    override fun onCreateView(): View = contentView

    override fun onAttached() {
        removeListener =
            FunctionKitTaskHub.addListener {
                windowManager.view.post {
                    refresh()
                }
            }
        refresh()
    }

    override fun onDetached() {
        removeListener?.invoke()
        removeListener = null
    }

    override val title: String by lazy {
        context.getString(R.string.function_kit_task_center)
    }

    override fun onCreateBarExtension(): View = barExtension

    private fun refresh() {
        val task = FunctionKitTaskHub.getTaskJson(taskId)
        if (task == null) {
            headerView.text = "taskId=$taskId\nstatus=not_found"
            jsonView.text = ""
            cancelButton.isEnabled = false
            openKitButton.isEnabled = false
            return
        }

        headerView.text = buildHeader(task)
        jsonView.text = task.toPrettyString()

        val kitId = task.optString("kitId").trim()
        openKitButton.isEnabled = kitId.isNotBlank()
        cancelButton.isEnabled = shouldEnableCancel(task)
    }

    private fun buildHeader(task: JSONObject): String {
        val kind = task.optString("kind").trim()
        val status = task.optString("status").trim()
        val kitId = task.optString("kitId").trim()
        val surface = task.optString("surface").trim()
        val updatedAt = task.optString("updatedAt").trim()
        val summary = summarize(task)

        val summaryLine = if (summary.isBlank()) "" else "\nsummary=$summary"
        return "taskId=$taskId\nkind=$kind\nstatus=$status\nkitId=$kitId\nsurface=$surface\nupdatedAt=$updatedAt$summaryLine"
    }

    private fun summarize(task: JSONObject): String {
        val error = task.optJSONObject("error")
        if (error != null) {
            val message = error.optString("message").trim()
            if (message.isNotBlank()) return message
            val code = error.optString("code").trim()
            if (code.isNotBlank()) return code
            return "error"
        }

        val progress = task.optJSONObject("progress")
        if (progress != null) {
            val message = progress.optString("message").trim()
            if (message.isNotBlank()) return message
            val stage = progress.optString("stage").trim()
            if (stage.isNotBlank()) return stage
        }

        val result = task.optJSONObject("result")
        if (result != null) {
            val summary = result.optString("summary").trim()
            if (summary.isNotBlank()) return summary
            val message = result.optString("message").trim()
            if (message.isNotBlank()) return message
        }

        return ""
    }

    private fun shouldEnableCancel(task: JSONObject): Boolean {
        if (!task.optBoolean("cancellable", false)) return false
        val status = task.optString("status").trim()
        return status !in setOf("succeeded", "failed", "canceled")
    }

    private fun JSONObject.toPrettyString(): String =
        try {
            toString(2)
        } catch (_: Throwable) {
            toString()
        }
}
