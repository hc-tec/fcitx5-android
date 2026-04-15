package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.voice.core.VoiceSessionRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class SherpaOnnxRuntimeRoutingTest {
    @Test
    fun `auto route prefers mixed model when english hotword is present`() {
        val decision =
            SherpaOnnxRuntimeRouting.decide(
                configuredPreference = SherpaOnnxModelPreference.Auto,
                request =
                    VoiceSessionRequest(
                        locale = "zh-CN",
                        hotwords = listOf("OpenClaw", "开黑")
                    ),
                deviceProfile = unconstrainedDevice()
            )

        assertEquals(SherpaOnnxModelPreference.MixedZhEn, decision.effectivePreference)
    }

    @Test
    fun `auto route prefers hotword model for strong chinese context`() {
        val decision =
            SherpaOnnxRuntimeRouting.decide(
                configuredPreference = SherpaOnnxModelPreference.Auto,
                request =
                    VoiceSessionRequest(
                        locale = "zh-CN",
                        selectedText = "刚刚在讨论语音输入项目的体验",
                        hotwords = listOf("输入法", "语音输入", "热词增强")
                    ),
                deviceProfile = unconstrainedDevice()
            )

        assertEquals(SherpaOnnxModelPreference.HotwordEnhanced, decision.effectivePreference)
    }

    @Test
    fun `auto route prefers fast ctc on constrained device without context pressure`() {
        val decision =
            SherpaOnnxRuntimeRouting.decide(
                configuredPreference = SherpaOnnxModelPreference.Auto,
                request = VoiceSessionRequest(locale = "zh-CN"),
                deviceProfile =
                    SherpaOnnxRuntimeRouting.DeviceProfile(
                        constrainedDevice = true,
                        lowRamDevice = true,
                        cpuCount = 4,
                        memoryClassMb = 128,
                        totalMemoryMb = 3_072
                    )
            )

        assertEquals(SherpaOnnxModelPreference.FastCtc, decision.effectivePreference)
    }

    @Test
    fun `explicit profile is respected`() {
        val decision =
            SherpaOnnxRuntimeRouting.decide(
                configuredPreference = SherpaOnnxModelPreference.HotwordEnhanced,
                request =
                    VoiceSessionRequest(
                        locale = "zh-CN",
                        hotwords = listOf("OpenClaw")
                    ),
                deviceProfile = unconstrainedDevice()
            )

        assertEquals(SherpaOnnxModelPreference.HotwordEnhanced, decision.effectivePreference)
    }

    private fun unconstrainedDevice(): SherpaOnnxRuntimeRouting.DeviceProfile =
        SherpaOnnxRuntimeRouting.DeviceProfile(
            constrainedDevice = false,
            lowRamDevice = false,
            cpuCount = 8,
            memoryClassMb = 256,
            totalMemoryMb = 8_192
        )
}
