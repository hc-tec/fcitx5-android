/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class FunctionKitAiChatBackendTest {
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
                          { "type": "text", "text": "第一段" },
                          { "type": "text", "text": "第二段" }
                        ]
                      }
                    }
                  ]
                }
                """.trimIndent()
            )

        assertEquals("第一段第二段", FunctionKitAiChatBackend.extractAssistantText(response))
    }

    @Test
    fun `normalizes candidates from fenced json`() {
        val rawText =
            """
            ```json
            {
              "candidates": [
                { "text": "收到，我先整理一版。", "tone": "稳妥", "risk": "low", "rationale": "确认动作" },
                { "text": "可以，我晚点发你看。", "tone": "直接", "risk": "medium", "rationale": "给出时间边界" }
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
        assertEquals("收到，我先整理一版。", candidates.getJSONObject(0).getString("text"))
        assertEquals("稳妥", candidates.getJSONObject(0).getString("tone"))
        assertTrue(candidates.getJSONObject(0).getJSONArray("actions").length() >= 2)
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
        assertEquals(false, request.getBoolean("stream"))
        assertEquals(2, request.getJSONArray("messages").length())
        assertEquals(256, request.getInt("max_tokens"))
        assertNotNull(request.opt("temperature"))
    }
}
