/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.Context
import org.fcitx.fcitx5.android.R

internal object FunctionKitRegistry {
    private const val AssetRoot = "function-kits"
    private const val ManifestFileName = "manifest.json"

    fun listInstalled(context: Context): List<FunctionKitManifest> {
        val bundled = listBundledFromAssets(context)
        val userInstalled = FunctionKitPackageManager.listUserInstalledManifests(context)
        if (userInstalled.isEmpty()) {
            return bundled
        }
        val merged = LinkedHashMap<String, FunctionKitManifest>()
        bundled.forEach { kit -> merged[kit.id] = kit }
        userInstalled.forEach { kit ->
            val existing = merged[kit.id]
            merged[kit.id] = if (existing == null) kit else pickPreferred(existing, kit)
        }
        return merged.values.toList().sortedWith(compareBy({ it.name.lowercase() }, { it.id.lowercase() }))
    }

    fun resolve(
        context: Context,
        requestedKitId: String? = null
    ): FunctionKitManifest {
        val installed = listInstalled(context)
        val available =
            installed.filter { kit -> FunctionKitKitSettings.isKitEnabled(kit.id) }
                .ifEmpty { installed }
        val preferredKitId =
            selectPreferredKitId(
                available.map(FunctionKitManifest::id),
                requestedKitId = requestedKitId
            )
        return available.firstOrNull { it.id == preferredKitId }
            ?: available.firstOrNull()
            ?: FunctionKitManifest.loadFromAssets(
                context = context,
                assetPath = FunctionKitDefaults.manifestAssetPath,
                fallbackId = FunctionKitDefaults.kitId,
                fallbackEntryHtmlAssetPath = FunctionKitDefaults.entryAssetPath,
                fallbackRuntimePermissions = emptySet()
            )
    }

    fun displayName(
        context: Context,
        manifest: FunctionKitManifest
    ): String =
        when (manifest.id) {
            FunctionKitDefaults.kitId -> context.getString(R.string.function_kit_auto_reply)
            else -> manifest.name
        }

    private fun listBundledFromAssets(context: Context): List<FunctionKitManifest> {
        val assetManager = context.assets
        val kitDirectories =
            assetManager.list(AssetRoot)
                ?.filter { candidate ->
                    candidate.isNotBlank() &&
                        assetManager.list("$AssetRoot/$candidate")?.contains(ManifestFileName) == true
                }
                .orEmpty()
                .sorted()

        return kitDirectories.map { directory ->
            val assetPath = "$AssetRoot/$directory/$ManifestFileName"
            FunctionKitManifest.loadFromAssets(
                context = context,
                assetPath = assetPath,
                fallbackId = directory,
                fallbackEntryHtmlAssetPath = "$AssetRoot/$directory/ui/app/index.html",
                fallbackRuntimePermissions = emptySet()
            )
        }
    }

    private fun pickPreferred(
        first: FunctionKitManifest,
        second: FunctionKitManifest
    ): FunctionKitManifest {
        // The download center is a privileged built-in kit (`kits.manage`, `files.download`).
        // Never allow user-installed versions to shadow the bundled one, otherwise users can
        // brick their management entry point.
        if (first.id == "kit-store" || second.id == "kit-store") {
            return when {
                !first.isUserInstalled -> first
                !second.isUserInstalled -> second
                else -> first
            }
        }

        val cmp = compareVersions(first.version, second.version)
        if (cmp != null && cmp != 0) {
            return if (cmp > 0) first else second
        }
        if (first.isUserInstalled != second.isUserInstalled) {
            return if (first.isUserInstalled) first else second
        }
        return second
    }

    private fun compareVersions(
        first: String?,
        second: String?
    ): Int? {
        val firstParts = parseVersionParts(first) ?: return null
        val secondParts = parseVersionParts(second) ?: return null
        val max = maxOf(firstParts.size, secondParts.size, 3)
        for (index in 0 until max) {
            val left = firstParts.getOrElse(index) { 0 }
            val right = secondParts.getOrElse(index) { 0 }
            if (left != right) {
                return left.compareTo(right)
            }
        }
        return 0
    }

    private fun parseVersionParts(raw: String?): List<Int>? {
        val normalized = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val core = normalized.split('-', '+').firstOrNull()?.trim().orEmpty()
        if (core.isBlank()) {
            return null
        }
        val parts = core.split('.').map(String::trim)
        if (parts.any { it.isBlank() }) {
            return null
        }
        return parts.map { token -> token.toIntOrNull() ?: return null }
    }

    internal fun selectPreferredKitId(
        availableKitIds: Collection<String>,
        requestedKitId: String? = null
    ): String? {
        val available = availableKitIds.filter { it.isNotBlank() }.distinct()
        return when {
            available.isEmpty() -> null
            requestedKitId != null && requestedKitId in available -> requestedKitId
            FunctionKitDefaults.kitId in available -> FunctionKitDefaults.kitId
            else -> available.first()
        }
    }
}
