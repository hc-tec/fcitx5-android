/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FunctionKitRegistryTest {
    @Test
    fun `selectPreferredKitId honors explicit request when available`() {
        val selected =
            FunctionKitRegistry.selectPreferredKitId(
                availableKitIds = listOf("chat-auto-reply", "quick-phrases"),
                requestedKitId = "quick-phrases"
            )

        assertEquals("quick-phrases", selected)
    }

    @Test
    fun `selectPreferredKitId falls back to preferred default kit`() {
        val selected =
            FunctionKitRegistry.selectPreferredKitId(
                availableKitIds = listOf("quick-phrases", "chat-auto-reply"),
                requestedKitId = null
            )

        assertEquals(FunctionKitDefaults.kitId, selected)
    }

    @Test
    fun `selectPreferredKitId falls back to first available kit when default missing`() {
        val selected =
            FunctionKitRegistry.selectPreferredKitId(
                availableKitIds = listOf("quick-phrases", "calendar-brief"),
                requestedKitId = null
            )

        assertEquals("quick-phrases", selected)
    }

    @Test
    fun `selectPreferredKitId returns null when no kits available`() {
        val selected =
            FunctionKitRegistry.selectPreferredKitId(
                availableKitIds = emptyList(),
                requestedKitId = null
            )

        assertNull(selected)
    }
}
