/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import org.fcitx.fcitx5.android.data.prefs.AppPrefs

internal object FunctionKitPermissionPolicy {
    fun isEnabled(permission: String, prefs: AppPrefs.FunctionKit): Boolean =
        when (permission) {
            "context.read" -> prefs.allowContextRead.getValue()
            "input.insert" -> prefs.allowInputInsert.getValue()
            "input.replace" -> prefs.allowInputReplace.getValue()
            "candidates.regenerate" -> prefs.allowCandidatesRegenerate.getValue()
            "settings.open" -> prefs.allowSettingsOpen.getValue()
            "storage.read" -> prefs.allowStorageRead.getValue()
            "storage.write" -> prefs.allowStorageWrite.getValue()
            "panel.state.write" -> prefs.allowPanelStateWrite.getValue()
            "network.fetch" -> prefs.allowNetworkFetch.getValue()
            "ai.chat" -> prefs.allowAiChat.getValue()
            "ai.chat.status.request" -> prefs.allowAiChat.getValue()
            "ai.agent.list" -> prefs.remoteInferenceEnabled.getValue() && prefs.allowAiAgentAccess.getValue()
            "ai.agent.run" -> prefs.remoteInferenceEnabled.getValue() && prefs.allowAiAgentAccess.getValue()
            "composer.open" -> prefs.allowPanelStateWrite.getValue()
            "composer.focus" -> prefs.allowPanelStateWrite.getValue()
            "composer.update" -> prefs.allowPanelStateWrite.getValue()
            "composer.close" -> prefs.allowPanelStateWrite.getValue()
            "composer.apply.insert" -> prefs.allowInputInsert.getValue()
            "composer.apply.replace" -> prefs.allowInputReplace.getValue()
            "composer.control" -> prefs.allowPanelStateWrite.getValue()
            else -> false
        }

    fun grantedPermissions(
        requestedPermissions: Collection<String>,
        prefs: AppPrefs.FunctionKit
    ): List<String> = requestedPermissions.filter { isEnabled(it, prefs) }
}
