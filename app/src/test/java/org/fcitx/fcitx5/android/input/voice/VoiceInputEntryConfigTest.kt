package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputEntryConfigTest {
    @Test
    fun `built-in engine prewarms when toolbar entry is enabled`() {
        val config =
            VoiceInputEntryConfig(
                showVoiceInputButton = true,
                spaceLongPressBehavior = SpaceLongPressBehavior.None,
                voiceInputMode = VoiceInputMode.BuiltInSpeechRecognizer
            )

        assertTrue(config.shouldPrewarmBuiltInEngine())
    }

    @Test
    fun `built-in engine prewarms when space long press owns voice entry`() {
        val config =
            VoiceInputEntryConfig(
                showVoiceInputButton = false,
                spaceLongPressBehavior = SpaceLongPressBehavior.VoiceInput,
                voiceInputMode = VoiceInputMode.BuiltInSpeechRecognizer
            )

        assertTrue(config.hasEnabledEntry())
        assertTrue(config.shouldPrewarmBuiltInEngine())
    }

    @Test
    fun `system voice ime does not trigger built-in prewarm`() {
        val config =
            VoiceInputEntryConfig(
                showVoiceInputButton = true,
                spaceLongPressBehavior = SpaceLongPressBehavior.VoiceInput,
                voiceInputMode = VoiceInputMode.SystemVoiceIme
            )

        assertTrue(config.hasEnabledEntry())
        assertFalse(config.shouldPrewarmBuiltInEngine())
    }
}
