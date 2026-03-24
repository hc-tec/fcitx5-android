package org.fcitx.fcitx5.android.input.functionkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionKitAiChatBackendTest {
    @Test
    fun repairsBlankButSetPrefsWhenBootstrapAvailable() {
        val resolved =
            FunctionKitAiChatBackend.resolveConfig(
                prefs =
                    HostAiChatPrefsSnapshot(
                        enabled = false,
                        enabledSet = true,
                        baseUrl = "",
                        baseUrlSet = true,
                        apiKey = "sk-leftover",
                        apiKeySet = true,
                        model = "   ",
                        modelSet = true,
                        timeoutSeconds = 0,
                        timeoutSecondsSet = true
                    ),
                bootstrapConfig =
                    HostAiChatBootstrapConfig(
                        enabled = true,
                        providerType = "openai-compatible",
                        baseUrl = "https://api.deepseek.com/v1",
                        apiKey = "sk-test",
                        model = "deepseek-chat",
                        timeoutSeconds = 20
                    )
            )

        assertTrue(resolved.enabled)
        assertEquals("https://api.deepseek.com/v1", resolved.configuredBaseUrl)
        assertEquals("deepseek-chat", resolved.model)
        assertEquals("debug-bootstrap", resolved.configSource)
        assertTrue(resolved.usesBootstrapDefaults)
        assertTrue(resolved.isConfigured)
    }

    @Test
    fun prefersBootstrapWhenPrefsLookLikeE2eStub() {
        val resolved =
            FunctionKitAiChatBackend.resolveConfig(
                prefs =
                    HostAiChatPrefsSnapshot(
                        enabled = true,
                        enabledSet = true,
                        baseUrl = "http://localhost:40989/v1",
                        baseUrlSet = true,
                        apiKey = "e2e-test",
                        apiKeySet = true,
                        model = "e2e-stub",
                        modelSet = true,
                        timeoutSeconds = 10,
                        timeoutSecondsSet = true
                    ),
                bootstrapConfig =
                    HostAiChatBootstrapConfig(
                        enabled = true,
                        providerType = "openai-compatible",
                        baseUrl = "https://api.deepseek.com/v1",
                        apiKey = "sk-test",
                        model = "deepseek-chat",
                        timeoutSeconds = 20
                    )
            )

        assertTrue(resolved.enabled)
        assertEquals("https://api.deepseek.com/v1", resolved.configuredBaseUrl)
        assertEquals("deepseek-chat", resolved.model)
        assertEquals("debug-bootstrap", resolved.configSource)
        assertTrue(resolved.isConfigured)
    }

    @Test
    fun disablesE2eStubWhenNoBootstrapAvailable() {
        val resolved =
            FunctionKitAiChatBackend.resolveConfig(
                prefs =
                    HostAiChatPrefsSnapshot(
                        enabled = true,
                        enabledSet = true,
                        baseUrl = "http://localhost:40989/v1",
                        baseUrlSet = true,
                        apiKey = "e2e-test",
                        apiKeySet = true,
                        model = "e2e-stub",
                        modelSet = true,
                        timeoutSeconds = 10,
                        timeoutSecondsSet = true
                    ),
                bootstrapConfig =
                    HostAiChatBootstrapConfig(
                        enabled = false,
                        providerType = "openai-compatible",
                        baseUrl = "",
                        apiKey = "",
                        model = "",
                        timeoutSeconds = 20
                    )
            )

        assertFalse(resolved.enabled)
        assertEquals("", resolved.configuredBaseUrl)
        assertEquals("", resolved.model)
        assertEquals("unset", resolved.configSource)
        assertFalse(resolved.isConfigured)
    }

    @Test
    fun prefersSharedPreferencesWhenUserConfigIsPresent() {
        val resolved =
            FunctionKitAiChatBackend.resolveConfig(
                prefs =
                    HostAiChatPrefsSnapshot(
                        enabled = true,
                        enabledSet = true,
                        baseUrl = "https://example.com/v1",
                        baseUrlSet = true,
                        apiKey = "user-secret",
                        apiKeySet = true,
                        model = "custom-model",
                        modelSet = true,
                        timeoutSeconds = 45,
                        timeoutSecondsSet = true
                    ),
                bootstrapConfig =
                    HostAiChatBootstrapConfig(
                        enabled = true,
                        providerType = "openai-compatible",
                        baseUrl = "https://api.deepseek.com/v1",
                        apiKey = "bootstrap-secret",
                        model = "deepseek-chat",
                        timeoutSeconds = 20
                    )
            )

        assertEquals("https://example.com/v1", resolved.configuredBaseUrl)
        assertEquals("custom-model", resolved.model)
        assertEquals(45, resolved.timeoutSeconds)
        assertEquals("shared-preferences", resolved.configSource)
        assertFalse(resolved.usesBootstrapDefaults)
    }
}
