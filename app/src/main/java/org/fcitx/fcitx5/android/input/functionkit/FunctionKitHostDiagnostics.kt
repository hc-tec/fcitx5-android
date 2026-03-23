/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

internal object FunctionKitHostDiagnostics {
    private const val WebViewConsolePrefix = "WebView console["

    fun shouldSurfaceHostEventToUi(message: String): Boolean = !message.startsWith(WebViewConsolePrefix)

    fun shortGitHash(gitHash: String): String = gitHash.trim().takeIf { it.isNotBlank() }?.take(7).orEmpty()

    fun buildDisplayName(
        versionName: String,
        gitHash: String
    ): String =
        shortGitHash(gitHash).takeIf { it.isNotEmpty() }?.let { "$versionName ($it)" } ?: versionName
}
