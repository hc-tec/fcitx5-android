/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

/**
 * Host-level pool to ensure one [FunctionKitWindow]/WebView per kitId across all entry points.
 *
 * Rationale: multiple caches (toolbar/status/bindings) can create multiple WebViews for the same kit, causing
 * runtime state to reset and appear as "state lost" to users.
 */
class FunctionKitWindowPool :
    UniqueComponent<FunctionKitWindowPool>(), Dependent, ManagedHandler by managedHandler() {

    private data class Entry(
        val kitId: String,
        val window: FunctionKitWindow,
        var lastUsedAtEpochMs: Long
    )

    private val context by manager.context()
    private val windowManager: InputWindowManager by manager.must()

    private val entries = mutableMapOf<String, Entry>()

    /**
     * Returns the canonical window instance for [kitId]. Blank/null kitId resolves to the default kit id.
     */
    @Synchronized
    fun require(kitId: String?): FunctionKitWindow {
        val resolvedKitId =
            kitId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: FunctionKitRegistry.resolve(context).id
        FunctionKitKitSettings.recordUsedNow(resolvedKitId)
        val now = System.currentTimeMillis()
        val entry =
            entries.getOrPut(resolvedKitId) {
                Entry(
                    kitId = resolvedKitId,
                    window = FunctionKitWindow(resolvedKitId),
                    lastUsedAtEpochMs = now
                )
            }
        entry.lastUsedAtEpochMs = now
        evictIfNeeded()
        return entry.window
    }

    /**
     * Keep a small number of windows alive. Evict least-recently-used windows that are not currently attached.
     *
     * This is a safety valve. We intentionally avoid calling WebView#destroy() here to minimize coupling.
     */
    @Synchronized
    private fun evictIfNeeded() {
        val maxKept = 6
        if (entries.size <= maxKept) {
            return
        }

        val candidates =
            entries.values
                .filter { entry -> !windowManager.isAttached(entry.window) }
                .sortedBy { it.lastUsedAtEpochMs }

        val targetEvictCount = (entries.size - maxKept).coerceAtLeast(0)
        candidates.take(targetEvictCount).forEach { entry ->
            entries.remove(entry.kitId)
        }
    }
}
