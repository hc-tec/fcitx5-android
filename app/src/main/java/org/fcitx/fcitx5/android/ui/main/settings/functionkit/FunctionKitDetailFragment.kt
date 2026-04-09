/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.functionkit

import android.content.ClipData
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitAiChatBackend
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitDefaults
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitKitStudioRemoteAttach
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitKitSettings
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitManifest
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitPackageManager
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitPermissionPolicy
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitRegistry
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitRuntimePermissionResolver
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.setup
import org.fcitx.fcitx5.android.utils.toast
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class FunctionKitDetailFragment : PaddingPreferenceFragment() {

    private val args by lazyRoute<SettingsRoute.FunctionKitDetail>()
    private val functionKitPrefs = AppPrefs.getInstance().functionKit
    private var selectedBindingCategoryId: String = CATEGORY_FILTER_ALL

    private fun normalizeBaseUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            return null
        }
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains("://") -> null
            else -> "http://$trimmed"
        }
    }

    private fun normalizeKitStudioPairCode(raw: String): String? {
        val cleaned = raw.trim().replace("\\s+".toRegex(), "").uppercase()
        if (cleaned.isBlank()) {
            return null
        }
        val match = "^KS-?([A-Z2-7]{8})$".toRegex().find(cleaned) ?: return null
        val code = match.groupValues.getOrNull(1).orEmpty().trim()
        return if (code.isBlank()) null else "KS-$code"
    }

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

        val screen = preferenceManager.createPreferenceScreen(context)
        preferenceScreen = screen
        renderUi(screen)
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

        if (FunctionKitPackageManager.isUserInstalled(context, kit.id)) {
            kitCategory.addPreference(
                Preference(context).apply {
                    setup(
                        title = getString(R.string.function_kit_download_center_uninstall_title),
                        summary = getString(R.string.function_kit_download_center_uninstall_summary)
                    )
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        showUninstallDialog()
                        true
                    }
                }
            )
        }

        if (usesSharedAi(kit)) {
            val aiConfig = FunctionKitAiChatBackend.fromPrefs(AppPrefs.getInstance().ai)
            val aiCategory =
                PreferenceCategory(context).apply {
                    title = getString(R.string.function_kit_detail_ai_category)
                    order = -199
                }
            screen.addPreference(aiCategory)

            aiCategory.addPreference(
                Preference(context).apply {
                    key = "function_kit_detail_ai_status:${kit.id}"
                    setup(title = getString(R.string.function_kit_detail_ai_status_title))
                    summary =
                        if (aiConfig.isConfigured) {
                            getString(
                                R.string.function_kit_detail_ai_status_ready_summary,
                                aiConfig.normalizedBaseUrl,
                                aiConfig.model,
                                aiConfig.timeoutSeconds
                            )
                        } else {
                            getString(R.string.function_kit_detail_ai_status_missing_summary)
                        }
                    isSelectable = false
                    isIconSpaceReserved = false
                }
            )

            aiCategory.addPreference(
                Preference(context).apply {
                    key = "function_kit_detail_ai_open_settings:${kit.id}"
                    setup(
                        title = getString(R.string.function_kit_detail_ai_open_settings),
                        summary = getString(R.string.function_kit_detail_ai_open_settings_summary)
                    )
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        navigateWithAnim(SettingsRoute.Ai)
                        true
                    }
                }
            )
        }

        if (BuildConfig.DEBUG) {
            val attachEnabledKey = functionKitPrefs.kitStudioAttachEnabled.key
            val remoteDebugCategory =
                PreferenceCategory(context).apply {
                    title = getString(R.string.function_kit_kitstudio_attach_category)
                    order = -198
                }
            screen.addPreference(remoteDebugCategory)

            val attachEnabledPreference =
                MySwitchPreference(context).apply {
                    key = attachEnabledKey
                    setup(
                        title = getString(R.string.function_kit_kitstudio_attach_enabled),
                        summary = getString(R.string.function_kit_kitstudio_attach_enabled_summary)
                    )
                    setDefaultValue(functionKitPrefs.kitStudioAttachEnabled.defaultValue)
                    isIconSpaceReserved = false
                }
            remoteDebugCategory.addPreference(attachEnabledPreference)

            val baseUrlPreference =
                EditTextPreference(context).apply {
                    key = functionKitPrefs.kitStudioAttachBaseUrl.key
                    setup(title = getString(R.string.function_kit_kitstudio_attach_base_url))
                    setDefaultValue(functionKitPrefs.kitStudioAttachBaseUrl.defaultValue)
                    summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
                    dialogMessage = getString(R.string.function_kit_kitstudio_attach_base_url_summary)
                    isIconSpaceReserved = false
                }
            remoteDebugCategory.addPreference(baseUrlPreference)
            baseUrlPreference.dependency = attachEnabledKey

            val tokenPreference =
                EditTextPreference(context).apply {
                    key = functionKitPrefs.kitStudioAttachToken.key
                    setup(title = getString(R.string.function_kit_kitstudio_attach_token))
                    setDefaultValue(functionKitPrefs.kitStudioAttachToken.defaultValue)
                    dialogMessage = getString(R.string.function_kit_kitstudio_attach_token_summary)
                    setOnBindEditTextListener { editText ->
                        editText.inputType =
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                    summaryProvider =
                        Preference.SummaryProvider<EditTextPreference> { pref ->
                            val value = pref.text?.trim().orEmpty()
                            when {
                                value.isBlank() -> "-"
                                normalizeKitStudioPairCode(value) != null -> normalizeKitStudioPairCode(value)
                                value.length <= 6 -> "••••••"
                                else -> "…${value.takeLast(4)}"
                            }.orEmpty()
                        }
                    isIconSpaceReserved = false
                }
            remoteDebugCategory.addPreference(tokenPreference)
            tokenPreference.dependency = attachEnabledKey

            remoteDebugCategory.addPreference(
                Preference(context).apply {
                    key = "function_kit_kitstudio_attach_use_adb_reverse"
                    isPersistent = false
                    setup(
                        title = getString(R.string.function_kit_kitstudio_attach_use_adb_reverse),
                        summary = getString(R.string.function_kit_kitstudio_attach_use_adb_reverse_summary)
                    )
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        functionKitPrefs.kitStudioAttachBaseUrl.setValue("http://127.0.0.1:39001")
                        context.toast(R.string.done)
                        refresh()
                        true
                    }
                }
            )

            remoteDebugCategory.addPreference(
                Preference(context).apply {
                    key = "function_kit_kitstudio_attach_test_connection"
                    isPersistent = false
                    setup(
                        title = getString(R.string.function_kit_kitstudio_attach_test_connection),
                        summary = getString(R.string.function_kit_kitstudio_attach_test_connection_summary)
                    )
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        val rawBaseUrl = functionKitPrefs.kitStudioAttachBaseUrl.getValue()
                        val baseUrl = normalizeBaseUrl(rawBaseUrl) ?: "http://127.0.0.1:39001"
                        lifecycleScope.launch(Dispatchers.IO) {
                            val result =
                                runCatching {
                                    val connection =
                                        URL("${baseUrl.trimEnd('/')}/api/health").openConnection() as HttpURLConnection
                                    connection.requestMethod = "GET"
                                    connection.connectTimeout = 1_200
                                    connection.readTimeout = 1_200
                                    connection.setRequestProperty("Accept", "application/json")

                                    val statusCode = connection.responseCode
                                    val stream =
                                        if (statusCode in 200..299) connection.inputStream
                                        else connection.errorStream
                                    val text =
                                        stream
                                            ?.bufferedReader(StandardCharsets.UTF_8)
                                            ?.use { it.readText() }
                                            ?.trim()
                                            .orEmpty()
                                    statusCode in 200..299 && text.contains("\"ok\":true")
                                }
                            withContext(Dispatchers.Main.immediate) {
                                if (result.getOrDefault(false)) {
                                    context.toast(R.string.function_kit_kitstudio_attach_test_connection_ok)
                                } else {
                                    val reason = result.exceptionOrNull()?.javaClass?.simpleName ?: "not ok"
                                    context.toast(
                                        getString(
                                            R.string.function_kit_kitstudio_attach_test_connection_failed,
                                            reason
                                        )
                                    )
                                }
                            }
                        }
                        true
                    }
                }
            )

            runCatching {
                val channelId = FunctionKitKitStudioRemoteAttach.channelIdForKit(context, kit.id)
                val channelPreference =
                    Preference(context).apply {
                        key = "function_kit_kitstudio_attach_channel:${kit.id}"
                        isPersistent = false
                        setup(
                            title = getString(R.string.function_kit_kitstudio_attach_channel_id),
                            summary = "$channelId\n${getString(R.string.function_kit_kitstudio_attach_channel_id_summary)}"
                        )
                        isIconSpaceReserved = false
                        setOnPreferenceClickListener {
                            context.clipboardManager.setPrimaryClip(
                                ClipData.newPlainText(
                                    "kitstudio-remote-attach-channel",
                                    channelId
                                )
                            )
                            Toast.makeText(
                                context,
                                getString(R.string.ime_bridge_copied_to_clipboard),
                                Toast.LENGTH_SHORT
                            ).show()
                            true
                        }
                    }
                remoteDebugCategory.addPreference(channelPreference)
                channelPreference.dependency = attachEnabledKey
            }
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
                permissionsCategory.addPreference(createPermissionPreference(context, permission))
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
        val updatedKit =
            FunctionKitRegistry.listInstalled(requireContext())
                .firstOrNull { it.id == args.kitId }
                ?: run {
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    return
                }
        kit = updatedKit
        enabledPreference.isChecked = FunctionKitKitSettings.isKitEnabled(kit.id)
        pinnedPreference.isChecked = FunctionKitKitSettings.isKitPinned(kit.id)
        preferenceScreen?.let { renderUi(it) }
    }

    private fun usesSharedAi(manifest: FunctionKitManifest): Boolean =
        manifest.runtimePermissions.any { permission ->
            permission == "ai.request" || permission == "candidates.regenerate"
        } || manifest.ai.executionMode == "direct-model"

    private fun showUninstallDialog() {
        val context = requireContext()
        AlertDialog.Builder(context)
            .setTitle(R.string.function_kit_download_center_uninstall_title)
            .setMessage(getString(R.string.function_kit_download_center_uninstall_confirm, kit.name))
            .setPositiveButton(R.string.function_kit_download_center_uninstall_button) { _, _ ->
                lifecycleScope.withLoadingDialog(context, title = R.string.function_kit_download_center_uninstalling) {
                    val success =
                        withContext(NonCancellable + Dispatchers.IO) {
                            FunctionKitPackageManager.uninstall(context, kit.id)
                        }
                    withContext(Dispatchers.Main) {
                        if (!success) {
                            context.toast(R.string.function_kit_download_center_uninstall_failed)
                            return@withContext
                        }
                        context.toast(getString(R.string.function_kit_download_center_uninstalled, kit.id))
                        refresh()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createPermissionPreference(
        context: android.content.Context,
        permission: String
    ): MySwitchPreference {
        val defaultEnabled = FunctionKitPermissionPolicy.defaultEnabled(permission, functionKitPrefs, kit.id)
        val effectiveEnabled = FunctionKitPermissionPolicy.isEnabled(permission, functionKitPrefs, kit.id)
        return MySwitchPreference(context).apply {
            key = "function_kit_detail_permission_${kit.id}_$permission"
            setup(
                title = getString(permissionLabel(permission)),
                summary = buildPermissionSummary(permission)
            )
            isPersistent = false
            isIconSpaceReserved = false
            isChecked = effectiveEnabled
            onResetRequested = {
                FunctionKitKitSettings.setPermissionOverride(kit.id, permission, null)
                refresh()
            }
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                val overrideValue =
                    if (enabled == defaultEnabled) {
                        null
                    } else {
                        enabled
                    }
                FunctionKitKitSettings.setPermissionOverride(kit.id, permission, overrideValue)
                refresh()
                true
            }
        }
    }

    private fun buildPermissionSummary(permission: String): String {
        val override = FunctionKitKitSettings.getPermissionOverride(kit.id, permission)
        val globalEnabled = FunctionKitPermissionPolicy.isEnabled(permission, functionKitPrefs)
        val defaultEnabled = FunctionKitPermissionPolicy.defaultEnabled(permission, functionKitPrefs, kit.id)
        val effectiveEnabled =
            FunctionKitPermissionPolicy.isEnabled(permission, functionKitPrefs, kit.id)

        val source =
            when (override) {
                null ->
                    when {
                        defaultEnabled != globalEnabled ->
                            getString(
                                if (defaultEnabled) {
                                    R.string.function_kit_manager_permission_source_bundled_allow
                                } else {
                                    R.string.function_kit_manager_permission_source_bundled_deny
                                }
                            )
                        globalEnabled ->
                            getString(R.string.function_kit_manager_permission_source_global_allow)
                        else ->
                            getString(R.string.function_kit_manager_permission_source_global_deny)
                    }
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
        return getString(
            R.string.function_kit_manager_permission_toggle_summary,
            statusLabel,
            source
        )
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
            "files.download" -> R.string.function_kit_permission_files_download
            "panel.state.write" -> R.string.function_kit_permission_panel_state_write
            "runtime.message.send" -> R.string.function_kit_permission_runtime_message_send
            "runtime.message.receive" -> R.string.function_kit_permission_runtime_message_receive
            "network.fetch" -> R.string.function_kit_permission_network_fetch
            "ai.request" -> R.string.function_kit_permission_ai_chat
            "ai.agent.list" -> R.string.function_kit_permission_ai_agent_list
            "ai.agent.run" -> R.string.function_kit_permission_ai_agent_run
            "kits.manage" -> R.string.function_kit_permission_kits_manage
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
