/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

internal object FunctionKitRuntimePermissionResolver {
    fun resolveSupported(
        manifestDeclared: Collection<String>,
        hostSupported: Collection<String>
    ): List<String> =
        manifestDeclared
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { it in hostSupported }
            .distinct()
            .toList()

    fun resolveRequested(
        uiRequested: Collection<String>,
        supported: Collection<String>,
        fallback: Collection<String> = supported
    ): List<String> {
        val normalizedUiRequested =
            uiRequested
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
        val filtered = normalizedUiRequested.filter { it in supported }.distinct()
        return if (normalizedUiRequested.isEmpty()) fallback.distinct() else filtered
    }
}

