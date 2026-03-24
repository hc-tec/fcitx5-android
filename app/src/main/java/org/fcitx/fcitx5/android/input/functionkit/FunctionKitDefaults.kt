/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

internal object FunctionKitDefaults {
    const val kitId = "chat-auto-reply"
    const val surface = "panel"
    const val manifestAssetPath = "function-kits/chat-auto-reply/manifest.json"
    const val entryAssetPath = "function-kits/chat-auto-reply/ui/app/index.html"

    val supportedPermissions =
        linkedSetOf(
            "context.read",
            "input.insert",
            "input.replace",
            "candidates.regenerate",
            "settings.open",
            "storage.read",
            "storage.write",
            "panel.state.write",
            "network.fetch",
            "ai.chat",
            "ai.agent.list",
            "ai.agent.run"
        )

    val corePermissions =
        listOf(
            "context.read",
            "input.insert",
            "input.replace",
            "candidates.regenerate",
            "settings.open",
            "storage.read",
            "storage.write",
            "panel.state.write"
        )

    val remotePermissions =
        listOf(
            "network.fetch",
            "ai.chat",
            "ai.agent.list",
            "ai.agent.run"
        )
}
