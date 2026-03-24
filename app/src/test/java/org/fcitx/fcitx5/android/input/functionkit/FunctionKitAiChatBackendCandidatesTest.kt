package org.fcitx.fcitx5.android.input.functionkit

import org.junit.Assert.assertEquals
import org.junit.Test

class FunctionKitAiChatBackendCandidatesTest {
    @Test
    fun acceptsStringCandidatesArray() {
        val normalized =
            FunctionKitAiChatBackend.normalizeCandidates(
                rawText = """{"candidates":["你好呀","在干嘛","哈哈"]}""",
                maxCandidates = 3,
                maxCharsPerCandidate = 64
            )

        assertEquals(3, normalized.length())
        assertEquals("你好呀", normalized.optJSONObject(0)?.optString("text"))
        assertEquals("在干嘛", normalized.optJSONObject(1)?.optString("text"))
        assertEquals("哈哈", normalized.optJSONObject(2)?.optString("text"))
    }

    @Test
    fun extractsTextFromInvalidJson() {
        val rawText =
            """
            ```json
            {
              "candidates": [
                {"text": "你好呀", "tone": "warm", "risk": "low", "rationale": "友好打招呼"},
                {"text": "在忙吗？", "tone": "warm", "risk": "low", "rationale": "询问对方是否方便"},
                {"text": "收到，我马上看。", "tone": "balanced", "risk": "medium", "rationale": "不承诺具体时间"},
              ]
            }
            """
                .trimIndent()

        val normalized =
            FunctionKitAiChatBackend.normalizeCandidates(
                rawText = rawText,
                maxCandidates = 3,
                maxCharsPerCandidate = 64
            )

        assertEquals(3, normalized.length())
        assertEquals("你好呀", normalized.optJSONObject(0)?.optString("text"))
        assertEquals("在忙吗？", normalized.optJSONObject(1)?.optString("text"))
        assertEquals("收到，我马上看。", normalized.optJSONObject(2)?.optString("text"))
    }
}

