package org.fcitx.fcitx5.android.input.functionkit

import org.junit.Assert.assertEquals
import org.junit.Test

class FunctionKitRuntimePermissionResolverTest {
    @Test
    fun resolveSupportedFiltersByHostSupportedAllowlist() {
        val supported =
            FunctionKitRuntimePermissionResolver.resolveSupported(
                manifestDeclared = listOf("context.read", "network.fetch", "evil.permission", "context.read"),
                hostSupported = listOf("context.read", "network.fetch")
            )

        assertEquals(listOf("context.read", "network.fetch"), supported)
    }

    @Test
    fun resolveRequestedFallsBackToSupportedWhenUiRequestedEmpty() {
        val requested =
            FunctionKitRuntimePermissionResolver.resolveRequested(
                uiRequested = emptyList(),
                supported = listOf("context.read", "ai.chat")
            )

        assertEquals(listOf("context.read", "ai.chat"), requested)
    }

    @Test
    fun resolveRequestedRespectsSubsetWhenUiRequestedProvided() {
        val requested =
            FunctionKitRuntimePermissionResolver.resolveRequested(
                uiRequested = listOf("ai.chat"),
                supported = listOf("context.read", "ai.chat")
            )

        assertEquals(listOf("ai.chat"), requested)
    }

    @Test
    fun resolveRequestedDoesNotFallBackWhenUiRequestedNonEmptyButInvalid() {
        val requested =
            FunctionKitRuntimePermissionResolver.resolveRequested(
                uiRequested = listOf("evil.permission"),
                supported = listOf("context.read", "ai.chat")
            )

        assertEquals(emptyList<String>(), requested)
    }
}

