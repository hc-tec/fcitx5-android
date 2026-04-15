package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePartialStabilizerTest {
    @Test
    fun `keeps previous tail when new partial shrinks`() {
        val stabilizer = VoicePartialStabilizer()

        assertEquals("openclaw improved", stabilizer.update("openclaw improved", stableLength = 8))
        assertEquals("openclaw improved", stabilizer.update("openclaw impro", stableLength = 8))
    }

    @Test
    fun `accepts longer tail when candidate expands`() {
        val stabilizer = VoicePartialStabilizer()

        assertEquals("hello wor", stabilizer.update("hello wor", stableLength = 6))
        assertEquals("hello world", stabilizer.update("hello world", stableLength = 6))
    }

    @Test
    fun `reset clears previous state`() {
        val stabilizer = VoicePartialStabilizer()

        stabilizer.update("first draft", stableLength = 5)
        stabilizer.reset()

        assertEquals("new phrase", stabilizer.update("new phrase", stableLength = 0))
    }
}
