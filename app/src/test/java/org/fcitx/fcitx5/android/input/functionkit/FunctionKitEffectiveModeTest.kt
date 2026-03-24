package org.fcitx.fcitx5.android.input.functionkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionKitEffectiveModeTest {
    @Test
    fun resolvesLocalAiWhenRemoteDisabled() {
        val resolved =
            resolveFunctionKitEffectiveMode(
                remoteEnabled = false,
                executionMode = "local-demo",
                transport = "local-webview",
                modeMessage = "demo",
                localAiEligible = true,
                localAiEndpointForMessage = "https://api.example.com/v1/chat/completions"
            )

        assertTrue(resolved.localAiActive)
        assertEquals("direct-model", resolved.executionMode)
        assertEquals("android-direct-http", resolved.transport)
        assertTrue(resolved.modeMessage.contains("api.example.com"))
    }

    @Test
    fun keepsConfigWhenLocalAiNotEligible() {
        val resolved =
            resolveFunctionKitEffectiveMode(
                remoteEnabled = false,
                executionMode = "local-demo",
                transport = "local-webview",
                modeMessage = "demo candidates",
                localAiEligible = false,
                localAiEndpointForMessage = "https://api.example.com"
            )

        assertEquals(false, resolved.localAiActive)
        assertEquals("local-demo", resolved.executionMode)
        assertEquals("local-webview", resolved.transport)
        assertEquals("demo candidates", resolved.modeMessage)
    }

    @Test
    fun keepsRemoteConfigWhenRemoteEnabled() {
        val resolved =
            resolveFunctionKitEffectiveMode(
                remoteEnabled = true,
                executionMode = "remote-agent",
                transport = "remote-http",
                modeMessage = "remote enabled",
                localAiEligible = true,
                localAiEndpointForMessage = "https://api.example.com"
            )

        assertEquals(false, resolved.localAiActive)
        assertEquals("remote-agent", resolved.executionMode)
        assertEquals("remote-http", resolved.transport)
        assertEquals("remote enabled", resolved.modeMessage)
    }
}

