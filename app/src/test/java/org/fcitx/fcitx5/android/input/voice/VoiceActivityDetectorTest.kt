package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceActivityDetectorTest {
    @Test
    fun `silence is not classified as speech`() {
        val stats = VoiceActivityDetector.analyze(FloatArray(WhisperAudioRecorder.SAMPLE_RATE / 2))

        assertFalse(stats.hasSpeech)
        assertTrue(stats.trailingSilenceMs >= 450)
    }

    @Test
    fun `energetic samples are classified as speech`() {
        val samples =
            FloatArray(WhisperAudioRecorder.SAMPLE_RATE / 2) { index ->
                if (index % 4 == 0) 0.15f else -0.12f
            }

        val stats = VoiceActivityDetector.analyze(samples)

        assertTrue(stats.hasSpeech)
        assertTrue(stats.speechRatio > 0.5)
        assertTrue(stats.trailingSilenceMs < 120)
    }
}
