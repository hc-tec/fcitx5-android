/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import org.junit.Assert.assertEquals
import org.junit.Test

class FunctionKitQuickAccessOrdererTest {
    @Test
    fun `pinned kits come first then by last used`() {
        assertEquals(
            listOf("b", "c", "a"),
            FunctionKitQuickAccessOrderer.orderKitIds(
                kitIds = listOf("a", "b", "c"),
                pinnedKitIds = setOf("b"),
                lastUsedAtEpochMsByKitId =
                    mapOf(
                        "a" to 10L,
                        "b" to 5L,
                        "c" to 20L
                    )
            )
        )
    }

    @Test
    fun `ties fall back to original order`() {
        assertEquals(
            listOf("a", "b", "c"),
            FunctionKitQuickAccessOrderer.orderKitIds(
                kitIds = listOf("a", "b", "c"),
                pinnedKitIds = emptySet(),
                lastUsedAtEpochMsByKitId = emptyMap()
            )
        )
    }
}

