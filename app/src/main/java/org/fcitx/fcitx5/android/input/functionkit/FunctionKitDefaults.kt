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
            "input.commitImage",
            "input.observe.best_effort",
            "candidates.regenerate",
            "settings.open",
            "storage.read",
            "storage.write",
            "files.pick",
            "files.download",
            "panel.state.write",
            "runtime.message.send",
            "runtime.message.receive",
            "network.fetch",
            "ai.request",
            "kits.manage",
            "send.intercept.ime_action",
            "ai.agent.list",
            "ai.agent.run"
        )

    val corePermissions =
        listOf(
            "context.read",
            "input.insert",
            "input.replace",
            "input.commitImage",
            "input.observe.best_effort",
            "candidates.regenerate",
            "send.intercept.ime_action",
            "settings.open",
            "storage.read",
            "storage.write",
            "files.pick",
            "panel.state.write",
            "runtime.message.send",
            "runtime.message.receive"
        )

    val remotePermissions =
        listOf(
            "network.fetch",
            "ai.request",
            "ai.agent.list",
            "ai.agent.run"
        )
}
