/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class HostAiChatConfig(
    val enabled: Boolean,
    val providerType: String,
    val configuredBaseUrl: String,
    val normalizedBaseUrl: String,
    val apiKey: String,
    val model: String,
    val timeoutSeconds: Int,
    val maxContextChars: Int,
    val configSource: String,
    val bootstrapAvailable: Boolean = false,
    val usesBootstrapDefaults: Boolean = false
) {
    val endpoint: String? =
        normalizedBaseUrl.takeIf { it.isNotBlank() }?.let {
            if (it.endsWith("/chat/completions")) {
                it
            } else {
                "$it/chat/completions"
            }
        }
    val apiKeyConfigured: Boolean = apiKey.isNotBlank()
    val isConfigured: Boolean = enabled && !endpoint.isNullOrBlank() && model.isNotBlank()
}

internal data class HostAiChatCompletion(
    val text: String,
    val structured: JSONObject?,
    val usage: JSONObject?
)

internal data class HostAiChatPrefsSnapshot(
    val enabled: Boolean,
    val enabledSet: Boolean,
    val baseUrl: String,
    val baseUrlSet: Boolean,
    val apiKey: String,
    val apiKeySet: Boolean,
    val model: String,
    val modelSet: Boolean,
    val timeoutSeconds: Int,
    val timeoutSecondsSet: Boolean
)

internal data class HostAiChatBootstrapConfig(
    val enabled: Boolean,
    val providerType: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val timeoutSeconds: Int
) {
    val isConfigured: Boolean =
        enabled && baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        fun fromBuildConfig(): HostAiChatBootstrapConfig =
            HostAiChatBootstrapConfig(
                enabled = BuildConfig.FUNCTION_KIT_DEBUG_AI_BOOTSTRAP_ENABLED,
                providerType =
                    BuildConfig.FUNCTION_KIT_DEBUG_AI_PROVIDER_TYPE
                        .trim()
                        .ifBlank { "openai-compatible" },
                baseUrl = BuildConfig.FUNCTION_KIT_DEBUG_AI_BASE_URL.trim(),
                apiKey = BuildConfig.FUNCTION_KIT_DEBUG_AI_API_KEY.trim(),
                model = BuildConfig.FUNCTION_KIT_DEBUG_AI_MODEL.trim(),
                timeoutSeconds = BuildConfig.FUNCTION_KIT_DEBUG_AI_TIMEOUT_SECONDS
            )
    }
}

internal object FunctionKitAiChatBackend {
    private const val DefaultMaxContextChars = 12_000
    private val looseTextRegex =
        Regex(""""text"\s*:\s*("(?:\\.|[^"\\])*")""")

    private fun normalizeBlankPrefs(prefs: HostAiChatPrefsSnapshot): HostAiChatPrefsSnapshot {
        fun normalizeString(
            value: String,
            set: Boolean
        ): Pair<String, Boolean> {
            val trimmed = value.trim()
            return if (set && trimmed.isBlank()) {
                "" to false
            } else {
                trimmed to set
            }
        }

        val (baseUrl, baseUrlSet) = normalizeString(prefs.baseUrl, prefs.baseUrlSet)
        val (apiKey, apiKeySet) = normalizeString(prefs.apiKey, prefs.apiKeySet)
        val (model, modelSet) = normalizeString(prefs.model, prefs.modelSet)
        val timeoutSeconds = prefs.timeoutSeconds.coerceAtLeast(1)
        val timeoutSecondsSet = prefs.timeoutSecondsSet && prefs.timeoutSeconds > 0

        return prefs.copy(
            baseUrl = baseUrl,
            baseUrlSet = baseUrlSet,
            apiKey = apiKey,
            apiKeySet = apiKeySet,
            model = model,
            modelSet = modelSet,
            timeoutSeconds = timeoutSeconds,
            timeoutSecondsSet = timeoutSecondsSet
        )
    }

    private fun maybeSeedPrefsFromBootstrap(
        prefs: AppPrefs.Ai,
        bootstrapConfig: HostAiChatBootstrapConfig
    ) {
        if (!BuildConfig.DEBUG) {
            return
        }
        if (!bootstrapConfig.isConfigured) {
            return
        }

        val sp = prefs.chatEnabled.sharedPreferences
        val enabledSet = sp.contains(prefs.chatEnabled.key)
        val baseUrlSet = sp.contains(prefs.chatBaseUrl.key)
        val apiKeySet = sp.contains(prefs.chatApiKey.key)
        val modelSet = sp.contains(prefs.chatModel.key)
        val timeoutSet = sp.contains(prefs.chatTimeoutSeconds.key)

        val enabled = prefs.chatEnabled.getValue()
        val baseUrl = prefs.chatBaseUrl.getValue().trim()
        val apiKey = prefs.chatApiKey.getValue().trim()
        val model = prefs.chatModel.getValue().trim()

        // Ensure the debug build is usable out-of-the-box when bootstrap values are injected at build time
        // (e.g. from ~/.openclaw/.env). Release builds keep everything empty.
        val explicitlyDisabledByUser = enabledSet && !enabled && baseUrl.isNotBlank() && model.isNotBlank()
        if (!explicitlyDisabledByUser) {
            // Repair stale debug prefs (e.g. enabled=false but URL/model empty) so users don't need to clear app data.
            if (!enabled && (!enabledSet || baseUrl.isBlank() || model.isBlank())) {
                prefs.chatEnabled.setValue(true)
            } else if (!enabledSet) {
                prefs.chatEnabled.setValue(true)
            }
        }
        if (!baseUrlSet || baseUrl.isBlank()) {
            prefs.chatBaseUrl.setValue(bootstrapConfig.baseUrl.trim())
        }
        if ((!apiKeySet || apiKey.isBlank()) && bootstrapConfig.apiKey.isNotBlank()) {
            prefs.chatApiKey.setValue(bootstrapConfig.apiKey.trim())
        }
        if (!modelSet || model.isBlank()) {
            prefs.chatModel.setValue(bootstrapConfig.model.trim())
        }
        if (!timeoutSet) {
            prefs.chatTimeoutSeconds.setValue(bootstrapConfig.timeoutSeconds.coerceAtLeast(1))
        }
    }

    // Instrumentation E2E runs may temporarily write stub config into shared prefs.
    // Avoid poisoning normal runtime by treating these sentinel values as unset.
    private fun looksLikeE2eStub(prefs: HostAiChatPrefsSnapshot): Boolean {
        val model = prefs.model.trim()
        val apiKey = prefs.apiKey.trim()
        return model == "e2e-stub" || apiKey == "e2e-test"
    }

    fun fromPrefs(
        prefs: AppPrefs.Ai,
        bootstrapConfig: HostAiChatBootstrapConfig = HostAiChatBootstrapConfig.fromBuildConfig()
    ): HostAiChatConfig {
        maybeSeedPrefsFromBootstrap(prefs, bootstrapConfig)
        return resolveConfig(
            prefs =
                HostAiChatPrefsSnapshot(
                    enabled = prefs.chatEnabled.getValue(),
                    enabledSet = prefs.chatEnabled.sharedPreferences.contains(prefs.chatEnabled.key),
                    baseUrl = prefs.chatBaseUrl.getValue().trim(),
                    baseUrlSet = prefs.chatBaseUrl.sharedPreferences.contains(prefs.chatBaseUrl.key),
                    apiKey = prefs.chatApiKey.getValue().trim(),
                    apiKeySet = prefs.chatApiKey.sharedPreferences.contains(prefs.chatApiKey.key),
                    model = prefs.chatModel.getValue().trim(),
                    modelSet = prefs.chatModel.sharedPreferences.contains(prefs.chatModel.key),
                    timeoutSeconds = prefs.chatTimeoutSeconds.getValue(),
                    timeoutSecondsSet =
                        prefs.chatTimeoutSeconds.sharedPreferences.contains(prefs.chatTimeoutSeconds.key)
                ),
            bootstrapConfig = bootstrapConfig
        )
    }

    internal fun resolveConfig(
        prefs: HostAiChatPrefsSnapshot,
        bootstrapConfig: HostAiChatBootstrapConfig = HostAiChatBootstrapConfig.fromBuildConfig()
    ): HostAiChatConfig {
        val bootstrapAvailable = bootstrapConfig.isConfigured
        val effectivePrefs =
            if (looksLikeE2eStub(prefs)) {
                prefs.copy(
                    enabled = false,
                    enabledSet = false,
                    baseUrl = "",
                    baseUrlSet = false,
                    apiKey = "",
                    apiKeySet = false,
                    model = "",
                    modelSet = false,
                    timeoutSecondsSet = false
                )
            } else {
                prefs
            }
        val repairedPrefs =
            if (BuildConfig.DEBUG && bootstrapAvailable) {
                val normalizedPrefs = normalizeBlankPrefs(effectivePrefs)
                val shouldTreatEnabledAsUnset =
                    normalizedPrefs.enabledSet &&
                        !normalizedPrefs.enabled &&
                        !(normalizedPrefs.baseUrlSet && normalizedPrefs.modelSet)

                normalizedPrefs.copy(
                    enabledSet = if (shouldTreatEnabledAsUnset) false else normalizedPrefs.enabledSet
                )
            } else {
                effectivePrefs
            }
        val baseUrl =
            when {
                repairedPrefs.baseUrlSet -> repairedPrefs.baseUrl.trim()
                bootstrapAvailable -> bootstrapConfig.baseUrl.trim()
                else -> repairedPrefs.baseUrl.trim()
            }
        val apiKey =
            when {
                repairedPrefs.apiKeySet -> repairedPrefs.apiKey.trim()
                bootstrapAvailable -> bootstrapConfig.apiKey.trim()
                else -> repairedPrefs.apiKey.trim()
            }
        val model =
            when {
                repairedPrefs.modelSet -> repairedPrefs.model.trim()
                bootstrapAvailable -> bootstrapConfig.model.trim()
                else -> repairedPrefs.model.trim()
            }
        val timeoutSeconds =
            when {
                repairedPrefs.timeoutSecondsSet -> repairedPrefs.timeoutSeconds.coerceAtLeast(1)
                bootstrapAvailable -> bootstrapConfig.timeoutSeconds.coerceAtLeast(1)
                else -> repairedPrefs.timeoutSeconds.coerceAtLeast(1)
            }
        val enabled =
            when {
                repairedPrefs.enabledSet -> repairedPrefs.enabled
                bootstrapAvailable -> true
                else -> repairedPrefs.enabled
            }
        val usesBootstrapDefaults =
            bootstrapAvailable &&
                (
                    (!repairedPrefs.enabledSet && enabled) ||
                        (!repairedPrefs.baseUrlSet && baseUrl == bootstrapConfig.baseUrl.trim()) ||
                        (!repairedPrefs.apiKeySet && apiKey == bootstrapConfig.apiKey.trim()) ||
                        (!repairedPrefs.modelSet && model == bootstrapConfig.model.trim()) ||
                        (!repairedPrefs.timeoutSecondsSet &&
                            timeoutSeconds == bootstrapConfig.timeoutSeconds.coerceAtLeast(1))
                )
        val hasExplicitPrefs =
            repairedPrefs.enabledSet ||
                repairedPrefs.baseUrlSet ||
                repairedPrefs.apiKeySet ||
                repairedPrefs.modelSet ||
                repairedPrefs.timeoutSecondsSet
        val configSource =
            when {
                usesBootstrapDefaults -> "debug-bootstrap"
                hasExplicitPrefs -> "shared-preferences"
                bootstrapAvailable -> "debug-bootstrap"
                else -> "unset"
            }

        return HostAiChatConfig(
            enabled = enabled,
            providerType = bootstrapConfig.providerType.ifBlank { "openai-compatible" },
            configuredBaseUrl = baseUrl,
            normalizedBaseUrl = baseUrl.trimEnd('/'),
            apiKey = apiKey,
            model = model,
            timeoutSeconds = timeoutSeconds,
            maxContextChars = DefaultMaxContextChars,
            configSource = configSource,
            bootstrapAvailable = bootstrapAvailable,
            usesBootstrapDefaults = usesBootstrapDefaults
        )
    }

    fun buildChatCompletionRequest(
        config: HostAiChatConfig,
        messages: JSONArray,
        temperature: Double?,
        maxOutputTokens: Int?
    ): JSONObject =
        JSONObject()
            .put("model", config.model)
            .put("stream", false)
            .put("messages", JSONArray(messages.toString()))
            .apply {
                temperature?.let { put("temperature", it) }
                maxOutputTokens?.takeIf { it > 0 }?.let { put("max_tokens", it) }
            }

    fun extractAssistantText(response: JSONObject): String {
        val choices = response.optJSONArray("choices") ?: return ""
        val firstChoice = choices.optJSONObject(0) ?: return ""
        val message = firstChoice.optJSONObject("message")
        val content = message?.opt("content") ?: firstChoice.opt("text")
        return extractMessageContent(content)
    }

    fun extractStructuredJson(rawText: String): JSONObject? {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) {
            return null
        }

        parseJsonObject(trimmed)?.let { return it }
        extractFencedJsonObject(trimmed)?.let(::parseJsonObject)?.let { return it }

        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return parseJsonObject(trimmed.substring(start, end + 1))
        }

        return null
    }

    fun normalizeCandidates(
        rawText: String,
        maxCandidates: Int,
        maxCharsPerCandidate: Int
    ): JSONArray {
        val structured = extractStructuredJson(rawText)
        structured?.optJSONArray("candidates")?.let { candidates ->
            val normalized = normalizeStructuredCandidates(candidates, maxCandidates, maxCharsPerCandidate)
            if (normalized.length() > 0) {
                return normalized
            }
        }

        extractStructuredJsonArray(rawText)?.let { candidates ->
            val normalized = normalizeStructuredCandidates(candidates, maxCandidates, maxCharsPerCandidate)
            if (normalized.length() > 0) {
                return normalized
            }
        }

        val extractedTexts = extractLooseTextCandidates(rawText, maxCandidates, maxCharsPerCandidate)
        if (extractedTexts.isNotEmpty()) {
            return JSONArray().apply {
                extractedTexts.forEachIndexed { index, text ->
                    put(buildFallbackCandidate("extracted-${index + 1}", text))
                }
            }
        }

        val lines =
            rawText.lines()
                .map { it.trim().trimStart('-', '*', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', ')', ' ') }
                .filter { it.isNotBlank() }
        val filteredLines = lines.filterNot(::looksLikeJsonNoiseLine).ifEmpty { lines }
        val fallbackTexts =
            if (filteredLines.isNotEmpty()) {
                filteredLines
            } else {
                listOf(rawText.trim())
            }

        return JSONArray().apply {
            fallbackTexts.take(maxCandidates).forEachIndexed { index, text ->
                put(buildFallbackCandidate("fallback-${index + 1}", text.take(maxCharsPerCandidate)))
            }
        }
    }

    private fun buildFallbackCandidate(id: String, text: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put("text", text.trim())
            .put("tone", "AI")
            .put("risk", "medium")
            .put("rationale", "由 Android 共享 AI chat 直接生成。")
            .put(
                "actions",
                JSONArray()
                    .put(JSONObject().put("type", "insert").put("label", "插入"))
                    .put(JSONObject().put("type", "replace").put("label", "替换"))
            )

    private fun normalizeStructuredCandidates(
        candidates: JSONArray,
        maxCandidates: Int,
        maxCharsPerCandidate: Int
    ): JSONArray {
        val normalized = JSONArray()
        for (index in 0 until candidates.length()) {
            if (normalized.length() >= maxCandidates) {
                break
            }

            val entry = candidates.opt(index)
            when (entry) {
                is JSONObject -> {
                    val text =
                        entry.optString("text")
                            .ifBlank { entry.optString("content") }
                            .trim()
                            .take(maxCharsPerCandidate)
                    if (text.isBlank()) {
                        continue
                    }
                    normalized.put(
                        JSONObject()
                            .put("id", entry.optString("id").ifBlank { "candidate-${normalized.length() + 1}" })
                            .put("text", text)
                            .put("tone", entry.optString("tone").ifBlank { "AI" })
                            .put("risk", entry.optString("risk").ifBlank { "medium" })
                            .put("rationale", entry.optString("rationale").trim())
                            .put(
                                "actions",
                                JSONArray()
                                    .put(JSONObject().put("type", "insert").put("label", "插入"))
                                    .put(JSONObject().put("type", "replace").put("label", "替换"))
                            )
                    )
                }
                is String -> {
                    val text = entry.trim().take(maxCharsPerCandidate)
                    if (text.isBlank()) {
                        continue
                    }
                    normalized.put(buildFallbackCandidate("candidate-${normalized.length() + 1}", text))
                }
            }
        }
        return normalized
    }

    private fun extractLooseTextCandidates(
        rawText: String,
        maxCandidates: Int,
        maxCharsPerCandidate: Int
    ): List<String> =
        looseTextRegex
            .findAll(rawText)
            .mapNotNull { match ->
                match.groupValues.getOrNull(1)
                    ?.let(::decodeJsonStringLiteral)
                    ?.trim()
                    ?.take(maxCharsPerCandidate)
                    ?.takeIf { it.isNotBlank() }
            }
            .distinct()
            .take(maxCandidates)
            .toList()

    private fun decodeJsonStringLiteral(literal: String): String? {
        val trimmed = literal.trim()
        if (trimmed.isBlank()) {
            return null
        }

        runCatching { JSONTokener(trimmed).nextValue() }
            .getOrNull()
            ?.let { decoded ->
                if (decoded is String) {
                    return decoded
                }
            }

        if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            return trimmed.substring(1, trimmed.length - 1)
                .replace("\\\\", "\\")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        }

        return null
    }

    private fun looksLikeJsonNoiseLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isBlank()) {
            return true
        }
        if (trimmed.startsWith("```")) {
            return true
        }
        if (trimmed == "{" || trimmed == "}" || trimmed == "[" || trimmed == "]") {
            return true
        }
        if (trimmed.startsWith("\"candidates\"", ignoreCase = true) || trimmed.startsWith("candidates", ignoreCase = true)) {
            return true
        }
        return trimmed.all { it.isWhitespace() || it in "{[]}:,`\"" }
    }

    private fun extractMessageContent(content: Any?): String =
        when (content) {
            is String -> content
            is JSONArray ->
                buildString {
                    for (index in 0 until content.length()) {
                        val part = content.opt(index)
                        when (part) {
                            is String -> append(part)
                            is JSONObject -> append(part.optString("text"))
                        }
                    }
                }
            else -> ""
        }.trim()

    private fun extractFencedJsonObject(rawText: String): String? {
        var searchStart = 0
        while (searchStart < rawText.length) {
            val fenceStart = rawText.indexOf("```", startIndex = searchStart)
            if (fenceStart < 0) {
                return null
            }

            val headerEnd = rawText.indexOf('\n', startIndex = fenceStart)
            if (headerEnd < 0) {
                return null
            }

            val header = rawText.substring(fenceStart + 3, headerEnd).trim()
            val contentStart = headerEnd + 1
            val fenceEnd = rawText.indexOf("```", startIndex = contentStart)
            if (fenceEnd < 0) {
                return null
            }

            if (header.isBlank() || header.equals("json", ignoreCase = true)) {
                return rawText.substring(contentStart, fenceEnd).trim()
            }

            searchStart = fenceEnd + 3
        }

        return null
    }

    private fun parseJsonObject(raw: String): JSONObject? =
        try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }

    private fun parseJsonArray(raw: String): JSONArray? =
        try {
            JSONArray(raw)
        } catch (_: Exception) {
            null
        }

    private fun extractStructuredJsonArray(rawText: String): JSONArray? {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) {
            return null
        }

        parseJsonArray(trimmed)?.let { return it }
        extractFencedJsonObject(trimmed)?.let(::parseJsonArray)?.let { return it }

        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        if (start >= 0 && end > start) {
            return parseJsonArray(trimmed.substring(start, end + 1))
        }

        return null
    }
}
