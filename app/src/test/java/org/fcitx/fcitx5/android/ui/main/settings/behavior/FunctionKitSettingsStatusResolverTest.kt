/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import org.fcitx.fcitx5.android.input.functionkit.FunctionKitDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionKitSettingsStatusResolverTest {
    @Test
    fun `resolve trims trailing slash and detects loopback`() {
        val status =
            FunctionKitSettingsStatusResolver.resolve(
                requestedPermissions = FunctionKitDefaults.supportedPermissions,
                enabledPermissions = FunctionKitDefaults.supportedPermissions,
                remoteInferenceEnabled = true,
                remoteBaseUrl = " http://127.0.0.1:18789/ ",
                remoteAuthToken = "",
                timeoutSeconds = 0,
                showToolbarButton = true,
                expandToolbarByDefault = true
            )

        assertEquals("http://127.0.0.1:18789", status.normalizedRemoteBaseUrl)
        assertTrue(status.remoteConfigured)
        assertTrue(status.remoteUsesLoopback)
        assertEquals(1, status.timeoutSeconds)
        assertTrue(status.quickAccessVisibleOnKeyboardStart)
    }

    @Test
    fun `resolve groups core and remote permissions`() {
        val enabledPermissions =
            setOf(
                "context.read",
                "input.insert",
                "input.replace",
                "storage.read",
                "storage.write",
                "network.fetch",
                "ai.chat"
            )

        val status =
            FunctionKitSettingsStatusResolver.resolve(
                requestedPermissions = FunctionKitDefaults.supportedPermissions,
                enabledPermissions = enabledPermissions,
                remoteInferenceEnabled = true,
                remoteBaseUrl = "http://100.109.100.33:18789",
                remoteAuthToken = "token",
                timeoutSeconds = 20,
                showToolbarButton = false,
                expandToolbarByDefault = false
            )

        assertEquals(5, status.corePermissions.enabled)
        assertEquals(9, status.corePermissions.total)
        assertEquals(
            listOf(
                "input.commitImage",
                "candidates.regenerate",
                "settings.open",
                "panel.state.write"
            ),
            status.corePermissions.disabledPermissions
        )
        assertEquals(2, status.remotePermissions.enabled)
        assertEquals(4, status.remotePermissions.total)
        assertEquals(
            listOf("ai.agent.list", "ai.agent.run"),
            status.remotePermissions.disabledPermissions
        )
        assertFalse(status.showToolbarButton)
        assertFalse(status.expandToolbarByDefault)
        assertFalse(status.quickAccessVisibleOnKeyboardStart)
        assertTrue(status.remoteAuthConfigured)
    }

    @Test
    fun `resolve handles blank remote url and unrequested groups`() {
        val requestedPermissions =
            listOf(
                "context.read",
                "input.insert",
                "input.replace"
            )
        val status =
            FunctionKitSettingsStatusResolver.resolve(
                requestedPermissions = requestedPermissions,
                enabledPermissions = requestedPermissions,
                remoteInferenceEnabled = true,
                remoteBaseUrl = "   ",
                remoteAuthToken = "",
                timeoutSeconds = 30,
                showToolbarButton = true,
                expandToolbarByDefault = false
            )

        assertFalse(status.remoteConfigured)
        assertFalse(status.remoteUsesLoopback)
        assertEquals(0, status.remotePermissions.total)
        assertFalse(status.quickAccessVisibleOnKeyboardStart)
    }

    @Test
    fun `resolve marks pinned shortcut as hidden on start when toolbar stays collapsed`() {
        val status =
            FunctionKitSettingsStatusResolver.resolve(
                requestedPermissions = FunctionKitDefaults.supportedPermissions,
                enabledPermissions = FunctionKitDefaults.supportedPermissions,
                remoteInferenceEnabled = false,
                remoteBaseUrl = "",
                remoteAuthToken = "",
                timeoutSeconds = 20,
                showToolbarButton = true,
                expandToolbarByDefault = false
            )

        assertTrue(status.showToolbarButton)
        assertFalse(status.expandToolbarByDefault)
        assertFalse(status.quickAccessVisibleOnKeyboardStart)
    }
}
