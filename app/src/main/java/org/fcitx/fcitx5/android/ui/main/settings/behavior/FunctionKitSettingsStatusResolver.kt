/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import org.fcitx.fcitx5.android.input.functionkit.FunctionKitDefaults

internal data class FunctionKitPermissionGroupStatus(
    val enabled: Int,
    val total: Int,
    val disabledPermissions: List<String>
) {
    val isRequested: Boolean
        get() = total > 0
}

internal data class FunctionKitSettingsStatus(
    val normalizedRemoteBaseUrl: String,
    val remoteInferenceEnabled: Boolean,
    val remoteAuthConfigured: Boolean,
    val timeoutSeconds: Int,
    val showToolbarButton: Boolean,
    val remoteConfigured: Boolean,
    val remoteUsesLoopback: Boolean,
    val corePermissions: FunctionKitPermissionGroupStatus,
    val remotePermissions: FunctionKitPermissionGroupStatus,
    val composerPermissions: FunctionKitPermissionGroupStatus
)

internal object FunctionKitSettingsStatusResolver {
    fun resolve(
        requestedPermissions: Collection<String>,
        enabledPermissions: Collection<String>,
        remoteInferenceEnabled: Boolean,
        remoteBaseUrl: String,
        remoteAuthToken: String,
        timeoutSeconds: Int,
        showToolbarButton: Boolean
    ): FunctionKitSettingsStatus {
        val requested = requestedPermissions.toSet()
        val enabled = enabledPermissions.toSet()
        val normalizedRemoteBaseUrl = remoteBaseUrl.trim().trimEnd('/')

        return FunctionKitSettingsStatus(
            normalizedRemoteBaseUrl = normalizedRemoteBaseUrl,
            remoteInferenceEnabled = remoteInferenceEnabled,
            remoteAuthConfigured = remoteAuthToken.isNotBlank(),
            timeoutSeconds = timeoutSeconds.coerceAtLeast(1),
            showToolbarButton = showToolbarButton,
            remoteConfigured = remoteInferenceEnabled && normalizedRemoteBaseUrl.isNotBlank(),
            remoteUsesLoopback =
                normalizedRemoteBaseUrl.contains("127.0.0.1")
                        || normalizedRemoteBaseUrl.contains("localhost", ignoreCase = true),
            corePermissions = bucket(requested, enabled, FunctionKitDefaults.corePermissions),
            remotePermissions = bucket(requested, enabled, FunctionKitDefaults.remotePermissions),
            composerPermissions = bucket(requested, enabled, FunctionKitDefaults.composerPermissions)
        )
    }

    private fun bucket(
        requested: Set<String>,
        enabled: Set<String>,
        permissions: List<String>
    ): FunctionKitPermissionGroupStatus {
        val requestedGroup = permissions.filter(requested::contains)
        return FunctionKitPermissionGroupStatus(
            enabled = requestedGroup.count(enabled::contains),
            total = requestedGroup.size,
            disabledPermissions = requestedGroup.filterNot(enabled::contains)
        )
    }
}
