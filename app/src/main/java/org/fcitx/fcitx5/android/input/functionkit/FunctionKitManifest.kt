/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

internal data class FunctionKitManifest(
    val id: String,
    val entryHtmlAssetPath: String,
    val runtimePermissions: List<String>,
    val ai: AiConfig,
    val discovery: DiscoveryConfig,
    val manifestAssetPath: String
) {
    val remoteRenderPath: String = "/v1/function-kits/$id/render"

    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("entryHtmlAssetPath", entryHtmlAssetPath)
            .put("runtimePermissions", JSONArray(runtimePermissions))
            .put("remoteRenderPath", remoteRenderPath)
            .put("manifestAssetPath", manifestAssetPath)
            .put("ai", ai.toJson())
            .put("discovery", discovery.toJson())

    data class AiConfig(
        val executionMode: String,
        val backendHints: BackendHints
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("executionMode", executionMode)
                .put("backendHints", backendHints.toJson())

        data class BackendHints(
            val preferredBackendClass: String?,
            val preferredAdapter: String?,
            val latencyTier: String?,
            val latencyBudgetMs: Int?,
            val requireStructuredJson: Boolean,
            val requiredCapabilities: List<String>,
            val notes: List<String>
        ) {
            fun toJson(): JSONObject =
                JSONObject()
                    .put("preferredBackendClass", preferredBackendClass)
                    .put("preferredAdapter", preferredAdapter)
                    .put("latencyTier", latencyTier)
                    .put("latencyBudgetMs", latencyBudgetMs)
                    .put("requireStructuredJson", requireStructuredJson)
                    .put("requiredCapabilities", JSONArray(requiredCapabilities))
                    .put("notes", JSONArray(notes))
        }
    }

    data class DiscoveryConfig(
        val launchMode: String,
        val slashEnabled: Boolean,
        val slashCommands: List<String>,
        val slashAliases: List<String>,
        val slashTags: List<String>
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("launchMode", launchMode)
                .put("slashEnabled", slashEnabled)
                .put("commands", JSONArray(slashCommands))
                .put("aliases", JSONArray(slashAliases))
                .put("tags", JSONArray(slashTags))

        fun resolveSlashQuery(text: String): SlashQuery? {
            if (!slashEnabled) {
                return null
            }

            val trimmed = text.trim()
            if (trimmed.isBlank()) {
                return null
            }

            val match =
                SlashTokenRegex.find(trimmed)
                    ?: return null
            val raw = match.value.substringAfterLast(' ')
            val query = match.groups["query"]?.value.orEmpty().lowercase()
            val commands =
                slashCommands.filter { it.startsWith(query, ignoreCase = true) }
            val aliases =
                slashAliases.filter { it.startsWith(query, ignoreCase = true) }
            val tags =
                slashTags.filter { it.startsWith(query, ignoreCase = true) }

            return SlashQuery(
                raw = raw,
                query = query,
                browse = query.isBlank(),
                matched = commands.isNotEmpty() || aliases.isNotEmpty() || tags.isNotEmpty(),
                commands = commands,
                aliases = aliases,
                tags = tags,
                launchMode = launchMode
            )
        }

        data class SlashQuery(
            val raw: String,
            val query: String,
            val browse: Boolean,
            val matched: Boolean,
            val commands: List<String>,
            val aliases: List<String>,
            val tags: List<String>,
            val launchMode: String
        ) {
            fun toJson(): JSONObject =
                JSONObject()
                    .put("raw", raw)
                    .put("query", query)
                    .put("browse", browse)
                    .put("matched", matched)
                    .put("launchMode", launchMode)
                    .put("commands", JSONArray(commands))
                    .put("aliases", JSONArray(aliases))
                    .put("tags", JSONArray(tags))
        }

        companion object {
            private val SlashTokenRegex =
                Regex("""(?:^|[\s()\[\]{}"'`.,!?;:<>|\\])/(?<query>[a-zA-Z0-9_-]*)$""")
        }
    }

    companion object {
        fun loadFromAssets(
            context: Context,
            assetPath: String,
            fallbackId: String,
            fallbackEntryHtmlAssetPath: String,
            fallbackRuntimePermissions: Set<String>
        ): FunctionKitManifest {
            return try {
                val raw =
                    context.assets
                        .open(assetPath)
                        .bufferedReader(StandardCharsets.UTF_8)
                        .use { it.readText() }
                parse(
                    root = JSONObject(raw),
                    assetPath = assetPath,
                    fallbackId = fallbackId,
                    fallbackEntryHtmlAssetPath = fallbackEntryHtmlAssetPath,
                    fallbackRuntimePermissions = fallbackRuntimePermissions
                )
            } catch (_: Exception) {
                fallback(assetPath, fallbackId, fallbackEntryHtmlAssetPath, fallbackRuntimePermissions)
            }
        }

        private fun parse(
            root: JSONObject,
            assetPath: String,
            fallbackId: String,
            fallbackEntryHtmlAssetPath: String,
            fallbackRuntimePermissions: Set<String>
        ): FunctionKitManifest {
            val entry = root.optJSONObject("entry")
            val bundle = entry?.optJSONObject("bundle")
            val aiRoot = root.optJSONObject("ai")
            val backendHints = aiRoot?.optJSONObject("backendHints")
            val discoveryRoot = root.optJSONObject("discovery")
            val slashRoot = discoveryRoot?.optJSONObject("slash")
            val id = root.optString("id").ifBlank { fallbackId }
            val manifestAssetDirectory = assetPath.substringBeforeLast('/', missingDelimiterValue = "")

            return FunctionKitManifest(
                id = id,
                entryHtmlAssetPath =
                    bundle?.optString("html")
                        ?.takeUnless { it.isNullOrBlank() }
                        ?.let { resolveAssetPath(manifestAssetDirectory, it) }
                        ?: fallbackEntryHtmlAssetPath,
                runtimePermissions =
                    root.optJSONArray("runtimePermissions")
                        .toStringList()
                        .ifEmpty { fallbackRuntimePermissions.toList() },
                ai =
                    AiConfig(
                        executionMode = aiRoot?.optString("executionMode").ifNullOrBlank { "local-demo" },
                        backendHints =
                            AiConfig.BackendHints(
                                preferredBackendClass = backendHints?.optString("preferredBackendClass").nullIfBlank(),
                                preferredAdapter = backendHints?.optString("preferredAdapter").nullIfBlank(),
                                latencyTier = backendHints?.optString("latencyTier").nullIfBlank(),
                                latencyBudgetMs = backendHints?.optInt("latencyBudgetMs")?.takeIf { it > 0 },
                                requireStructuredJson = backendHints?.optBoolean("requireStructuredJson") == true,
                                requiredCapabilities = backendHints?.optJSONArray("requiredCapabilities").toStringList(),
                                notes = backendHints?.optJSONArray("notes").toStringList()
                            )
                    ),
                discovery =
                    DiscoveryConfig(
                        launchMode = discoveryRoot?.optString("launchMode").ifNullOrBlank { "panel-first" },
                        slashEnabled = slashRoot?.optBoolean("enabled") != false,
                        slashCommands = slashRoot?.optJSONArray("commands").toStringList(),
                        slashAliases = slashRoot?.optJSONArray("aliases").toStringList(),
                        slashTags = slashRoot?.optJSONArray("tags").toStringList()
                    ),
                manifestAssetPath = assetPath
            )
        }

        private fun fallback(
            assetPath: String,
            fallbackId: String,
            fallbackEntryHtmlAssetPath: String,
            fallbackRuntimePermissions: Set<String>
        ): FunctionKitManifest =
            FunctionKitManifest(
                id = fallbackId,
                entryHtmlAssetPath = fallbackEntryHtmlAssetPath,
                runtimePermissions = fallbackRuntimePermissions.toList(),
                ai =
                    AiConfig(
                        executionMode = "local-demo",
                        backendHints =
                            AiConfig.BackendHints(
                                preferredBackendClass = null,
                                preferredAdapter = null,
                                latencyTier = null,
                                latencyBudgetMs = null,
                                requireStructuredJson = false,
                                requiredCapabilities = emptyList(),
                                notes = emptyList()
                            )
                    ),
                discovery =
                    DiscoveryConfig(
                        launchMode = "panel-first",
                        slashEnabled = false,
                        slashCommands = emptyList(),
                        slashAliases = emptyList(),
                        slashTags = emptyList()
                    ),
                manifestAssetPath = assetPath
            )

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) {
                return emptyList()
            }

            return buildList {
                for (index in 0 until length()) {
                    optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }

        private fun resolveAssetPath(
            assetDirectory: String,
            relativePath: String
        ): String {
            val normalizedRelativePath = relativePath.replace('\\', '/').trim('/')
            return listOf(assetDirectory.trim('/'), normalizedRelativePath)
                .filter { it.isNotBlank() }
                .joinToString("/")
        }

        private fun String?.nullIfBlank(): String? = takeUnless { it.isNullOrBlank() }

        private inline fun String?.ifNullOrBlank(defaultValue: () -> String): String =
            takeUnless { it.isNullOrBlank() } ?: defaultValue()
    }
}
