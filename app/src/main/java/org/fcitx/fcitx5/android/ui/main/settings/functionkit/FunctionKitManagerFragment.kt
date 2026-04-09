/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.functionkit

import android.os.Bundle
import android.text.InputType
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitDefaults
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitKitSettings
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitManifest
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitPermissionPolicy
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitQuickAccessOrderer
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitRegistry
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitRuntimePermissionResolver
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.setup
import org.fcitx.fcitx5.android.utils.toast
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class FunctionKitManagerFragment : PaddingPreferenceFragment() {

    private val functionKitPrefs = AppPrefs.getInstance().functionKit
    private var selectedCategoryId: String = CATEGORY_FILTER_ALL

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

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        preferenceScreen = screen
        renderUi(screen)
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

        generalCategory.addPreference(
            Preference(context).apply {
                setup(
                    title = getString(R.string.function_kit_manager_shared_ai_title),
                    summary = getString(R.string.function_kit_manager_shared_ai_summary)
                )
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    navigateWithAnim(SettingsRoute.Ai)
                    true
                }
            }
        )

        generalCategory.addPreference(
            Preference(context).apply {
                setup(
                    title = getString(R.string.function_kit_download_center),
                    summary = getString(R.string.function_kit_download_center_summary)
                )
                FunctionKitRegistry
                    .listInstalled(context)
                    .firstOrNull { it.id == "kit-store" }
                    ?.let { applyFunctionKitPreferenceIcon(it, targetSizePx = 96) }
                setOnPreferenceClickListener {
                    navigateWithAnim(SettingsRoute.FunctionKitDownloadCenter)
                    true
                }
            }
        )

        if (BuildConfig.DEBUG) {
            val attachEnabledKey = functionKitPrefs.kitStudioAttachEnabled.key
            val remoteDebugCategory =
                PreferenceCategory(context).apply {
                    title = getString(R.string.function_kit_kitstudio_attach_category)
                    order = -195
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
                        renderUi(screen)
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
        }

        val kitsCategory =
            PreferenceCategory(context).apply {
                title = getString(R.string.function_kit_manager_kits_category)
                order = -190
            }
        screen.addPreference(kitsCategory)

        val installed =
            FunctionKitRegistry.listInstalled(context)
                .ifEmpty { listOf(FunctionKitRegistry.resolve(context)) }
        val kitCategoriesById =
            installed.associate { kit ->
                kit.id to resolveKitCategories(kit)
            }
        val allCategories =
            kitCategoriesById.values
                .flatten()
                .distinct()
                .sortedWith(compareBy { it.lowercase() })
        val hasUncategorizedKits = kitCategoriesById.values.any { it.isEmpty() }
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

        if (allCategories.isNotEmpty() || hasUncategorizedKits) {
            kitsCategory.addPreference(
                ListPreference(context).apply {
                    key = "function_kit_manager_category_filter"
                    isPersistent = false
                    title = getString(R.string.function_kit_manager_category_filter_title)
                    entries =
                        buildList {
                            add(getString(R.string.function_kit_bindings_filter_all))
                            addAll(allCategories)
                            if (hasUncategorizedKits) {
                                add(getString(R.string.function_kit_bindings_filter_other))
                            }
                        }.toTypedArray()
                    entryValues =
                        buildList {
                            add(CATEGORY_FILTER_ALL)
                            addAll(allCategories)
                            if (hasUncategorizedKits) {
                                add(CATEGORY_FILTER_OTHER)
                            }
                        }.toTypedArray()
                    value = selectedCategoryId
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    isIconSpaceReserved = false
                    setOnPreferenceChangeListener { _, newValue ->
                        selectedCategoryId = newValue?.toString()?.trim().orEmpty().ifBlank { CATEGORY_FILTER_ALL }
                        renderUi(screen)
                        true
                    }
                }
            )
        }

        orderedKitIds
            .mapNotNull { kitById[it] }
            .filter { kit ->
                val selected = selectedCategoryId
                if (selected == CATEGORY_FILTER_ALL) {
                    return@filter true
                }
                val categories = kitCategoriesById[kit.id].orEmpty()
                if (selected == CATEGORY_FILTER_OTHER) {
                    return@filter categories.isEmpty()
                }
                return@filter categories.contains(selected)
            }.forEach { kit ->
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
                    val kitCategories = kitCategoriesById[kit.id].orEmpty()
                    if (kitCategories.isNotEmpty()) {
                        append('\n')
                        append(
                            getString(
                                R.string.function_kit_manager_categories_summary,
                                kitCategories.joinToString(", ")
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
                    applyFunctionKitPreferenceIcon(kit, targetSizePx = 96)
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

    private fun resolveKitCategories(kit: FunctionKitManifest): List<String> =
        kit.bindings
            .flatMap { binding ->
                binding.categories?.mapNotNull { category ->
                    category.trim().takeIf { it.isNotBlank() }
                }.orEmpty()
            }
            .distinct()
            .sortedWith(compareBy { it.lowercase() })

    private companion object {
        private const val CATEGORY_FILTER_ALL = "__all__"
        private const val CATEGORY_FILTER_OTHER = "__other__"
    }
}
