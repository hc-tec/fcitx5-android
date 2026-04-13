package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceContextHotwordsTest {
    @Test
    fun selectedTextWinsOverLeftContextDuplicates() {
        val result =
            VoiceContextHotwords.extract(
                selectedText = "OpenClaw",
                composingText = "",
                leftContext = "我们刚才讨论过 openclaw 和 KeyFlow 的语音输入。"
            )

        assertEquals("OpenClaw", result.first())
        assertTrue(result.contains("KeyFlow"))
        assertEquals(1, result.count { it.equals("OpenClaw", ignoreCase = true) })
    }

    @Test
    fun sherpaHotwordsUseUtf8LineFormat() {
        val result = VoiceContextHotwords.formatForSherpaStream(listOf("OpenClaw", "KeyFlow"))

        assertEquals("OpenClaw\nKeyFlow", result)
    }
}
