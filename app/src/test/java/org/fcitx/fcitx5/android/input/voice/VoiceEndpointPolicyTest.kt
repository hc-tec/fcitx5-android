package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceEndpointPolicyTest {
    @Test
    fun `quiet long recording is treated as blocked microphone`() {
        val decision =
            VoiceEndpointPolicy.decide(
                stats =
                    stats(
                        hasSpeech = false,
                        trailingSilenceMs = 2_100,
                        rms = 0.0001f,
                        peak = 0.001f
                    ),
                sampleCount = WhisperAudioRecorder.SAMPLE_RATE * 3,
                source = "unit"
            )

        assertEquals(VoiceListeningState.MIC_MAY_BE_BLOCKED, decision.state)
        assertFalse(decision.shouldAutoStop)
    }

    @Test
    fun `long trailing silence after speech enters ending soon state`() {
        val decision =
            VoiceEndpointPolicy.decide(
                stats =
                    stats(
                        hasSpeech = true,
                        trailingSilenceMs = 1_000,
                        rms = 0.02f,
                        peak = 0.12f
                    ),
                sampleCount = WhisperAudioRecorder.SAMPLE_RATE * 2,
                source = "unit"
            )

        assertEquals(VoiceListeningState.ENDING_SOON_VAD, decision.state)
        assertFalse(decision.shouldAutoStop)
    }

    @Test
    fun `very long trailing silence after speech auto stops`() {
        val decision =
            VoiceEndpointPolicy.decide(
                stats =
                    stats(
                        hasSpeech = true,
                        trailingSilenceMs = 1_850,
                        rms = 0.02f,
                        peak = 0.12f
                    ),
                sampleCount = WhisperAudioRecorder.SAMPLE_RATE * 3,
                source = "unit"
            )

        assertEquals(VoiceListeningState.ENDING_SOON_VAD, decision.state)
        assertTrue(decision.shouldAutoStop)
    }

    private fun stats(
        hasSpeech: Boolean,
        trailingSilenceMs: Long,
        rms: Float,
        peak: Float
    ): VoiceActivityStats =
        VoiceActivityStats(
            hasSpeech = hasSpeech,
            speechRatio = if (hasSpeech) 0.6 else 0.0,
            trailingSilenceMs = trailingSilenceMs,
            leadingSilenceMs = 0,
            totalFrames = 10,
            rms = rms,
            peak = peak,
            magnitude = 1.0f
        )
}
