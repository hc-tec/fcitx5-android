/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FunctionKitManifestTest {
    @Test
    fun `parse resolves browser style icon assets relative to manifest`() {
        val manifest =
            FunctionKitManifest.parse(
                root =
                    JSONObject(
                        """
                        {
                          "id": "quick-phrases",
                          "name": "Quick Phrases",
                          "icons": {
                            "32": "icons/quick-phrases.png",
                            "128": "icons/quick-phrases-large.png"
                          },
                          "entry": {
                            "bundle": {
                              "html": "ui/app/index.html"
                            }
                          }
                        }
                        """.trimIndent()
                    ),
                assetPath = "function-kits/quick-phrases/manifest.json",
                fallbackId = "quick-phrases",
                fallbackEntryHtmlAssetPath = "function-kits/quick-phrases/ui/app/index.html",
                fallbackRuntimePermissions = FunctionKitDefaults.supportedPermissions
            )

        assertEquals(
            listOf(
                "function-kits/quick-phrases/icons/quick-phrases-large.png",
                "function-kits/quick-phrases/icons/quick-phrases.png"
            ),
            manifest.iconAssets.map { it.assetPath }
        )
        assertEquals("function-kits/quick-phrases/icons/quick-phrases.png", manifest.preferredIconAssetPath(32))
        assertEquals(
            "function-kits/quick-phrases/icons/quick-phrases-large.png",
            manifest.preferredIconAssetPath(96)
        )
    }

    @Test
    fun `parse supports single icon string and icon arrays`() {
        val fromString =
            FunctionKitManifest.parse(
                root =
                    JSONObject(
                        """
                        {
                          "id": "chat-auto-reply",
                          "icon": "icons/chat-auto-reply.ico",
                          "entry": {
                            "bundle": {
                              "html": "ui/app/index.html"
                            }
                          }
                        }
                        """.trimIndent()
                    ),
                assetPath = "function-kits/chat-auto-reply/manifest.json",
                fallbackId = "chat-auto-reply",
                fallbackEntryHtmlAssetPath = "function-kits/chat-auto-reply/ui/app/index.html",
                fallbackRuntimePermissions = FunctionKitDefaults.supportedPermissions
            )
        val fromArray =
            FunctionKitManifest.parse(
                root =
                    JSONObject(
                        """
                        {
                          "id": "quick-phrases",
                          "icons": [
                            {
                              "src": "icons/quick-phrases.png",
                              "sizes": "48x48 96x96",
                              "type": "image/png"
                            }
                          ],
                          "entry": {
                            "bundle": {
                              "html": "ui/app/index.html"
                            }
                          }
                        }
                        """.trimIndent()
                    ),
                assetPath = "function-kits/quick-phrases/manifest.json",
                fallbackId = "quick-phrases",
                fallbackEntryHtmlAssetPath = "function-kits/quick-phrases/ui/app/index.html",
                fallbackRuntimePermissions = FunctionKitDefaults.supportedPermissions
            )

        assertEquals("function-kits/chat-auto-reply/icons/chat-auto-reply.ico", fromString.preferredIconAssetPath())
        assertEquals(listOf(48, 96), fromArray.iconAssets.single().sizes)
        assertEquals("image/png", fromArray.iconAssets.single().mimeType)
    }

    @Test
    fun `preferredIconAssetPath returns null when icon is not declared`() {
        val manifest =
            FunctionKitManifest.parse(
                root =
                    JSONObject(
                        """
                        {
                          "id": "plain-kit",
                          "entry": {
                            "bundle": {
                              "html": "ui/app/index.html"
                            }
                          }
                        }
                        """.trimIndent()
                    ),
                assetPath = "function-kits/plain-kit/manifest.json",
                fallbackId = "plain-kit",
                fallbackEntryHtmlAssetPath = "function-kits/plain-kit/ui/app/index.html",
                fallbackRuntimePermissions = FunctionKitDefaults.supportedPermissions
            )

        assertNull(manifest.preferredIconAssetPath(48))
    }

    @Test
    fun `parse supports bindings array`() {
        val manifest =
            FunctionKitManifest.parse(
                root =
                    JSONObject(
                        """
                        {
                          "id": "plain-kit",
                          "entry": {
                            "bundle": {
                              "html": "ui/app/index.html"
                            }
                          },
                          "bindings": [
                            {
                              "id": "clipboard.alpha",
                              "title": "Alpha",
                              "triggers": ["Clipboard", "Manual"],
                              "requestedPayloads": ["clipboard.text"],
                              "preferredPresentation": "clipboard-chip"
                            },
                            {
                              "id": "selection.beta",
                              "title": "Beta",
                              "triggers": ["selection", "manual"],
                              "requestedPayloads": ["selection.text"]
                            },
                            {
                              "id": "manual.gamma",
                              "title": "Gamma",
                              "triggers": ["manual"]
                            },
                            {
                              "id": "ignored.invalid-trigger",
                              "title": "Ignored",
                              "triggers": ["unknown"]
                            },
                            {
                              "id": "",
                              "title": "Ignored",
                              "triggers": ["manual"]
                            }
                          ]
                        }
                        """.trimIndent()
                    ),
                assetPath = "function-kits/plain-kit/manifest.json",
                fallbackId = "plain-kit",
                fallbackEntryHtmlAssetPath = "function-kits/plain-kit/ui/app/index.html",
                fallbackRuntimePermissions = FunctionKitDefaults.supportedPermissions
            )

        assertEquals(3, manifest.bindings.size)
        assertEquals("clipboard.alpha", manifest.bindings[0].id)
        assertEquals("Alpha", manifest.bindings[0].title)
        assertEquals(listOf("clipboard", "manual"), manifest.bindings[0].triggers)
        assertEquals(listOf("clipboard.text"), manifest.bindings[0].requestedPayloads)
        assertEquals("selection.beta", manifest.bindings[1].id)
        assertEquals(listOf("selection", "manual"), manifest.bindings[1].triggers)
        assertEquals(listOf("selection.text"), manifest.bindings[1].requestedPayloads)
        assertEquals("manual.gamma", manifest.bindings[2].id)
        assertEquals(null, manifest.bindings[2].requestedPayloads)
    }
}
