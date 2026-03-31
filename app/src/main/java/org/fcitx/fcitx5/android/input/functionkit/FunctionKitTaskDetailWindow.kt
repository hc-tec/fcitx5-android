/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.ClipData
import android.content.Context
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

    private val userTitleView: TextView by lazy {
        TextView(context).apply {
            setTextColor(theme.keyTextColor)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        }
    }

    private val userMetaView: TextView by lazy {
        TextView(context).apply {
            setTextColor(theme.altKeyTextColor)
            textSize = 12f
        }
    }

    private val userMessageView: TextView by lazy {
        TextView(context).apply {
            setTextColor(theme.keyTextColor)
            textSize = 13f
        }
    }

    private val developerHeaderView: TextView by lazy {
        TextView(context).apply {
            setTextColor(theme.keyTextColor)
            textSize = 13f
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            visibility = View.GONE
        }
    }

    private val developerJsonView: TextView by lazy {
        TextView(context).apply {
            setTextColor(theme.altKeyTextColor)
            textSize = 12f
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextIsSelectable(true)
            visibility = View.GONE
        }
    }

    private var developerVisible: Boolean = false

    private val contentView: View by lazy {
        val content =
            context.verticalLayout {
                padding = dp(12)
                add(userTitleView, lParams(matchParent, wrapContent))
                add(
                    userMetaView,
                    lParams(matchParent, wrapContent) {
                        topMargin = dp(4)
                    }
                )
                add(
                    userMessageView,
                    lParams(matchParent, wrapContent) {
                        topMargin = dp(8)
                    }
                )
                add(
                    developerHeaderView,
                    lParams(matchParent, wrapContent) {
                        topMargin = dp(12)
                    }
                )
                add(developerJsonView, lParams(matchParent, wrapContent))
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

    private val developerButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_developer_mode_24, theme).apply {
            contentDescription = developerButtonContentDescription()
            setOnClickListener {
                developerVisible = !developerVisible
                applyDeveloperVisibility()
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
            visibility = View.GONE
        }
    }

    private val barExtension: View by lazy {
        context.horizontalLayout {
            add(backButton, lParams(dp(40), dp(40)))
            add(openKitButton, lParams(dp(40), dp(40)))
            add(cancelButton, lParams(dp(40), dp(40)))
            add(developerButton, lParams(dp(40), dp(40)))
            add(copyJsonButton, lParams(dp(40), dp(40)))
        }
    }

    override fun onCreateView(): View = contentView

    override fun onAttached() {
        refreshKitLabels()
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
            userTitleView.text = context.getString(R.string.function_kit_task_center_task_not_found)
            userMetaView.text = ""
            userMessageView.text = ""
            userMessageView.visibility = View.GONE
            developerHeaderView.text = "taskId=$taskId\nstatus=not_found"
            developerJsonView.text = ""
            cancelButton.isEnabled = false
            openKitButton.isEnabled = false
            copyJsonButton.isEnabled = false
            applyDeveloperVisibility()
            return
        }

        val taskTitle = task.optString("title").trim()
        userTitleView.text =
            if (taskTitle.isNotBlank()) {
                taskTitle
            } else {
                kindLabel(context, task.optString("kind").trim())
            }
        val kitId = task.optString("kitId").trim()
        val status = statusLabel(context, task.optString("status").trim())
        val kit =
            kitLabel(kitId).trim().ifBlank { kitId }
        userMetaView.text =
            listOf(kit, status)
                .filter { it.isNotBlank() }
                .joinToString(" · ")

        val summary = summarize(task).trim()
        userMessageView.text = summary
        userMessageView.visibility = if (summary.isBlank()) View.GONE else View.VISIBLE

        developerHeaderView.text = buildDeveloperHeader(task)
        developerJsonView.text = task.toPrettyString()

        openKitButton.isEnabled = kitId.isNotBlank()
        cancelButton.isEnabled = shouldEnableCancel(task)
        copyJsonButton.isEnabled = true
        applyDeveloperVisibility()
    }

    private fun buildDeveloperHeader(task: JSONObject): String {
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

    private fun applyDeveloperVisibility() {
        developerHeaderView.visibility = if (developerVisible) View.VISIBLE else View.GONE
        developerJsonView.visibility = if (developerVisible) View.VISIBLE else View.GONE
        copyJsonButton.visibility = if (developerVisible) View.VISIBLE else View.GONE
        developerButton.contentDescription = developerButtonContentDescription()
    }

    private fun developerButtonContentDescription(): String =
        context.getString(
            if (developerVisible) {
                R.string.function_kit_task_center_hide_developer_info
            } else {
                R.string.function_kit_task_center_show_developer_info
            }
        )

    private fun kindLabel(context: Context, kind: String): String =
        when (kind.trim()) {
            "network.fetch" -> context.getString(R.string.function_kit_task_kind_network_fetch)
            "ai.request" -> context.getString(R.string.function_kit_task_kind_ai_request)
            else -> context.getString(R.string.function_kit_task_kind_generic)
        }

    private fun statusLabel(context: Context, status: String): String =
        when (status.trim()) {
            "queued" -> context.getString(R.string.function_kit_task_status_queued)
            "running" -> context.getString(R.string.function_kit_task_status_running)
            "canceling" -> context.getString(R.string.function_kit_task_status_canceling)
            "succeeded" -> context.getString(R.string.function_kit_task_status_succeeded)
            "failed" -> context.getString(R.string.function_kit_task_status_failed)
            "canceled" -> context.getString(R.string.function_kit_task_status_canceled)
            else -> status
        }

    private fun JSONObject.toPrettyString(): String =
        try {
            toString(2)
        } catch (_: Throwable) {
            toString()
        }
}
