/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionKitHostDiagnosticsTest {
    @Test
    fun `filters webview console events from ui status stream`() {
        assertFalse(
            FunctionKitHostDiagnostics.shouldSurfaceHostEventToUi(
                "WebView console[TIP] [FunctionKitRuntimeSDK:duplicate-message] host-bridge.ready.ack-1"
            )
        )
    }

    @Test
    fun `keeps non console host events visible to ui`() {
        assertTrue(FunctionKitHostDiagnostics.shouldSurfaceHostEventToUi("宿主握手完成"))
    }

    @Test
    fun `build display name includes short git hash`() {
        assertEquals(
            "5.1.0-debug (9fa072d)",
            FunctionKitHostDiagnostics.buildDisplayName(
                versionName = "5.1.0-debug",
                gitHash = "9fa072d1234567890"
            )
        )
    }
}
