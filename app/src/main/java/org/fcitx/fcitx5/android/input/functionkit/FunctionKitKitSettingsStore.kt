/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.fcitx.fcitx5.android.utils.appContext

internal class FunctionKitKitSettingsStore(
    private val sharedPreferences: SharedPreferences
) {
    fun registryRevisionEpochMs(): Long =
        sharedPreferences.getLong(FunctionKitKitSettings.RegistryRevisionPreferenceKey, 0L)

    fun bumpRegistryRevision(epochMs: Long = System.currentTimeMillis()) {
        sharedPreferences.edit()
            .putLong(FunctionKitKitSettings.RegistryRevisionPreferenceKey, epochMs)
            .apply()
    }

    fun isKitEnabled(kitId: String): Boolean =
        sharedPreferences.getBoolean(enabledKey(kitId), true)

    fun setKitEnabled(
        kitId: String,
        enabled: Boolean
    ) {
        sharedPreferences.edit().putBoolean(enabledKey(kitId), enabled).apply()
    }

    fun isKitPinned(kitId: String): Boolean =
        sharedPreferences.getBoolean(pinnedKey(kitId), false)

    fun setKitPinned(
        kitId: String,
        pinned: Boolean
    ) {
        sharedPreferences.edit().putBoolean(pinnedKey(kitId), pinned).apply()
    }

    fun activeInstallKey(kitId: String): String? =
        sharedPreferences.getString(activeInstallKeyKey(kitId), null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    fun setActiveInstallKey(
        kitId: String,
        installKey: String?
    ) {
        val key = activeInstallKeyKey(kitId)
        val normalized = installKey?.trim().orEmpty()
        sharedPreferences.edit().apply {
            if (normalized.isBlank()) {
                remove(key)
            } else {
                putString(key, normalized)
            }
        }.apply()
    }

    fun lastUsedAtEpochMs(kitId: String): Long =
        sharedPreferences.getLong(lastUsedAtKey(kitId), 0L)

    fun recordUsedAtEpochMs(
        kitId: String,
        epochMs: Long
    ) {
        sharedPreferences.edit().putLong(lastUsedAtKey(kitId), epochMs).apply()
    }

    fun getPermissionOverride(
        kitId: String,
        permission: String
    ): Boolean? {
        val key = permissionKey(kitId, permission)
        if (!sharedPreferences.contains(key)) {
            return null
        }
        return sharedPreferences.getBoolean(key, true)
    }

    fun setPermissionOverride(
        kitId: String,
        permission: String,
        enabled: Boolean?
    ) {
        val key = permissionKey(kitId, permission)
        sharedPreferences.edit().apply {
            if (enabled == null) {
                remove(key)
            } else {
                putBoolean(key, enabled)
            }
        }.apply()
    }

    fun clearPermissionOverrides(kitId: String) {
        val prefix = "${kitKeyPrefix(kitId)}.perm."
        val keys = sharedPreferences.all.keys.filter { it.startsWith(prefix) }
        if (keys.isEmpty()) {
            return
        }
        sharedPreferences.edit().apply {
            keys.forEach { remove(it) }
        }.apply()
    }

    fun clearAll(kitId: String) {
        sharedPreferences.edit().apply {
            remove(enabledKey(kitId))
            remove(pinnedKey(kitId))
            remove(activeInstallKeyKey(kitId))
            remove(lastUsedAtKey(kitId))
            val prefix = "${kitKeyPrefix(kitId)}.perm."
            sharedPreferences.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
        }.apply()
    }

    private fun enabledKey(kitId: String): String = "${kitKeyPrefix(kitId)}.enabled"

    private fun pinnedKey(kitId: String): String = "${kitKeyPrefix(kitId)}.pinned"

    private fun activeInstallKeyKey(kitId: String): String = "${kitKeyPrefix(kitId)}.active_install_key"

    private fun lastUsedAtKey(kitId: String): String = "${kitKeyPrefix(kitId)}.last_used_at_epoch_ms"

    private fun permissionKey(
        kitId: String,
        permission: String
    ): String = "${kitKeyPrefix(kitId)}.perm.${encode(permission)}"

    private fun kitKeyPrefix(kitId: String): String = "function_kit.kit.${encode(kitId)}"

    private fun encode(raw: String): String = Uri.encode(raw.trim())
}

internal object FunctionKitKitSettings {
    private const val PrefName = "function_kit_kit_settings"

    const val RegistryRevisionPreferenceKey: String = "function_kit.registry_revision"

    private val sharedPreferences: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val ctx = appContext.createDeviceProtectedStorageContext()
        ctx.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
    }

    private val store: FunctionKitKitSettingsStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        FunctionKitKitSettingsStore(sharedPreferences)
    }

    fun addOnChangeListener(listener: (String?) -> Unit): () -> Unit {
        val prefsListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                listener(key)
            }
        sharedPreferences.registerOnSharedPreferenceChangeListener(prefsListener)
        return { sharedPreferences.unregisterOnSharedPreferenceChangeListener(prefsListener) }
    }

    fun isKitEnabled(kitId: String): Boolean = store.isKitEnabled(kitId)

    fun setKitEnabled(kitId: String, enabled: Boolean) = store.setKitEnabled(kitId, enabled)

    fun isKitPinned(kitId: String): Boolean = store.isKitPinned(kitId)

    fun setKitPinned(kitId: String, pinned: Boolean) = store.setKitPinned(kitId, pinned)

    fun activeInstallKey(kitId: String): String? = store.activeInstallKey(kitId)

    fun setActiveInstallKey(kitId: String, installKey: String?) = store.setActiveInstallKey(kitId, installKey)

    fun registryRevisionEpochMs(): Long = store.registryRevisionEpochMs()

    fun bumpRegistryRevision() = store.bumpRegistryRevision()

    fun lastUsedAtEpochMs(kitId: String): Long = store.lastUsedAtEpochMs(kitId)

    fun recordUsedNow(kitId: String) = store.recordUsedAtEpochMs(kitId, System.currentTimeMillis())

    fun getPermissionOverride(kitId: String, permission: String): Boolean? =
        store.getPermissionOverride(kitId, permission)

    fun setPermissionOverride(kitId: String, permission: String, enabled: Boolean?) =
        store.setPermissionOverride(kitId, permission, enabled)

    fun clearPermissionOverrides(kitId: String) = store.clearPermissionOverrides(kitId)

    fun clearAll(kitId: String) = store.clearAll(kitId)

    internal fun createForTesting(sharedPreferences: SharedPreferences): FunctionKitKitSettingsStore =
        FunctionKitKitSettingsStore(sharedPreferences)
}
