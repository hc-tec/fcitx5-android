/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.Context
import org.json.JSONObject

internal enum class FunctionKitBindingTrigger {
    Manual,
    Selection,
    Clipboard;

    companion object {
        fun parse(value: String?): FunctionKitBindingTrigger? =
            when (value?.trim()?.lowercase()) {
                "manual" -> Manual
                "selection" -> Selection
                "clipboard" -> Clipboard
                else -> null
            }
    }
}

internal data class FunctionKitBindingEntry(
    val kitId: String,
    val kitLabel: String,
    val kitIconAssetPath: String?,
    val bindingId: String,
    val title: String,
    val triggers: Set<FunctionKitBindingTrigger>,
    val requestedPayloads: Set<String>?,
    val categories: Set<String>?,
    val entry: JSONObject?,
    val preferredPresentation: String?
) {
    val stableId: String = "$kitId:$bindingId"
}

internal object FunctionKitBindingRegistry {
    fun listAll(context: Context): List<FunctionKitBindingEntry> {
        val installed =
            FunctionKitRegistry.listInstalled(context).ifEmpty {
                listOf(FunctionKitRegistry.resolve(context))
            }
        val kits =
            installed.filter { kit -> FunctionKitKitSettings.isKitEnabled(kit.id) }

        return kits
            .flatMap { kit ->
                val kitLabel = FunctionKitRegistry.displayName(context, kit)
                val kitIconAssetPath = kit.preferredIconAssetPath(96)
                kit.bindings.mapNotNull { binding ->
                    val triggers =
                        binding.triggers
                            .mapNotNull(FunctionKitBindingTrigger::parse)
                            .toSet()
                    if (triggers.isEmpty()) {
                        return@mapNotNull null
                    }

                    FunctionKitBindingEntry(
                        kitId = kit.id,
                        kitLabel = kitLabel,
                        kitIconAssetPath = kitIconAssetPath,
                        bindingId = binding.id,
                        title = binding.title,
                        triggers = triggers,
                        requestedPayloads = binding.requestedPayloads?.toSet(),
                        categories = binding.categories?.toSet(),
                        entry = binding.entry,
                        preferredPresentation = binding.preferredPresentation
                    )
                }
            }
            .sortedWith(
                compareBy<FunctionKitBindingEntry>(
                    { it.kitLabel.lowercase() },
                    { it.title.lowercase() },
                    { it.stableId }
                )
            )
    }

    fun listForTrigger(
        context: Context,
        trigger: FunctionKitBindingTrigger
    ): List<FunctionKitBindingEntry> =
        listAll(context).filter { trigger in it.triggers }
}
