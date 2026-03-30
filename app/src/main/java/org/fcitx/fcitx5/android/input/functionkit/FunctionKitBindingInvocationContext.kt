/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.json.JSONObject

internal object FunctionKitBindingInvocationContext {
    private const val CursorContextChars = 256

    fun capture(
        service: FcitxInputMethodService,
        requestedPayloads: Set<String>,
        candidateCount: Int = 0
    ): JSONObject {
        val inputConnection = service.currentInputConnection
        val beforeCursor = inputConnection?.getTextBeforeCursor(CursorContextChars, 0)?.toString().orEmpty()
        val afterCursor = inputConnection?.getTextAfterCursor(CursorContextChars, 0)?.toString().orEmpty()
        val selectedText = inputConnection?.getSelectedText(0)?.toString().orEmpty()
        val selection = service.currentInputSelection

        return JSONObject()
            .put("sourcePackage", service.currentInputEditorInfo.packageName.orEmpty())
            .put("selectionStart", selection.start)
            .put("selectionEnd", selection.end)
            .put("inputType", service.currentInputEditorInfo.inputType)
            .put("candidateCount", candidateCount)
            .apply {
                if ("selection.text" in requestedPayloads) {
                    put("selectedText", selectedText.trim())
                }
                if ("selection.beforeCursor" in requestedPayloads) {
                    put("beforeCursor", beforeCursor)
                }
                if ("selection.afterCursor" in requestedPayloads) {
                    put("afterCursor", afterCursor)
                }
            }
    }
}

