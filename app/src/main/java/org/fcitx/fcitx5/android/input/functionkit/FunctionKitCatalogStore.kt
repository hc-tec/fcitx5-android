/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.utils.appContext
import org.json.JSONArray
import org.json.JSONObject

internal object FunctionKitCatalogStore {
    private const val PrefName = "function_kit_catalog"
    private const val KeySourcesJson = "sources_json"
    private const val LegacyKeyCatalogUrl = "function_kit_download_center.catalog_url"

    data class Source(
        val url: String,
        val enabled: Boolean
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("url", url)
                .put("enabled", enabled)
    }

    fun getSources(context: Context): List<Source> {
        val ctx = context.applicationContext
        val prefs = sharedPreferences()
        val stored = prefs.getString(KeySourcesJson, null)
        val parsed = parseSources(stored)
        if (parsed.isNotEmpty()) {
            return parsed
        }

        val legacy = loadLegacyCatalogUrl(ctx)
        if (!legacy.isNullOrBlank()) {
            val migrated = listOf(Source(url = legacy.trim(), enabled = true))
            saveSources(migrated)
            return migrated
        }

        return emptyList()
    }

    fun saveSources(sources: List<Source>) {
        val normalized =
            sources
                .mapNotNull { src ->
                    val url = src.url.trim()
                    if (url.isBlank()) {
                        null
                    } else {
                        Source(url = url, enabled = src.enabled)
                    }
                }
                .distinctBy { it.url }
        val json = JSONArray(normalized.map(Source::toJson)).toString()
        sharedPreferences().edit().putString(KeySourcesJson, json).apply()
    }

    private fun sharedPreferences(): SharedPreferences {
        val ctx = appContext.createDeviceProtectedStorageContext()
        return ctx.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
    }

    private fun loadLegacyCatalogUrl(context: Context): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        return prefs.getString(LegacyKeyCatalogUrl, null)
    }

    private fun parseSources(raw: String?): List<Source> {
        val json = raw?.trim().orEmpty()
        if (json.isBlank()) {
            return emptyList()
        }
        val root =
            runCatching {
                JSONArray(json)
            }.getOrNull() ?: return emptyList()

        val sources = mutableListOf<Source>()
        for (index in 0 until root.length()) {
            val item = root.optJSONObject(index) ?: continue
            val url = item.optString("url").trim()
            if (url.isBlank()) {
                continue
            }
            val enabled = if (item.has("enabled")) item.optBoolean("enabled", true) else true
            sources += Source(url = url, enabled = enabled)
        }
        return sources.distinctBy { it.url }
    }
}
