/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.json.JSONArray
import org.json.JSONObject

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

        val baseUrl = prefs.chatBaseUrl.getValue().trim()
        val apiKey = prefs.chatApiKey.getValue().trim()
        val model = prefs.chatModel.getValue().trim()

        // Ensure the debug build is usable out-of-the-box when bootstrap values are injected at build time
        // (e.g. from ~/.openclaw/.env). Release builds keep everything empty.
        if (!enabledSet) {
            prefs.chatEnabled.setValue(true)
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
        val baseUrl =
            when {
                effectivePrefs.baseUrlSet -> effectivePrefs.baseUrl.trim()
                bootstrapAvailable -> bootstrapConfig.baseUrl.trim()
                else -> effectivePrefs.baseUrl.trim()
            }
        val apiKey =
            when {
                effectivePrefs.apiKeySet -> effectivePrefs.apiKey.trim()
                bootstrapAvailable -> bootstrapConfig.apiKey.trim()
                else -> effectivePrefs.apiKey.trim()
            }
        val model =
            when {
                effectivePrefs.modelSet -> effectivePrefs.model.trim()
                bootstrapAvailable -> bootstrapConfig.model.trim()
                else -> effectivePrefs.model.trim()
            }
        val timeoutSeconds =
            when {
                effectivePrefs.timeoutSecondsSet -> effectivePrefs.timeoutSeconds.coerceAtLeast(1)
                bootstrapAvailable -> bootstrapConfig.timeoutSeconds.coerceAtLeast(1)
                else -> effectivePrefs.timeoutSeconds.coerceAtLeast(1)
            }
        val enabled =
            when {
                effectivePrefs.enabledSet -> effectivePrefs.enabled
                bootstrapAvailable -> true
                else -> effectivePrefs.enabled
            }
        val usesBootstrapDefaults =
            bootstrapAvailable &&
                (
                    (!effectivePrefs.enabledSet && enabled) ||
                        (!effectivePrefs.baseUrlSet && baseUrl == bootstrapConfig.baseUrl.trim()) ||
                        (!effectivePrefs.apiKeySet && apiKey == bootstrapConfig.apiKey.trim()) ||
                        (!effectivePrefs.modelSet && model == bootstrapConfig.model.trim()) ||
                        (!effectivePrefs.timeoutSecondsSet &&
                            timeoutSeconds == bootstrapConfig.timeoutSeconds.coerceAtLeast(1))
                )
        val hasExplicitPrefs =
            effectivePrefs.enabledSet ||
                effectivePrefs.baseUrlSet ||
                effectivePrefs.apiKeySet ||
                effectivePrefs.modelSet ||
                effectivePrefs.timeoutSecondsSet
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
        val normalizedFromStructured =
            structured?.optJSONArray("candidates")?.let { candidates ->
                JSONArray().apply {
                    for (index in 0 until minOf(candidates.length(), maxCandidates)) {
                        val candidate = candidates.optJSONObject(index) ?: continue
                        put(
                            JSONObject()
                                .put("id", candidate.optString("id").ifBlank { "candidate-${index + 1}" })
                                .put("text", candidate.optString("text").trim().take(maxCharsPerCandidate))
                                .put("tone", candidate.optString("tone").ifBlank { "AI" })
                                .put("risk", candidate.optString("risk").ifBlank { "medium" })
                                .put("rationale", candidate.optString("rationale").trim())
                                .put(
                                    "actions",
                                    JSONArray()
                                        .put(JSONObject().put("type", "insert").put("label", "插入"))
                                        .put(JSONObject().put("type", "replace").put("label", "替换"))
                                )
                        )
                    }
                }
            }
        if (normalizedFromStructured != null && normalizedFromStructured.length() > 0) {
            return normalizedFromStructured
        }

        val lines =
            rawText.lines()
                .map { it.trim().trimStart('-', '*', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', ')', ' ') }
                .filter { it.isNotBlank() }
        val fallbackTexts =
            if (lines.isNotEmpty()) {
                lines
            } else {
                listOf(rawText.trim())
            }

        return JSONArray().apply {
            fallbackTexts.take(maxCandidates).forEachIndexed { index, text ->
                put(
                    JSONObject()
                        .put("id", "fallback-${index + 1}")
                        .put("text", text.take(maxCharsPerCandidate))
                        .put("tone", "AI")
                        .put("risk", "medium")
                        .put("rationale", "由 Android 共享 AI chat 直接生成。")
                        .put(
                            "actions",
                            JSONArray()
                                .put(JSONObject().put("type", "insert").put("label", "插入"))
                                .put(JSONObject().put("type", "replace").put("label", "替换"))
                        )
                )
            }
        }
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
}
