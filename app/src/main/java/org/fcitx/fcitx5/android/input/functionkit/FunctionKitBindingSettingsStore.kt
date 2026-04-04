/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.fcitx.fcitx5.android.utils.appContext

internal class FunctionKitBindingSettingsStore(
    private val sharedPreferences: SharedPreferences
) {
    fun isBindingPinned(stableId: String): Boolean =
        sharedPreferences.getBoolean(pinnedKey(stableId), false)

    fun setBindingPinned(
        stableId: String,
        pinned: Boolean
    ) {
        sharedPreferences.edit().putBoolean(pinnedKey(stableId), pinned).apply()
    }

    fun lastUsedAtEpochMs(stableId: String): Long =
        sharedPreferences.getLong(lastUsedAtKey(stableId), 0L)

    fun recordUsedAtEpochMs(
        stableId: String,
        epochMs: Long
    ) {
        sharedPreferences.edit().putLong(lastUsedAtKey(stableId), epochMs).apply()
    }

    fun clearAll(stableId: String) {
        sharedPreferences.edit().apply {
            remove(pinnedKey(stableId))
            remove(lastUsedAtKey(stableId))
        }.apply()
    }

    private fun pinnedKey(stableId: String): String =
        "${bindingKeyPrefix(stableId)}.pinned"

    private fun lastUsedAtKey(stableId: String): String =
        "${bindingKeyPrefix(stableId)}.last_used_at_epoch_ms"

    private fun bindingKeyPrefix(stableId: String): String =
        "function_kit.binding.${encode(stableId)}"

    private fun encode(raw: String): String = Uri.encode(raw.trim())
}

internal object FunctionKitBindingSettings {
    private const val PrefName = "function_kit_binding_settings"

    private val sharedPreferences: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val ctx = appContext.createDeviceProtectedStorageContext()
        ctx.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
    }

    private val store: FunctionKitBindingSettingsStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        FunctionKitBindingSettingsStore(sharedPreferences)
    }

    fun addOnChangeListener(listener: (String?) -> Unit): () -> Unit {
        val prefsListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                listener(key)
            }
        sharedPreferences.registerOnSharedPreferenceChangeListener(prefsListener)
        return { sharedPreferences.unregisterOnSharedPreferenceChangeListener(prefsListener) }
    }

    fun isBindingPinned(stableId: String): Boolean = store.isBindingPinned(stableId)

    fun setBindingPinned(stableId: String, pinned: Boolean) = store.setBindingPinned(stableId, pinned)

    fun lastUsedAtEpochMs(stableId: String): Long = store.lastUsedAtEpochMs(stableId)

    fun recordUsedNow(stableId: String) = store.recordUsedAtEpochMs(stableId, System.currentTimeMillis())

    fun clearAll(stableId: String) = store.clearAll(stableId)

    internal fun createForTesting(sharedPreferences: SharedPreferences): FunctionKitBindingSettingsStore =
        FunctionKitBindingSettingsStore(sharedPreferences)
}

