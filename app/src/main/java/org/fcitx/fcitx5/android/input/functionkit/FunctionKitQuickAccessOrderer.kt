/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

internal object FunctionKitQuickAccessOrderer {
    fun orderKitIds(
        kitIds: List<String>,
        pinnedKitIds: Set<String>,
        lastUsedAtEpochMsByKitId: Map<String, Long>
    ): List<String> {
        val distinct =
            kitIds
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        val indexById = distinct.withIndex().associate { it.value to it.index }

        return distinct.sortedWith(
            compareByDescending<String> { it in pinnedKitIds }
                .thenByDescending { lastUsedAtEpochMsByKitId[it] ?: 0L }
                .thenBy { indexById[it] ?: Int.MAX_VALUE }
        )
    }
}

