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
    fun camelCaseHotwordsExpandToSpacedAlias() {
        val result =
            VoiceContextHotwords.extract(
                selectedText = "OpenClaw",
                composingText = "",
                leftContext = ""
            )

        assertTrue(result.contains("OpenClaw"))
        assertTrue(result.contains("Open Claw"))
    }

    @Test
    fun rightContextContributesAdditionalHotwords() {
        val result =
            VoiceContextHotwords.extract(
                selectedText = "",
                composingText = "",
                leftContext = "这里在聊语音输入。",
                rightContext = "接下来还会提到 OpenClaw 和 KeyFlow。"
            )

        assertTrue(result.contains("OpenClaw"))
        assertTrue(result.contains("KeyFlow"))
    }

    @Test
    fun sherpaHotwordsUseSlashSeparatedPerStreamFormat() {
        val result = VoiceContextHotwords.formatForSherpaStream(listOf("OpenClaw", "KeyFlow"))

        assertEquals("OpenClaw/KeyFlow", result)
    }

    @Test
    fun sherpaHotwordsNormalizeReservedSeparators() {
        val result = VoiceContextHotwords.formatForSherpaStream(listOf("Open/Claw", "Key\nFlow"))

        assertEquals("Open Claw/Key Flow", result)
    }
}
