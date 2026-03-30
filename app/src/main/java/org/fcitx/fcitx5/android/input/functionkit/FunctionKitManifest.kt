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
    val name: String,
    val description: String?,
    val iconAssets: List<IconAsset>,
    val entryHtmlAssetPath: String,
    val runtimePermissions: List<String>,
    val ai: AiConfig,
    val bindings: List<Binding>,
    val discovery: DiscoveryConfig,
    val manifestAssetPath: String
) {
    data class IconAsset(
        val assetPath: String,
        val sizes: List<Int> = emptyList(),
        val mimeType: String? = null
    ) {
        val largestDeclaredSize: Int? = sizes.maxOrNull()
    }

    val remoteRenderPath: String = "/v1/function-kits/$id/render"

    fun preferredIconAssetPath(targetSizePx: Int? = null): String? {
        if (iconAssets.isEmpty()) {
            return null
        }

        val sizedIcons = iconAssets.filter { it.largestDeclaredSize != null }
        if (targetSizePx != null && sizedIcons.isNotEmpty()) {
            return sizedIcons.minWithOrNull(
                compareBy<IconAsset>(
                    { icon ->
                        val size = icon.largestDeclaredSize ?: Int.MAX_VALUE
                        if (size >= targetSizePx) 0 else 1
                    },
                    { icon ->
                        kotlin.math.abs((icon.largestDeclaredSize ?: targetSizePx) - targetSizePx)
                    },
                    { icon -> -(icon.largestDeclaredSize ?: 0) }
                )
            )?.assetPath
        }

        return sizedIcons.maxByOrNull { it.largestDeclaredSize ?: 0 }?.assetPath
            ?: iconAssets.firstOrNull()?.assetPath
    }

    fun toJson(): JSONObject =
            JSONObject()
                .put("id", id)
                .put("name", name)
                .put("description", description)
                .put(
                    "iconAssets",
                    JSONArray(
                        iconAssets.map { icon ->
                            JSONObject()
                                .put("assetPath", icon.assetPath)
                                .put("sizes", JSONArray(icon.sizes))
                                .put("mimeType", icon.mimeType)
                        }
                    )
                )
                .put("preferredIconAssetPath", preferredIconAssetPath())
                .put("entryHtmlAssetPath", entryHtmlAssetPath)
                .put("runtimePermissions", JSONArray(runtimePermissions))
                .put("remoteRenderPath", remoteRenderPath)
                .put("manifestAssetPath", manifestAssetPath)
                .put("ai", ai.toJson())
                .put(
                    "bindings",
                    JSONArray(
                        bindings.map { binding ->
                            binding.toJson()
                        }
                    )
                )
            .put("discovery", discovery.toJson())

    data class Binding(
        val id: String,
        val title: String,
        val triggers: List<String>,
        val requestedPayloads: List<String>? = null,
        val categories: List<String>? = null,
        val entry: JSONObject? = null,
        val preferredPresentation: String? = null
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("id", id)
                .put("title", title)
                .put("triggers", JSONArray(triggers))
                .apply {
                    requestedPayloads?.let { payloads ->
                        put("requestedPayloads", JSONArray(payloads))
                    }
                    categories?.let { values ->
                        put("categories", JSONArray(values))
                    }
                    entry?.let { value ->
                        put("entry", value)
                    }
                    preferredPresentation?.trim()?.takeIf { it.isNotBlank() }?.let { put("preferredPresentation", it) }
                }
    }

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

        internal fun parse(
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
                name = root.optString("name").ifBlank { id },
                description = root.optString("description").nullIfBlank(),
                iconAssets = parseIconAssets(root, manifestAssetDirectory),
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
                bindings = parseBindings(root),
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
                name = fallbackId,
                description = null,
                iconAssets = emptyList(),
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
                bindings = emptyList(),
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

        private fun parseBindings(root: JSONObject): List<Binding> {
            val bindingsNode = root.optJSONArray("bindings") ?: return emptyList()
            val bindings = mutableListOf<Binding>()
            val supportedTriggers = setOf("manual", "selection", "clipboard")
            val supportedRequestedPayloads =
                setOf(
                    "selection.text",
                    "selection.beforeCursor",
                    "selection.afterCursor",
                    "clipboard.text"
                )

            for (index in 0 until bindingsNode.length()) {
                val item = bindingsNode.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isBlank()) {
                    continue
                }

                val title = item.optString("title").trim()
                if (title.isBlank()) {
                    continue
                }

                val triggers =
                    item.optJSONArray("triggers")
                        .toStringList()
                        .map { it.lowercase() }
                        .filter { it in supportedTriggers }
                        .distinct()
                if (triggers.isEmpty()) {
                    continue
                }

                val requestedPayloads =
                    if (item.has("requestedPayloads")) {
                        item.optJSONArray("requestedPayloads")
                            .toStringList()
                            .map(String::trim)
                            .filter(String::isNotBlank)
                            .filter { it in supportedRequestedPayloads }
                            .distinct()
                    } else {
                        null
                    }

                val categories =
                    if (item.has("categories")) {
                        item.optJSONArray("categories")
                            .toStringList()
                            .map(String::trim)
                            .filter(String::isNotBlank)
                            .distinct()
                            .ifEmpty { null }
                    } else {
                        null
                    }

                bindings +=
                    Binding(
                        id = id,
                        title = title,
                        triggers = triggers,
                        requestedPayloads = requestedPayloads,
                        categories = categories,
                        entry = item.optJSONObject("entry"),
                        preferredPresentation = item.optString("preferredPresentation").nullIfBlank()
                    )
            }

            return bindings
                .distinctBy { it.id }
                .sortedWith(compareBy<Binding>({ it.title.lowercase() }, { it.id }))
        }

        private fun parseIconAssets(
            root: JSONObject,
            manifestAssetDirectory: String
        ): List<IconAsset> {
            val icons = mutableListOf<IconAsset>()

            root.optString("icon")
                .takeIf { it.isNotBlank() }
                ?.let { path ->
                    icons +=
                        IconAsset(
                            assetPath = resolveAssetPath(manifestAssetDirectory, path),
                            mimeType = mimeTypeFromAssetPath(path)
                        )
                }

            when (val iconsNode = root.opt("icons")) {
                is JSONObject -> {
                    val keys = iconsNode.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val path = iconsNode.optString(key)
                        if (path.isBlank()) {
                            continue
                        }
                        icons +=
                            IconAsset(
                                assetPath = resolveAssetPath(manifestAssetDirectory, path),
                                sizes = listOfNotNull(key.toIntOrNull()),
                                mimeType = mimeTypeFromAssetPath(path)
                            )
                    }
                }
                is JSONArray -> {
                    for (index in 0 until iconsNode.length()) {
                        when (val item = iconsNode.opt(index)) {
                            is String ->
                                if (item.isNotBlank()) {
                                    icons +=
                                        IconAsset(
                                            assetPath = resolveAssetPath(manifestAssetDirectory, item),
                                            mimeType = mimeTypeFromAssetPath(item)
                                        )
                                }
                            is JSONObject -> {
                                val src = item.optString("src")
                                if (src.isBlank()) {
                                    continue
                                }
                                icons +=
                                    IconAsset(
                                        assetPath = resolveAssetPath(manifestAssetDirectory, src),
                                        sizes = parseSizes(item.optString("sizes")),
                                        mimeType = item.optString("type").nullIfBlank() ?: mimeTypeFromAssetPath(src)
                                    )
                            }
                        }
                    }
                }
            }

            return icons
                .distinctBy { it.assetPath }
                .sortedWith(
                    compareByDescending<IconAsset> { it.largestDeclaredSize ?: -1 }
                        .thenBy { it.assetPath }
                )
        }

        private fun parseSizes(value: String?): List<Int> =
            value.orEmpty()
                .split(' ')
                .mapNotNull { token ->
                    token.substringBefore('x', missingDelimiterValue = token)
                        .trim()
                        .toIntOrNull()
                }
                .distinct()

        private fun mimeTypeFromAssetPath(assetPath: String): String? =
            when (assetPath.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "jpg", "jpeg" -> "image/jpeg"
                "bmp" -> "image/bmp"
                "ico" -> "image/x-icon"
                else -> null
            }

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
