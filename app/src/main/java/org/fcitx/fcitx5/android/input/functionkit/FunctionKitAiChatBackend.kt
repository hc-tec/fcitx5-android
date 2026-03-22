/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

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
    val maxContextChars: Int
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

internal object FunctionKitAiChatBackend {
    private const val DefaultMaxContextChars = 12_000
    private val JsonFenceRegex = Regex("""```(?:json)?\s*(\{.*?})\s*```""", RegexOption.DOT_MATCHES_ALL)

    fun fromPrefs(prefs: AppPrefs.Ai): HostAiChatConfig {
        val baseUrl = prefs.chatBaseUrl.getValue().trim()
        return HostAiChatConfig(
            enabled = prefs.chatEnabled.getValue(),
            providerType = "openai-compatible",
            configuredBaseUrl = baseUrl,
            normalizedBaseUrl = baseUrl.trimEnd('/'),
            apiKey = prefs.chatApiKey.getValue().trim(),
            model = prefs.chatModel.getValue().trim(),
            timeoutSeconds = prefs.chatTimeoutSeconds.getValue().coerceAtLeast(1),
            maxContextChars = DefaultMaxContextChars
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
        JsonFenceRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let(::parseJsonObject)?.let { return it }

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

    private fun parseJsonObject(raw: String): JSONObject? =
        try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
}
