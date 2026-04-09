/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.appContext

internal object FunctionKitPermissionPolicy {
    private val PrivilegedKitIds = setOf("kit-store")
    private val PrivilegedPermissions = setOf("kits.manage", "files.download")

    fun isEnabled(permission: String, prefs: AppPrefs.FunctionKit): Boolean =
        when (permission) {
            "context.read" -> prefs.allowContextRead.getValue()
            "input.insert" -> prefs.allowInputInsert.getValue()
            "input.replace" -> prefs.allowInputReplace.getValue()
            "input.commitImage" -> prefs.allowInputCommitImage.getValue()
            "input.observe.best_effort" -> prefs.allowInputObserveBestEffort.getValue()
            "candidates.regenerate" -> prefs.allowCandidatesRegenerate.getValue()
            "settings.open" -> prefs.allowSettingsOpen.getValue()
            "storage.read" -> prefs.allowStorageRead.getValue()
            "storage.write" -> prefs.allowStorageWrite.getValue()
            "files.pick" -> prefs.allowFilesPick.getValue()
            "files.download" -> false
            "panel.state.write" -> prefs.allowPanelStateWrite.getValue()
            "runtime.message.send" -> prefs.allowRuntimeMessageSend.getValue()
            "runtime.message.receive" -> prefs.allowRuntimeMessageReceive.getValue()
            "network.fetch" -> prefs.allowNetworkFetch.getValue()
            "ai.request" -> prefs.allowAiChat.getValue()
            "kits.manage" -> false
            "send.intercept.ime_action" -> prefs.allowSendInterceptImeAction.getValue()
            "ai.agent.list" -> prefs.remoteInferenceEnabled.getValue() && prefs.allowAiAgentAccess.getValue()
            "ai.agent.run" -> prefs.remoteInferenceEnabled.getValue() && prefs.allowAiAgentAccess.getValue()
            else -> false
        }

    fun defaultEnabled(
        permission: String,
        prefs: AppPrefs.FunctionKit,
        kitId: String?
    ): Boolean {
        if (kitId.isNullOrBlank()) {
            return isEnabled(permission, prefs)
        }
        return when (permission) {
            in PrivilegedPermissions ->
                kitId in PrivilegedKitIds && !FunctionKitPackageManager.isUserInstalled(appContext, kitId)
            else -> isEnabled(permission, prefs)
        }
    }

    fun isEnabled(
        permission: String,
        prefs: AppPrefs.FunctionKit,
        kitId: String?
    ): Boolean {
        if (kitId.isNullOrBlank()) {
            return isEnabled(permission, prefs)
        }
        if (!FunctionKitKitSettings.isKitEnabled(kitId)) {
            return false
        }
        val override = FunctionKitKitSettings.getPermissionOverride(kitId, permission)
        val defaultValueEnabled = defaultEnabled(permission, prefs, kitId)
        val baseEnabled = override ?: defaultValueEnabled
        // Some capabilities still depend on host-level routing/config, so per-kit overrides should
        // not bypass those constraints.
        return when (permission) {
            "ai.agent.list", "ai.agent.run" -> baseEnabled && prefs.remoteInferenceEnabled.getValue()
            else -> baseEnabled
        }
    }

    fun grantedPermissions(
        requestedPermissions: Collection<String>,
        prefs: AppPrefs.FunctionKit,
        kitId: String? = null
    ): List<String> = requestedPermissions.filter { isEnabled(it, prefs, kitId) }
}
