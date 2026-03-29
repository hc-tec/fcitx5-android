/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.functionkit

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitDefaults
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitKitSettings
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitPermissionPolicy
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitQuickAccessOrderer
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitRegistry
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitRuntimePermissionResolver
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.setup

class FunctionKitManagerFragment : PaddingPreferenceFragment() {

    private val functionKitPrefs = AppPrefs.getInstance().functionKit

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen =
            preferenceManager.createPreferenceScreen(requireContext()).also { screen ->
                renderUi(screen)
            }
    }

    private fun renderUi(screen: PreferenceScreen) {
        val context = screen.context
        screen.removeAll()

        val generalCategory =
            PreferenceCategory(context).apply {
                title = getString(R.string.function_kit_manager_general_category)
                order = -200
            }
        screen.addPreference(generalCategory)

        generalCategory.addPreference(
            MySwitchPreference(context).apply {
                key = functionKitPrefs.showToolbarButton.key
                setup(
                    title = getString(R.string.function_kit_toolbar_button),
                    summary = getString(R.string.function_kit_toolbar_button_summary)
                )
                setDefaultValue(functionKitPrefs.showToolbarButton.defaultValue)
                isIconSpaceReserved = false
            }
        )

        val kitsCategory =
            PreferenceCategory(context).apply {
                title = getString(R.string.function_kit_manager_kits_category)
                order = -190
            }
        screen.addPreference(kitsCategory)

        val installed =
            FunctionKitRegistry.listInstalled(context)
                .ifEmpty { listOf(FunctionKitRegistry.resolve(context)) }
        val pinnedKitIds =
            installed.mapNotNull { kit ->
                kit.id.takeIf { FunctionKitKitSettings.isKitPinned(it) }
            }.toSet()
        val lastUsedAtEpochMsByKitId =
            installed.associate { kit ->
                kit.id to FunctionKitKitSettings.lastUsedAtEpochMs(kit.id)
            }
        val kitById = installed.associateBy { it.id }
        val orderedKitIds =
            FunctionKitQuickAccessOrderer.orderKitIds(
                kitIds = installed.map { it.id },
                pinnedKitIds = pinnedKitIds,
                lastUsedAtEpochMsByKitId = lastUsedAtEpochMsByKitId
            )

        orderedKitIds.mapNotNull { kitById[it] }.forEach { kit ->
            val supportedPermissions =
                FunctionKitRuntimePermissionResolver.resolveSupported(
                    manifestDeclared = kit.runtimePermissions,
                    hostSupported = FunctionKitDefaults.supportedPermissions
                )
            val grantedPermissions =
                FunctionKitPermissionPolicy.grantedPermissions(
                    requestedPermissions = supportedPermissions,
                    prefs = functionKitPrefs,
                    kitId = kit.id
                )
            val enabled = FunctionKitKitSettings.isKitEnabled(kit.id)
            val summary =
                buildString {
                    append("id=")
                    append(kit.id)
                    append('\n')
                    append(
                        getString(
                            if (enabled) {
                                R.string.function_kit_manager_enabled
                            } else {
                                R.string.function_kit_manager_disabled
                            }
                        )
                    )
                    if (FunctionKitKitSettings.isKitPinned(kit.id)) {
                        append(" · ")
                        append(getString(R.string.function_kit_manager_pinned_badge))
                    }
                    if (supportedPermissions.isNotEmpty()) {
                        append(" · ")
                        append(
                            getString(
                                R.string.function_kit_manager_permissions_summary,
                                grantedPermissions.size,
                                supportedPermissions.size
                            )
                        )
                    }
                }

            kitsCategory.addPreference(
                Preference(context).apply {
                    setup(
                        title = FunctionKitRegistry.displayName(context, kit),
                        summary = summary
                    )
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        navigateWithAnim(
                            org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute.FunctionKitDetail(
                                kitId = kit.id
                            )
                        )
                        true
                    }
                }
            )
        }
    }
}
