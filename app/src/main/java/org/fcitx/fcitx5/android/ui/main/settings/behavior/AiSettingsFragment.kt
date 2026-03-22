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

        val baseUrl = aiPrefs.chatBaseUrl.getValue().trim().trimEnd('/')
        val model = aiPrefs.chatModel.getValue().trim()

        runtimePreference.summary =
            when {
                !aiPrefs.chatEnabled.getValue() ->
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
                        aiPrefs.chatTimeoutSeconds.getValue()
                    )
            }

        usagePreference.summary =
            getString(R.string.ai_status_usage_summary)
    }
}
