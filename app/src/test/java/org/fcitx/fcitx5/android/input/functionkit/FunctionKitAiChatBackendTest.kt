/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionKitAiChatBackendTest {
    @Test
    fun `falls back to debug bootstrap ai config when prefs are unset`() {
        val config =
            FunctionKitAiChatBackend.resolveConfig(
                prefs = emptyPrefsSnapshot(),
                bootstrapConfig = debugBootstrapConfig()
            )

        assertTrue(config.enabled)
        assertTrue(config.bootstrapAvailable)
        assertTrue(config.usesBootstrapDefaults)
        assertEquals("openai-compatible", config.providerType)
        assertEquals("https://api.deepseek.com/v1/chat/completions", config.endpoint)
        assertEquals("deepseek-chat", config.model)
        assertEquals("secret", config.apiKey)
        assertEquals(35, config.timeoutSeconds)
    }

    @Test
    fun `explicit disable stays disabled even when debug bootstrap is available`() {
        val config =
            FunctionKitAiChatBackend.resolveConfig(
                prefs =
                    emptyPrefsSnapshot(
                        enabled = false,
                        enabledSet = true
                    ),
                bootstrapConfig = debugBootstrapConfig()
            )

        assertFalse(config.enabled)
        assertTrue(config.bootstrapAvailable)
        assertTrue(config.usesBootstrapDefaults)
        assertFalse(config.isConfigured)
        assertEquals("https://api.deepseek.com/v1/chat/completions", config.endpoint)
    }

    @Test
    fun `user supplied fields override bootstrap while unset fields still fall back`() {
        val config =
            FunctionKitAiChatBackend.resolveConfig(
                prefs =
                    emptyPrefsSnapshot(
                        enabled = true,
                        enabledSet = true,
                        baseUrl = "http://127.0.0.1:11434/v1",
                        baseUrlSet = true
                    ),
                bootstrapConfig = debugBootstrapConfig()
            )

        assertTrue(config.enabled)
        assertTrue(config.bootstrapAvailable)
        assertTrue(config.usesBootstrapDefaults)
        assertEquals("http://127.0.0.1:11434/v1/chat/completions", config.endpoint)
        assertEquals("secret", config.apiKey)
        assertEquals("deepseek-chat", config.model)
        assertEquals(35, config.timeoutSeconds)
    }

    @Test
    fun `explicit blank values do not silently fall back to bootstrap`() {
        val config =
            FunctionKitAiChatBackend.resolveConfig(
                prefs =
                    emptyPrefsSnapshot(
                        enabled = true,
                        enabledSet = true,
                        baseUrl = "",
                        baseUrlSet = true,
                        apiKey = "",
                        apiKeySet = true,
                        model = "",
                        modelSet = true
                    ),
                bootstrapConfig = debugBootstrapConfig()
            )

        assertTrue(config.enabled)
        assertTrue(config.bootstrapAvailable)
        assertNull(config.endpoint)
        assertEquals("", config.apiKey)
        assertEquals("", config.model)
        assertFalse(config.isConfigured)
    }

    @Test
    fun `keeps full chat completions endpoint when already provided`() {
        val config =
            HostAiChatConfig(
                enabled = true,
                providerType = "openai-compatible",
                configuredBaseUrl = "http://127.0.0.1:11434/v1/chat/completions",
                normalizedBaseUrl = "http://127.0.0.1:11434/v1/chat/completions",
                apiKey = "",
                model = "llama3.1:8b-instruct",
                timeoutSeconds = 20,
                maxContextChars = 12_000
            )

        assertEquals("http://127.0.0.1:11434/v1/chat/completions", config.endpoint)
    }

    @Test
    fun `extracts assistant text from array content responses`() {
        val response =
            JSONObject(
                """
                {
                  "choices": [
                    {
                      "message": {
                        "content": [
                          { "type": "text", "text": "First " },
                          { "type": "text", "text": "second" }
                        ]
                      }
                    }
                  ]
                }
                """.trimIndent()
            )

        assertEquals("First second", FunctionKitAiChatBackend.extractAssistantText(response))
    }

    @Test
    fun `normalizes candidates from fenced json`() {
        val rawText =
            """
            ```json
            {
              "candidates": [
                { "text": "Received. I will clean this up first.", "tone": "steady", "risk": "low", "rationale": "Acknowledges the action" },
                { "text": "Works for me. I will send it later tonight.", "tone": "direct", "risk": "medium", "rationale": "Sets a time boundary" }
              ]
            }
            ```
            """.trimIndent()

        val candidates =
            FunctionKitAiChatBackend.normalizeCandidates(
                rawText = rawText,
                maxCandidates = 3,
                maxCharsPerCandidate = 120
            )

        assertEquals(2, candidates.length())
        assertEquals("Received. I will clean this up first.", candidates.getJSONObject(0).getString("text"))
        assertEquals("steady", candidates.getJSONObject(0).getString("tone"))
        assertTrue(candidates.getJSONObject(0).getJSONArray("actions").length() >= 2)
    }

    @Test
    fun `extracts structured json from fenced block with surrounding prose`() {
        val rawText =
            """
            Here is the result:

            ```json
            {
              "candidates": [
                { "text": "Let me confirm that first." }
              ]
            }
            ```

            Done.
            """.trimIndent()

        val structured = FunctionKitAiChatBackend.extractStructuredJson(rawText)

        assertNotNull(structured)
        assertEquals(
            "Let me confirm that first.",
            structured!!.getJSONArray("candidates").getJSONObject(0).getString("text")
        )
    }

    @Test
    fun `builds openai compatible chat request`() {
        val config =
            HostAiChatConfig(
                enabled = true,
                providerType = "openai-compatible",
                configuredBaseUrl = "http://127.0.0.1:11434/v1",
                normalizedBaseUrl = "http://127.0.0.1:11434/v1",
                apiKey = "",
                model = "deepseek-chat",
                timeoutSeconds = 20,
                maxContextChars = 12_000
            )
        val request =
            FunctionKitAiChatBackend.buildChatCompletionRequest(
                config = config,
                messages =
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", "You are helpful"))
                        .put(JSONObject().put("role", "user").put("content", "hello")),
                temperature = 0.4,
                maxOutputTokens = 256
            )

        assertEquals("deepseek-chat", request.getString("model"))
        assertFalse(request.getBoolean("stream"))
        assertEquals(2, request.getJSONArray("messages").length())
        assertEquals(256, request.getInt("max_tokens"))
        assertNotNull(request.opt("temperature"))
    }

    private fun emptyPrefsSnapshot(
        enabled: Boolean = false,
        enabledSet: Boolean = false,
        baseUrl: String = "",
        baseUrlSet: Boolean = false,
        apiKey: String = "",
        apiKeySet: Boolean = false,
        model: String = "",
        modelSet: Boolean = false,
        timeoutSeconds: Int = 20,
        timeoutSecondsSet: Boolean = false
    ): HostAiChatPrefsSnapshot =
        HostAiChatPrefsSnapshot(
            enabled = enabled,
            enabledSet = enabledSet,
            baseUrl = baseUrl,
            baseUrlSet = baseUrlSet,
            apiKey = apiKey,
            apiKeySet = apiKeySet,
            model = model,
            modelSet = modelSet,
            timeoutSeconds = timeoutSeconds,
            timeoutSecondsSet = timeoutSecondsSet
        )

    private fun debugBootstrapConfig(): HostAiChatBootstrapConfig =
        HostAiChatBootstrapConfig(
            enabled = true,
            providerType = "openai-compatible",
            baseUrl = "https://api.deepseek.com/v1",
            apiKey = "secret",
            model = "deepseek-chat",
            timeoutSeconds = 35
        )
}
