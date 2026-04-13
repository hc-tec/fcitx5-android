package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperRuntimePolicyTest {
    @Test
    fun `blacklisted vivo device forces cpu and fast model for balanced preference`() {
        val profile =
            WhisperRuntimePolicy.resolve(
                configuredPreference = VoiceModelPreference.Balanced,
                forceCpuFallback = false,
                persistedGpuDisabled = false,
                manufacturer = "vivo",
                model = "V2244A"
            )

        assertEquals(WhisperBackend.Cpu, profile.backend)
        assertEquals(VoiceModelPreference.Fast, profile.effectivePreference)
        assertTrue(profile.reason?.contains("device-gpu-blacklist") == true)
    }

    @Test
    fun `persisted gpu disable keeps explicit accurate preference`() {
        val profile =
            WhisperRuntimePolicy.resolve(
                configuredPreference = VoiceModelPreference.Accurate,
                forceCpuFallback = false,
                persistedGpuDisabled = true,
                manufacturer = "google",
                model = "Pixel 8"
            )

        assertEquals(WhisperBackend.Cpu, profile.backend)
        assertEquals(VoiceModelPreference.Accurate, profile.effectivePreference)
        assertEquals("persisted-gpu-disable", profile.reason)
    }

    @Test
    fun `healthy device keeps gpu and configured preference`() {
        val profile =
            WhisperRuntimePolicy.resolve(
                configuredPreference = VoiceModelPreference.Auto,
                forceCpuFallback = false,
                persistedGpuDisabled = false,
                manufacturer = "google",
                model = "Pixel 8"
            )

        assertEquals(WhisperBackend.Gpu, profile.backend)
        assertEquals(VoiceModelPreference.Auto, profile.effectivePreference)
        assertEquals(null, profile.reason)
    }
}
