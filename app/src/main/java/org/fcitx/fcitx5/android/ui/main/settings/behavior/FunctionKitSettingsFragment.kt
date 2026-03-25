/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitDefaults
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitPermissionPolicy
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitRegistry
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.utils.setup

class FunctionKitSettingsFragment :
    ManagedPreferenceFragment(AppPrefs.getInstance().functionKit) {

    private val functionKitPrefs = AppPrefs.getInstance().functionKit
    private val keyboardPrefs = AppPrefs.getInstance().keyboard
    private val functionKitManifest by lazy(LazyThreadSafetyMode.NONE) {
        FunctionKitRegistry.resolve(requireContext())
    }

    private lateinit var runtimePreference: Preference
    private lateinit var remoteRoutingPreference: Preference
    private lateinit var quickAccessPreference: Preference
    private lateinit var toolbarExpandPreference: MySwitchPreference
    private lateinit var localPermissionsPreference: Preference
    private lateinit var remotePermissionsPreference: Preference

    private val preferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    override fun onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        super.onDestroy()
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val context = requireContext()
        val statusCategory =
            PreferenceCategory(context).apply {
                title = getString(R.string.function_kit_status_category)
                order = -200
            }
        screen.addPreference(statusCategory)

        runtimePreference =
            Preference(context).apply {
                key = "function_kit_status_runtime"
                order = -199
                setup(title = getString(R.string.function_kit_status_runtime))
                isSelectable = false
            }
        statusCategory.addPreference(runtimePreference)

        remoteRoutingPreference =
            Preference(context).apply {
                key = "function_kit_status_remote_routing"
                order = -198
                setup(title = getString(R.string.function_kit_status_remote_routing))
                isSelectable = false
            }
        statusCategory.addPreference(remoteRoutingPreference)

        quickAccessPreference =
            Preference(context).apply {
                key = "function_kit_status_quick_access"
                order = -197
                setup(title = getString(R.string.function_kit_status_quick_access))
                isSelectable = false
            }
        statusCategory.addPreference(quickAccessPreference)

        toolbarExpandPreference =
            MySwitchPreference(context).apply {
                key = "function_kit_status_expand_toolbar_by_default"
                order = -196
                setup(
                    title = getString(R.string.expand_toolbar_by_default),
                    summary = getString(R.string.function_kit_toolbar_expand_by_default_summary)
                )
                isPersistent = false
                isChecked = keyboardPrefs.expandToolbarByDefault.getValue()
                setOnPreferenceChangeListener { _, newValue ->
                    keyboardPrefs.expandToolbarByDefault.setValue(newValue as Boolean)
                    true
                }
            }
        statusCategory.addPreference(toolbarExpandPreference)

        val capabilityCategory =
            PreferenceCategory(context).apply {
                title = getString(R.string.function_kit_status_capabilities)
                order = -195
            }
        screen.addPreference(capabilityCategory)

        localPermissionsPreference =
            Preference(context).apply {
                key = "function_kit_status_permissions_local"
                order = -194
                setup(title = getString(R.string.function_kit_status_permissions_local))
                isSelectable = false
            }
        capabilityCategory.addPreference(localPermissionsPreference)

        remotePermissionsPreference =
            Preference(context).apply {
                key = "function_kit_status_permissions_remote"
                order = -193
                setup(title = getString(R.string.function_kit_status_permissions_remote))
                isSelectable = false
            }
        capabilityCategory.addPreference(remotePermissionsPreference)

        refreshStatus()
    }

    private fun refreshStatus() {
        if (!this::runtimePreference.isInitialized) {
            return
        }

        val requestedPermissions = functionKitManifest.runtimePermissions
        val status =
            FunctionKitSettingsStatusResolver.resolve(
                requestedPermissions = requestedPermissions,
                enabledPermissions =
                    FunctionKitPermissionPolicy.grantedPermissions(
                        requestedPermissions = requestedPermissions,
                        prefs = functionKitPrefs
                    ),
                remoteInferenceEnabled = functionKitPrefs.remoteInferenceEnabled.getValue(),
                remoteBaseUrl = functionKitPrefs.remoteBaseUrl.getValue(),
                remoteAuthToken = functionKitPrefs.remoteAuthToken.getValue(),
                timeoutSeconds = functionKitPrefs.remoteTimeoutSeconds.getValue(),
                showToolbarButton = functionKitPrefs.showToolbarButton.getValue(),
                expandToolbarByDefault = keyboardPrefs.expandToolbarByDefault.getValue()
            )

        runtimePreference.summary =
            when {
                !status.remoteInferenceEnabled ->
                    getString(R.string.function_kit_status_runtime_local_summary)
                !status.remoteConfigured ->
                    getString(R.string.function_kit_status_runtime_missing_url_summary)
                else ->
                    getString(
                        R.string.function_kit_status_runtime_remote_summary,
                        status.normalizedRemoteBaseUrl,
                        status.timeoutSeconds
                    )
            }

        remoteRoutingPreference.summary =
            when {
                !status.remoteInferenceEnabled ->
                    getString(R.string.function_kit_status_remote_routing_disabled_summary)
                status.remoteUsesLoopback ->
                    getString(
                        R.string.function_kit_status_remote_routing_loopback_summary,
                        status.normalizedRemoteBaseUrl.ifBlank { "127.0.0.1" }
                    )
                status.remoteConfigured ->
                    getString(
                        if (status.remoteAuthConfigured) {
                            R.string.function_kit_status_remote_routing_token_summary
                        } else {
                            R.string.function_kit_status_remote_routing_direct_summary
                        },
                        status.normalizedRemoteBaseUrl
                    )
                else ->
                    getString(R.string.function_kit_status_runtime_missing_url_summary)
            }

        quickAccessPreference.summary =
            when {
                !status.showToolbarButton ->
                    getString(R.string.function_kit_status_quick_access_disabled_summary)
                status.quickAccessVisibleOnKeyboardStart ->
                    getString(R.string.function_kit_status_quick_access_enabled_summary)
                else ->
                    getString(R.string.function_kit_status_quick_access_collapsed_summary)
            }

        toolbarExpandPreference.isChecked = status.expandToolbarByDefault
        toolbarExpandPreference.summary =
            when {
                !status.showToolbarButton ->
                    getString(R.string.function_kit_toolbar_expand_by_default_hidden_summary)
                status.quickAccessVisibleOnKeyboardStart ->
                    getString(R.string.function_kit_toolbar_expand_by_default_enabled_summary)
                else ->
                    getString(R.string.function_kit_toolbar_expand_by_default_disabled_summary)
            }

        localPermissionsPreference.summary =
            buildPermissionSummary(status.corePermissions)
        remotePermissionsPreference.summary =
            buildPermissionSummary(status.remotePermissions)
    }

    private fun buildPermissionSummary(status: FunctionKitPermissionGroupStatus): String =
        when {
            !status.isRequested ->
                getString(R.string.function_kit_status_permissions_not_requested)
            status.disabledPermissions.isEmpty() ->
                getString(
                    R.string.function_kit_status_permissions_all_enabled,
                    status.enabled,
                    status.total
                )
            else -> {
                val disabledLabels =
                    status.disabledPermissions.joinToString(separator = ", ") {
                        getString(permissionLabel(it))
                    }
                getString(
                    R.string.function_kit_status_permissions_partial,
                    status.enabled,
                    status.total,
                    disabledLabels
                )
            }
        }

    private fun permissionLabel(permission: String): Int =
        when (permission) {
            "context.read" -> R.string.function_kit_permission_context_read
            "input.insert" -> R.string.function_kit_permission_input_insert
            "input.replace" -> R.string.function_kit_permission_input_replace
            "input.commitImage" -> R.string.function_kit_permission_input_commit_image
            "candidates.regenerate" -> R.string.function_kit_permission_candidates_regenerate
            "settings.open" -> R.string.function_kit_permission_settings_open
            "storage.read" -> R.string.function_kit_permission_storage_read
            "storage.write" -> R.string.function_kit_permission_storage_write
            "panel.state.write" -> R.string.function_kit_permission_panel_state_write
            "network.fetch" -> R.string.function_kit_status_permission_network_fetch
            "ai.chat" -> R.string.function_kit_status_permission_ai_chat
            "ai.agent.list" -> R.string.function_kit_status_permission_ai_agent_list
            "ai.agent.run" -> R.string.function_kit_status_permission_ai_agent_run
            else -> android.R.string.untitled
        }
}
