package org.fcitx.fcitx5.android.voice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VoiceSessionManagerTest {
    @Test
    fun `session memory survives multiple utterances`() {
        val manager = VoiceSessionManager()
        val sessionId =
            manager.createSession(
                VoiceSessionRequest(
                    locale = "en-US",
                    packageName = "com.example.chat",
                    hotwords = listOf("OpenClaw")
                )
            )

        val first =
            manager.processFinal(
                sessionId,
                VoiceRecognitionResult("Open Cloud", confidence = 0.8)
            )
        val second =
            manager.processFinal(
                sessionId,
                VoiceRecognitionResult("Open Cloud improved speech input quality", confidence = 0.8)
            )

        assertNotNull(first)
        assertNotNull(second)
        assertEquals("OpenClaw.", first?.text)
        assertEquals("OpenClaw improved speech input quality.", second?.text)
    }

    @Test
    fun `stable length tracks common prefix for partials`() {
        val manager = VoiceSessionManager()
        val sessionId = manager.createSession(VoiceSessionRequest(locale = "en-US"))

        val first =
            manager.processPartial(
                sessionId,
                VoiceRecognitionResult("hello wor", confidence = 0.5)
            )
        val second =
            manager.processPartial(
                sessionId,
                VoiceRecognitionResult("hello world", confidence = 0.6)
            )

        assertEquals(0, first?.stableLength)
        assertEquals(9, second?.stableLength)
    }
}
