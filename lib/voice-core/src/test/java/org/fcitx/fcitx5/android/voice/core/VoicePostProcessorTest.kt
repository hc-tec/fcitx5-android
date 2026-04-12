package org.fcitx.fcitx5.android.voice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VoicePostProcessorTest {
    private val store = InMemoryVoiceCorrectionStore()
    private val processor = VoicePostProcessor(store)
    private val context =
        VoiceContextSnapshot(
            sessionId = "voice_test",
            locale = "zh-CN",
            packageName = "com.example.chat",
            leftContext = "",
            selectedText = "",
            composingText = "",
            hotwords = listOf("OpenClaw"),
            sessionEntities = listOf("OpenClaw")
        )

    @Test
    fun `hotword bias rewrites coined term`() {
        val result =
            processor.process(
                result = VoiceRecognitionResult("Open Cloud 计划", confidence = 0.8),
                context = context,
                isFinal = true
            )

        assertEquals("OpenClaw 计划。", result.text)
        assertFalse(result.lowConfidenceSpans.isEmpty())
    }

    @Test
    fun `window bias rewrites coined term inside sentence`() {
        val result =
            processor.process(
                result = VoiceRecognitionResult("Open Cloud improved speech input quality", confidence = 0.8),
                context = context,
                isFinal = true
            )

        assertEquals("OpenClaw improved speech input quality。", result.text)
    }
}
