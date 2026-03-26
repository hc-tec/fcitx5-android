/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Future

internal class FunctionKitTaskTracker(
    private val kitId: String,
    private val host: FunctionKitWebViewHost,
    private val maxHistory: Int = 50
) {
    init {
        FunctionKitTaskHub.registerTracker(kitId, this)
    }

    data class CancelDecision(
        val ok: Boolean,
        val code: String? = null,
        val message: String? = null,
        val status: String? = null
    ) {
        fun toJson(taskId: String): JSONObject =
            JSONObject()
                .put("taskId", taskId)
                .put("ok", ok)
                .apply {
                    code?.takeIf { it.isNotBlank() }?.let { put("code", it) }
                    message?.takeIf { it.isNotBlank() }?.let { put("message", it) }
                    status?.takeIf { it.isNotBlank() }?.let { put("status", it) }
                }
    }

    private data class Task(
        val taskId: String,
        val kitId: String,
        val surface: String,
        val kind: String,
        var status: String,
        var createdAt: String,
        var updatedAt: String,
        var seq: Int,
        var cancellable: Boolean,
        var cancelRequested: Boolean,
        var cancelRequestedAt: String?,
        var progress: JSONObject?,
        var result: JSONObject?,
        var error: JSONObject?,
        var future: Future<*>?
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("taskId", taskId)
                .put("kitId", kitId)
                .put("surface", surface)
                .put("kind", kind)
                .put("status", status)
                .put("createdAt", createdAt)
                .put("updatedAt", updatedAt)
                .put("seq", seq)
                .put("cancellable", cancellable)
                .apply {
                    if (cancelRequested) {
                        put("cancelRequested", true)
                        cancelRequestedAt?.takeIf { it.isNotBlank() }?.let { put("cancelRequestedAt", it) }
                    }
                    progress?.let { put("progress", JSONObject(it.toString())) }
                    result?.let { put("result", JSONObject(it.toString())) }
                    error?.let { put("error", JSONObject(it.toString())) }
                }
    }

    private val lock = java.lang.Object()
    private val tasksById = LinkedHashMap<String, Task>()
    private var runningIds = mutableListOf<String>()
    private var historyIds = mutableListOf<String>()

    fun status(taskId: String): String? =
        synchronized(lock) {
            tasksById[taskId]?.status
        }

    fun cancelRequested(taskId: String): Boolean =
        synchronized(lock) {
            tasksById[taskId]?.cancelRequested == true
        }

    fun create(
        kind: String,
        surface: String,
        status: String = "queued",
        cancellable: Boolean = false,
        progress: JSONObject? = null
    ): String {
        val taskId = "t-${UUID.randomUUID()}"
        val now = OffsetDateTime.now().toString()
        val task =
            Task(
                taskId = taskId,
                kitId = kitId,
                surface = surface,
                kind = kind,
                status = status,
                createdAt = now,
                updatedAt = now,
                seq = 1,
                cancellable = cancellable,
                cancelRequested = false,
                cancelRequestedAt = null,
                progress = progress,
                result = null,
                error = null,
                future = null
            )
        synchronized(lock) {
            tasksById[taskId] = task
            trackStatus(taskId, task)
        }
        dispatch(task)
        return taskId
    }

    fun attachFuture(
        taskId: String,
        future: Future<*>
    ) {
        synchronized(lock) {
            tasksById[taskId]?.let { task ->
                task.future = future
                task.cancellable = true
            }
        }
    }

    fun update(
        taskId: String,
        status: String? = null,
        progress: JSONObject? = null,
        result: JSONObject? = null,
        error: JSONObject? = null
    ) {
        val updated =
            synchronized(lock) {
                val task = tasksById[taskId] ?: return
                if (!status.isNullOrBlank()) {
                    task.status = status
                }
                if (progress != null) {
                    task.progress = progress
                }
                if (result != null) {
                    task.result = result
                }
                if (error != null) {
                    task.error = error
                }
                task.seq += 1
                task.updatedAt = OffsetDateTime.now().toString()
                trackStatus(taskId, task)
                task.copy(future = task.future)
            }
        dispatch(updated)
    }

    fun buildSyncPayload(
        surface: String,
        includeHistory: Boolean,
        historyLimit: Int
    ): JSONObject {
        val running = JSONArray()
        val history = JSONArray()

        synchronized(lock) {
            runningIds.forEach { id ->
                tasksById[id]?.takeIf { it.surface == surface }?.let { running.put(it.toJson()) }
            }
            if (includeHistory) {
                val limit = historyLimit.coerceAtLeast(0).coerceAtMost(maxHistory)
                historyIds.take(limit).forEach { id ->
                    tasksById[id]?.takeIf { it.surface == surface }?.let { history.put(it.toJson()) }
                }
            }
        }

        return JSONObject()
            .put("running", running)
            .put("history", history)
    }

    fun requestCancel(
        taskId: String,
        reason: String? = null
    ): CancelDecision {
        val (task, future) =
            synchronized(lock) {
                val task = tasksById[taskId] ?: return CancelDecision(ok = false, code = "task_not_found")
                val terminal = task.status in setOf("succeeded", "failed", "canceled")
                if (terminal) {
                    return CancelDecision(ok = true, status = task.status)
                }
                val now = OffsetDateTime.now().toString()
                task.cancelRequested = true
                task.cancelRequestedAt = now
                task.status = "canceling"
                task.seq += 1
                task.updatedAt = now
                if (!reason.isNullOrBlank()) {
                    task.progress =
                        (task.progress ?: JSONObject())
                            .put("message", "Cancel requested")
                            .put("stage", "cancel")
                }
                trackStatus(taskId, task)
                task.copy(future = task.future) to task.future
            }

        dispatch(task)

        if (future == null) {
            return CancelDecision(ok = false, code = "cancel_not_supported", status = task.status)
        }

        return try {
            val canceled = future.cancel(true)
            if (canceled) {
                update(taskId, status = "canceled")
                CancelDecision(ok = true, status = "canceled")
            } else {
                val code = if (future.isDone) "already_done" else "cancel_failed"
                CancelDecision(ok = false, code = code, status = task.status)
            }
        } catch (error: Throwable) {
            CancelDecision(ok = false, code = "cancel_failed", message = error.message ?: "cancel failed", status = task.status)
        }
    }

    private fun dispatch(task: Task) {
        host.dispatchTaskUpdate(
            kitId = kitId,
            surface = task.surface,
            payload = JSONObject().put("task", task.toJson())
        )
    }

    private fun trackStatus(taskId: String, task: Task) {
        val runningStatuses = setOf("queued", "running", "canceling")
        val terminalStatuses = setOf("succeeded", "failed", "canceled")
        when {
            task.status in runningStatuses -> {
                runningIds = (mutableListOf(taskId) + runningIds.filter { it != taskId }).toMutableList()
                historyIds = historyIds.filter { it != taskId }.toMutableList()
            }
            task.status in terminalStatuses -> {
                runningIds = runningIds.filter { it != taskId }.toMutableList()
                historyIds = (mutableListOf(taskId) + historyIds.filter { it != taskId }).toMutableList()
                if (historyIds.size > maxHistory) {
                    historyIds = historyIds.take(maxHistory).toMutableList()
                }
            }
            else -> {
                runningIds = runningIds.filter { it != taskId }.toMutableList()
            }
        }
    }
}
