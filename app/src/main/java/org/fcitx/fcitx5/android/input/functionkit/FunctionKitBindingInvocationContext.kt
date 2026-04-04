/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.json.JSONObject

internal object FunctionKitBindingInvocationContext {
    private const val CursorContextChars = 256
    private const val SelectionTextMaxChars = 8 * 1024
    private const val ClipboardTextMaxChars = 8 * 1024

    data class CaptureResult(
        val context: JSONObject,
        val providedPayloads: Set<String>,
        val payloadTruncated: Boolean
    )

    fun payloadLimits(): JSONObject =
        JSONObject()
            .put("cursorContextChars", CursorContextChars)
            .put("selectionTextMaxChars", SelectionTextMaxChars)
            .put("clipboardTextMaxChars", ClipboardTextMaxChars)

    fun capture(
        service: FcitxInputMethodService,
        requestedPayloads: Set<String>,
        candidateCount: Int = 0,
        preeditText: String = ""
    ): CaptureResult {
        val inputSnapshot =
            FunctionKitInputSnapshotReader.capture(
                service = service,
                cursorContextChars = CursorContextChars,
                selectionTextMaxChars = SelectionTextMaxChars
            )
        val selection = service.currentInputSelection
        val includeTextContext = requestedPayloads.any { it.startsWith("selection.") }

        val providedPayloads = mutableSetOf<String>()
        var payloadTruncated = false

        val payload =
            JSONObject()
                .put("sourcePackage", service.currentInputEditorInfo.packageName.orEmpty())
                .put("selectionStart", selection.start)
                .put("selectionEnd", selection.end)
                .put("inputType", service.currentInputEditorInfo.inputType)
                .put("candidateCount", candidateCount)
                .apply {
                    if ("selection.text" in requestedPayloads) {
                        val (selectedText, truncated) =
                            truncateText(inputSnapshot.selectedText, SelectionTextMaxChars)
                        put("selectedText", selectedText)
                        providedPayloads += "selection.text"
                        payloadTruncated = payloadTruncated || truncated
                    }
                    if ("selection.beforeCursor" in requestedPayloads) {
                        put("beforeCursor", inputSnapshot.beforeCursor)
                        providedPayloads += "selection.beforeCursor"
                    }
                    if ("selection.afterCursor" in requestedPayloads) {
                        put("afterCursor", inputSnapshot.afterCursor)
                        providedPayloads += "selection.afterCursor"
                    }
                    if (includeTextContext) {
                        val (normalizedPreeditText, truncated) =
                            truncateText(preeditText.trim(), SelectionTextMaxChars)
                        put("preeditText", normalizedPreeditText)
                        payloadTruncated = payloadTruncated || truncated
                    }
                }

        return CaptureResult(
            context = payload,
            providedPayloads = providedPayloads,
            payloadTruncated = payloadTruncated
        )
    }

    private fun truncateText(
        value: String,
        maxChars: Int
    ): Pair<String, Boolean> {
        if (value.length <= maxChars) {
            return value to false
        }
        return value.take(maxChars) to true
    }
}
