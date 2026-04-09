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
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitAiChatBackend
import org.fcitx.fcitx5.android.utils.setup

class AiSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().ai) {

    private val aiPrefs = AppPrefs.getInstance().ai

    private lateinit var runtimePreference: Preference
    private lateinit var usagePreference: Preference

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
        val guideCategory =
            PreferenceCategory(context).apply {
                title = getString(R.string.ai_get_started_category)
                order = -220
            }
        screen.addPreference(guideCategory)

        guideCategory.addPreference(
            Preference(context).apply {
                key = "ai_get_started_steps"
                order = -219
                setup(title = getString(R.string.ai_get_started_title))
                summary = getString(R.string.ai_get_started_summary)
                isSelectable = false
            }
        )

        guideCategory.addPreference(
            Preference(context).apply {
                key = "ai_get_started_examples"
                order = -218
                setup(title = getString(R.string.ai_get_started_examples))
                summary = getString(R.string.ai_get_started_examples_summary)
                isSelectable = false
            }
        )

        val statusCategory =
            PreferenceCategory(context).apply {
                title = getString(R.string.ai_status_category)
                order = -200
            }
        screen.addPreference(statusCategory)

        runtimePreference =
            Preference(context).apply {
                key = "ai_status_runtime"
                order = -199
                setup(title = getString(R.string.ai_status_runtime))
                isSelectable = false
            }
        statusCategory.addPreference(runtimePreference)

        usagePreference =
            Preference(context).apply {
                key = "ai_status_usage"
                order = -198
                setup(title = getString(R.string.ai_status_usage))
                isSelectable = false
            }
        statusCategory.addPreference(usagePreference)

        refreshStatus()
    }

    private fun refreshStatus() {
        if (!this::runtimePreference.isInitialized) {
            return
        }

        val config = FunctionKitAiChatBackend.fromPrefs(aiPrefs)
        val baseUrl = config.normalizedBaseUrl.trim()
        val model = config.model.trim()

        runtimePreference.summary =
            when {
                !config.enabled && config.bootstrapAvailable ->
                    "Android AI chat is disabled. Shared debug defaults are available."
                config.usesBootstrapDefaults && config.isConfigured ->
                    "Using shared debug AI defaults: $baseUrl | $model | ${config.timeoutSeconds}s"
                !config.enabled ->
                    getString(R.string.ai_status_runtime_disabled_summary)
                baseUrl.isBlank() ->
                    getString(R.string.ai_status_runtime_missing_url_summary)
                model.isBlank() ->
                    getString(R.string.ai_status_runtime_missing_model_summary)
                else ->
                    getString(
                        R.string.ai_status_runtime_ready_summary,
                        baseUrl,
                        model,
                        config.timeoutSeconds
                    )
            }

        usagePreference.summary =
            buildString {
                append(getString(R.string.ai_status_usage_summary))
                when {
                    config.usesBootstrapDefaults ->
                        append("\n\nUnset AI settings are currently being filled from the shared debug bootstrap.")
                    config.bootstrapAvailable ->
                        append("\n\nShared debug defaults are available for this debug build.")
                }
            }
    }
}
