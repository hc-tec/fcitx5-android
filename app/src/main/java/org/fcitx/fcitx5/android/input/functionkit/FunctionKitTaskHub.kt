/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Host-level task aggregator for IME Task Center.
 *
 * Tasks are produced by per-kit [FunctionKitTaskTracker] and dispatched through
 * [FunctionKitWebViewHost.dispatchTaskUpdate]. TaskHub keeps a cross-kit view by
 * merging updates using (taskId + seq).
 */
internal object FunctionKitTaskHub {
    data class TaskPreview(
        val taskId: String,
        val kitId: String,
        val surface: String,
        val kind: String,
        val title: String,
        val status: String,
        val updatedAt: String,
        val cancellable: Boolean,
        val summary: String
    )

    data class Snapshot(
        val running: List<TaskPreview>,
        val history: List<TaskPreview>
    )

    private const val MaxHistory = 200

    private val lock = Any()
    private val tasksById = LinkedHashMap<String, JSONObject>()
    private var runningIds = mutableListOf<String>()
    private var historyIds = mutableListOf<String>()
    private val trackerByKitId = LinkedHashMap<String, WeakReference<FunctionKitTaskTracker>>()

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun registerTracker(
        kitId: String,
        tracker: FunctionKitTaskTracker
    ) {
        synchronized(lock) {
            trackerByKitId[kitId] = WeakReference(tracker)
        }
    }

    fun recordTaskUpdate(
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        val task = payload.optJSONObject("task") ?: return
        val taskId = task.optString("taskId").trim()
        if (taskId.isBlank()) return

        val merged =
            synchronized(lock) {
                val existing = tasksById[taskId]
                val incomingSeq = task.optInt("seq", 0)
                val existingSeq = existing?.optInt("seq", 0) ?: 0
                if (incomingSeq <= existingSeq) {
                    return@synchronized false
                }

                // Keep a detached copy.
                val normalized =
                    JSONObject(task.toString())
                        .apply {
                            if (optString("kitId").isBlank()) put("kitId", kitId)
                            if (optString("surface").isBlank()) put("surface", surface)
                        }

                tasksById[taskId] = normalized
                trackStatus(taskId, normalized)
                true
            }

        if (merged) {
            listeners.forEach { it.invoke() }
        }
    }

    fun snapshot(): Snapshot =
        synchronized(lock) {
            Snapshot(
                running = runningIds.mapNotNull { id -> tasksById[id]?.let(::toPreview) },
                history = historyIds.mapNotNull { id -> tasksById[id]?.let(::toPreview) }
            )
        }

    fun getTaskJson(taskId: String): JSONObject? =
        synchronized(lock) {
            tasksById[taskId]?.let { JSONObject(it.toString()) }
        }

    fun requestCancel(
        taskId: String,
        reason: String? = null
    ): FunctionKitTaskTracker.CancelDecision {
        val kitId =
            synchronized(lock) {
                tasksById[taskId]?.optString("kitId").orEmpty().trim()
            }

        if (kitId.isBlank()) {
            return FunctionKitTaskTracker.CancelDecision(ok = false, code = "task_not_found")
        }

        val tracker =
            synchronized(lock) {
                val ref = trackerByKitId[kitId] ?: return@synchronized null
                val tracker = ref.get()
                if (tracker == null) {
                    trackerByKitId.remove(kitId)
                }
                tracker
            }

        if (tracker == null) {
            return FunctionKitTaskTracker.CancelDecision(ok = false, code = "cancel_not_supported")
        }

        return tracker.requestCancel(taskId = taskId, reason = reason)
    }

    internal fun resetForTest() {
        synchronized(lock) {
            tasksById.clear()
            runningIds.clear()
            historyIds.clear()
            trackerByKitId.clear()
        }
    }

    private fun toPreview(task: JSONObject): TaskPreview {
        val taskId = task.optString("taskId").trim()
        val kitId = task.optString("kitId").trim()
        val surface = task.optString("surface").trim()
        val kind = task.optString("kind").trim()
        val title = task.optString("title").trim()
        val status = task.optString("status").trim()
        val updatedAt = task.optString("updatedAt").trim()
        val cancellable = task.optBoolean("cancellable", false)
        val summary = summarize(task)
        return TaskPreview(
            taskId = taskId,
            kitId = kitId,
            surface = surface,
            kind = kind,
            title = title,
            status = status,
            updatedAt = updatedAt,
            cancellable = cancellable,
            summary = summary
        )
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

    private fun trackStatus(taskId: String, task: JSONObject) {
        val status = task.optString("status").trim()
        val terminalStatuses = setOf("succeeded", "failed", "canceled")

        if (status in terminalStatuses) {
            runningIds = runningIds.filter { it != taskId }.toMutableList()
            historyIds = (mutableListOf(taskId) + historyIds.filter { it != taskId }).toMutableList()
            if (historyIds.size > MaxHistory) {
                val dropped = historyIds.drop(MaxHistory)
                historyIds = historyIds.take(MaxHistory).toMutableList()
                dropped.forEach { id ->
                    if (id !in runningIds) {
                        tasksById.remove(id)
                    }
                }
            }
            return
        }

        // Default to running if status is unknown, so users can still find it in Task Center.
        runningIds = (mutableListOf(taskId) + runningIds.filter { it != taskId }).toMutableList()
        historyIds = historyIds.filter { it != taskId }.toMutableList()
    }
}
