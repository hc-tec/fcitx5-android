package org.fcitx.fcitx5.android.voice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCorrectionStoreTest {
    @Test
    fun `repeated corrections increase weight`() {
        val store = InMemoryVoiceCorrectionStore()

        store.learn(
            VoiceCorrectionFeedback(
                sessionId = "voice_1",
                originalText = "Open Cloud",
                correctedText = "OpenClaw",
                packageName = "com.example.chat",
                locale = "zh-CN"
            )
        )
        store.learn(
            VoiceCorrectionFeedback(
                sessionId = "voice_2",
                originalText = "Open Cloud",
                correctedText = "OpenClaw",
                packageName = "com.example.chat",
                locale = "zh-CN"
            )
        )

        val entries = store.findEntries(locale = "zh-CN", packageName = "com.example.chat")
        assertEquals(1, entries.size)
        assertEquals("OpenClaw", entries.first().correct)
        assertTrue(entries.first().weight >= 2.0)
    }
}
