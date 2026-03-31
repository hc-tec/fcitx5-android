/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.functionkit

import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.ListPreference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.appcompat.app.AlertDialog
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitDefaults
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitKitSettings
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitManifest
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitPermissionPolicy
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitRegistry
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitRuntimePermissionResolver
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.setup

class FunctionKitDetailFragment : PaddingPreferenceFragment() {

    private val args by lazyRoute<SettingsRoute.FunctionKitDetail>()
    private val functionKitPrefs = AppPrefs.getInstance().functionKit
    private var selectedBindingCategoryId: String = CATEGORY_FILTER_ALL

    private lateinit var kit: FunctionKitManifest

    private lateinit var enabledPreference: MySwitchPreference
    private lateinit var pinnedPreference: MySwitchPreference

    private val preferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            refresh()
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

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val installed = FunctionKitRegistry.listInstalled(context)
        kit =
            installed.firstOrNull { it.id == args.kitId }
                ?: FunctionKitRegistry.resolve(context)

        preferenceScreen =
            preferenceManager.createPreferenceScreen(context).also { screen ->
                renderUi(screen)
            }
    }

    private fun renderUi(screen: PreferenceScreen) {
        val context = screen.context
        screen.removeAll()

        val kitCategory =
            PreferenceCategory(context).apply {
                title = getString(R.string.function_kit_manager_kit_category)
                order = -200
            }
        screen.addPreference(kitCategory)

        enabledPreference =
            MySwitchPreference(context).apply {
                key = "function_kit_kit_enabled:${kit.id}"
                isPersistent = false
                setup(
                    title = getString(R.string.function_kit_manager_enabled_toggle),
                    summary = kit.id
                )
                isIconSpaceReserved = false
                isChecked = FunctionKitKitSettings.isKitEnabled(kit.id)
                setOnPreferenceChangeListener { _, newValue ->
                    FunctionKitKitSettings.setKitEnabled(kit.id, newValue as Boolean)
                    refresh()
                    true
                }
            }
        kitCategory.addPreference(enabledPreference)

        pinnedPreference =
            MySwitchPreference(context).apply {
                key = "function_kit_kit_pinned:${kit.id}"
                isPersistent = false
                setup(
                    title = getString(R.string.function_kit_manager_pin_to_toolbar),
                    summary = getString(R.string.function_kit_manager_pin_to_toolbar_summary)
                )
                isIconSpaceReserved = false
                isChecked = FunctionKitKitSettings.isKitPinned(kit.id)
                setOnPreferenceChangeListener { _, newValue ->
                    FunctionKitKitSettings.setKitPinned(kit.id, newValue as Boolean)
                    refresh()
                    true
                }
            }
        kitCategory.addPreference(pinnedPreference)

        kit.description?.takeIf { it.isNotBlank() }?.let { description ->
            kitCategory.addPreference(
                Preference(context).apply {
                    setup(
                        title = getString(R.string.function_kit_manager_description),
                        summary = description.trim()
                    )
                    isSelectable = false
                    isIconSpaceReserved = false
                }
            )
        }

        val bindingsCategory =
            PreferenceCategory(context).apply {
                title = getString(R.string.function_kit_manager_bindings_category)
                order = -195
            }
        screen.addPreference(bindingsCategory)

        val bindingCategoriesById =
            kit.bindings.associate { binding ->
                binding.id to resolveBindingCategories(binding)
            }
        val allBindingCategories =
            bindingCategoriesById.values
                .flatten()
                .distinct()
                .sortedWith(compareBy { it.lowercase() })
        val hasUncategorizedBindings =
            bindingCategoriesById.values.any { categories -> categories.isEmpty() } && allBindingCategories.isNotEmpty()

        if (kit.bindings.isEmpty()) {
            bindingsCategory.addPreference(
                Preference(context).apply {
                    setup(
                        title = getString(R.string.function_kit_manager_bindings_empty_title),
                        summary = getString(R.string.function_kit_manager_bindings_empty_summary)
                    )
                    isSelectable = false
                    isIconSpaceReserved = false
                }
            )
        } else {
            if (allBindingCategories.isNotEmpty()) {
                bindingsCategory.addPreference(
                    ListPreference(context).apply {
                        key = "function_kit_binding_category_filter:${kit.id}"
                        isPersistent = false
                        title = getString(R.string.function_kit_manager_category_filter_title)
                        entries =
                            buildList {
                                add(getString(R.string.function_kit_bindings_filter_all))
                                addAll(allBindingCategories)
                                if (hasUncategorizedBindings) {
                                    add(getString(R.string.function_kit_bindings_filter_other))
                                }
                            }.toTypedArray()
                        entryValues =
                            buildList {
                                add(CATEGORY_FILTER_ALL)
                                addAll(allBindingCategories)
                                if (hasUncategorizedBindings) {
                                    add(CATEGORY_FILTER_OTHER)
                                }
                            }.toTypedArray()
                        value = selectedBindingCategoryId
                        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                        isIconSpaceReserved = false
                        setOnPreferenceChangeListener { _, newValue ->
                            selectedBindingCategoryId =
                                newValue?.toString()?.trim().orEmpty().ifBlank { CATEGORY_FILTER_ALL }
                            renderUi(screen)
                            true
                        }
                    }
                )
            }

            kit.bindings
                .filter { binding ->
                    val selected = selectedBindingCategoryId
                    if (selected == CATEGORY_FILTER_ALL) {
                        return@filter true
                    }
                    val categories = bindingCategoriesById[binding.id].orEmpty()
                    if (selected == CATEGORY_FILTER_OTHER) {
                        return@filter categories.isEmpty()
                    }
                    return@filter categories.contains(selected)
                }
                .sortedWith(compareBy<FunctionKitManifest.Binding>({ it.title.lowercase() }, { it.id.lowercase() }))
                .forEach { binding ->
                    val categories = bindingCategoriesById[binding.id].orEmpty()
                    val summaryLines = mutableListOf<String>()
                    summaryLines += "id=${binding.id}"
                    if (binding.triggers.isNotEmpty()) {
                        summaryLines += "triggers=${binding.triggers.joinToString(",")}"
                    }
                    if (categories.isNotEmpty()) {
                        summaryLines += "categories=${categories.joinToString(", ")}"
                    }
                    binding.preferredPresentation?.trim()?.takeIf { it.isNotBlank() }?.let { value ->
                        summaryLines += "presentation=$value"
                    }
                    binding.requestedPayloads?.takeIf { it.isNotEmpty() }?.let { payloads ->
                        summaryLines += "payloads=${payloads.joinToString(",")}"
                    }

                    bindingsCategory.addPreference(
                        Preference(context).apply {
                            setup(
                                title = binding.title,
                                summary = summaryLines.joinToString("\n")
                            )
                            isSelectable = false
                            isIconSpaceReserved = false
                        }
                    )
                }
        }

        val permissionsCategory =
            PreferenceCategory(context).apply {
                title = getString(R.string.function_kit_manager_permissions_category)
                order = -190
            }
        screen.addPreference(permissionsCategory)

        val supportedPermissions =
            FunctionKitRuntimePermissionResolver.resolveSupported(
                manifestDeclared = kit.runtimePermissions,
                hostSupported = FunctionKitDefaults.supportedPermissions
            )

        if (supportedPermissions.isEmpty()) {
            permissionsCategory.addPreference(
                Preference(context).apply {
                    setup(
                        title = getString(R.string.function_kit_manager_permissions_empty_title),
                        summary = getString(R.string.function_kit_manager_permissions_empty_summary)
                    )
                    isSelectable = false
                    isIconSpaceReserved = false
                }
            )
        } else {
            supportedPermissions.forEach { permission ->
                permissionsCategory.addPreference(
                    Preference(context).apply {
                        setup(
                            title = getString(permissionLabel(permission)),
                            summary = buildPermissionSummary(permission)
                        )
                        isIconSpaceReserved = false
                        setOnPreferenceClickListener {
                            showPermissionDialog(permission)
                            true
                        }
                    }
                )
            }

            permissionsCategory.addPreference(
                Preference(context).apply {
                    setup(
                        title = getString(R.string.function_kit_manager_permissions_reset_title),
                        summary = getString(R.string.function_kit_manager_permissions_reset_summary)
                    )
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        FunctionKitKitSettings.clearPermissionOverrides(kit.id)
                        refresh()
                        true
                    }
                }
            )
        }
    }

    private fun refresh() {
        if (!this::enabledPreference.isInitialized) {
            return
        }
        enabledPreference.isChecked = FunctionKitKitSettings.isKitEnabled(kit.id)
        pinnedPreference.isChecked = FunctionKitKitSettings.isKitPinned(kit.id)
        preferenceScreen?.let { renderUi(it) }
    }

    private fun showPermissionDialog(permission: String) {
        val context = requireContext()
        val override = FunctionKitKitSettings.getPermissionOverride(kit.id, permission)
        val globalEnabled = FunctionKitPermissionPolicy.isEnabled(permission, functionKitPrefs)

        val labels =
            arrayOf(
                getString(R.string.function_kit_manager_permission_inherit),
                getString(R.string.function_kit_manager_permission_allow),
                getString(R.string.function_kit_manager_permission_deny)
            )
        val initialSelection =
            when (override) {
                null -> 0
                true -> 1
                false -> 2
            }

        AlertDialog.Builder(context)
            .setTitle(getString(permissionLabel(permission)))
            .setMessage(
                getString(
                    R.string.function_kit_manager_permission_dialog_summary,
                    if (globalEnabled) getString(R.string.function_kit_manager_enabled)
                    else getString(R.string.function_kit_manager_disabled)
                )
            )
            .setSingleChoiceItems(labels, initialSelection) { dialog, which ->
                when (which) {
                    0 -> FunctionKitKitSettings.setPermissionOverride(kit.id, permission, null)
                    1 -> FunctionKitKitSettings.setPermissionOverride(kit.id, permission, true)
                    2 -> FunctionKitKitSettings.setPermissionOverride(kit.id, permission, false)
                }
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildPermissionSummary(permission: String): String {
        val override = FunctionKitKitSettings.getPermissionOverride(kit.id, permission)
        val globalEnabled = FunctionKitPermissionPolicy.isEnabled(permission, functionKitPrefs)
        val effectiveEnabled =
            FunctionKitPermissionPolicy.isEnabled(permission, functionKitPrefs, kit.id)

        val source =
            when (override) {
                null ->
                    getString(
                        if (globalEnabled) {
                            R.string.function_kit_manager_permission_source_global_allow
                        } else {
                            R.string.function_kit_manager_permission_source_global_deny
                        }
                    )
                true -> getString(R.string.function_kit_manager_permission_source_kit_allow)
                false -> getString(R.string.function_kit_manager_permission_source_kit_deny)
            }

        val statusLabel =
            getString(
                if (effectiveEnabled) {
                    R.string.function_kit_manager_enabled
                } else {
                    R.string.function_kit_manager_disabled
                }
            )
        return "$statusLabel · $source"
    }

    private fun permissionLabel(permission: String): Int =
        when (permission) {
            "context.read" -> R.string.function_kit_permission_context_read
            "input.insert" -> R.string.function_kit_permission_input_insert
            "input.replace" -> R.string.function_kit_permission_input_replace
            "input.commitImage" -> R.string.function_kit_permission_input_commit_image
            "input.observe.best_effort" -> R.string.function_kit_permission_input_observe_best_effort
            "candidates.regenerate" -> R.string.function_kit_permission_candidates_regenerate
            "send.intercept.ime_action" -> R.string.function_kit_permission_send_intercept_ime_action
            "settings.open" -> R.string.function_kit_permission_settings_open
            "storage.read" -> R.string.function_kit_permission_storage_read
            "storage.write" -> R.string.function_kit_permission_storage_write
            "files.pick" -> R.string.function_kit_permission_files_pick
            "panel.state.write" -> R.string.function_kit_permission_panel_state_write
            "runtime.message.send" -> R.string.function_kit_permission_runtime_message_send
            "runtime.message.receive" -> R.string.function_kit_permission_runtime_message_receive
            "network.fetch" -> R.string.function_kit_permission_network_fetch
            "ai.request" -> R.string.function_kit_permission_ai_chat
            "ai.agent.list" -> R.string.function_kit_permission_ai_agent_access
            "ai.agent.run" -> R.string.function_kit_permission_ai_agent_access
            else -> android.R.string.untitled
    }

    private fun resolveBindingCategories(binding: FunctionKitManifest.Binding): List<String> =
        binding.categories
            ?.mapNotNull { category ->
                category.trim().takeIf { it.isNotBlank() }
            }
            ?.distinct()
            ?.sortedWith(compareBy { it.lowercase() })
            ?: emptyList()

    private companion object {
        private const val CATEGORY_FILTER_ALL = "__all__"
        private const val CATEGORY_FILTER_OTHER = "__other__"
    }
}
