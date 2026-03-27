/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.wm

/**
 * One-shot "resume" request for the next time the IME window becomes active.
 *
 * Motivation:
 * - External activities (e.g. document picker) often dismiss the IME.
 * - When the user returns, the IME historically resets to the default keyboard window.
 * - Some flows (file picking, clipboard actions, etc.) want to restore a specific IME window.
 *
 * Design:
 * - Single pending request (best-effort) with TTL.
 * - Optional packageName guard to avoid resuming into the wrong app.
 */
internal object ImeWindowResumeManager {

    internal sealed class Request(
        open val source: String,
        open val expectedPackageName: String?,
        open val createdAtEpochMs: Long,
        open val ttlMs: Long
    ) {
        data class FunctionKit(
            val kitId: String,
            override val source: String,
            override val expectedPackageName: String? = null,
            override val createdAtEpochMs: Long = System.currentTimeMillis(),
            override val ttlMs: Long = DefaultTtlMs
        ) : Request(
            source = source,
            expectedPackageName = expectedPackageName,
            createdAtEpochMs = createdAtEpochMs,
            ttlMs = ttlMs
        )

        data class FunctionKitBindings(
            val clipboardText: String,
            override val source: String,
            override val expectedPackageName: String? = null,
            override val createdAtEpochMs: Long = System.currentTimeMillis(),
            override val ttlMs: Long = DefaultTtlMs
        ) : Request(
            source = source,
            expectedPackageName = expectedPackageName,
            createdAtEpochMs = createdAtEpochMs,
            ttlMs = ttlMs
        )
    }

    private const val DefaultTtlMs = 60_000L

    private var pending: Request? = null

    @Synchronized
    fun schedule(request: Request) {
        pending = request
    }

    @Synchronized
    fun clearIfSource(source: String) {
        if (pending?.source == source) {
            pending = null
        }
    }

    @Synchronized
    fun consume(currentPackageName: String?): Request? {
        val request = pending ?: return null
        pending = null

        val now = System.currentTimeMillis()
        if (now - request.createdAtEpochMs > request.ttlMs) {
            return null
        }

        val expected = request.expectedPackageName?.trim().orEmpty()
        val current = currentPackageName?.trim().orEmpty()
        if (expected.isNotEmpty() && expected != current) {
            return null
        }

        return request
    }
}

