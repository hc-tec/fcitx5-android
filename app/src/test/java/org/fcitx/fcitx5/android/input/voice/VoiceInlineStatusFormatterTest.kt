package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceInlineStatusFormatterTest {

    @Test
    fun keepsBareStatusWhenTranscriptIsBlank() {
        assertEquals("正在聆听", VoiceInlineStatusFormatter.build("正在聆听", ""))
        assertEquals("正在聆听", VoiceInlineStatusFormatter.build("正在聆听", "   "))
    }

    @Test
    fun appendsTranscriptWhenAvailable() {
        assertEquals(
            "松开发送  OpenClaw 测试",
            VoiceInlineStatusFormatter.build("松开发送", "OpenClaw 测试")
        )
    }
}
