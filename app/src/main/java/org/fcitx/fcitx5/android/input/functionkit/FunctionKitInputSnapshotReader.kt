/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.view.inputmethod.ExtractedTextRequest
import androidx.core.view.inputmethod.EditorInfoCompat
import org.fcitx.fcitx5.android.input.FcitxInputMethodService

internal data class FunctionKitInputSnapshot(
    val beforeCursor: String,
    val afterCursor: String,
    val selectedText: String
)

internal object FunctionKitInputSnapshotReader {
    private const val CachedSnapshotMaxAgeMs = 60_000L
    private const val WarmCursorContextChars = 2_048
    private const val WarmSelectionTextMaxChars = 8 * 1_024

    private data class CachedSnapshot(
        val packageName: String,
        val fieldId: Int,
        val inputType: Int,
        val snapshot: FunctionKitInputSnapshot,
        val updatedAtEpochMs: Long
    )

    @Volatile
    private var cachedSnapshot: CachedSnapshot? = null

    fun capture(
        service: FcitxInputMethodService,
        cursorContextChars: Int,
        selectionTextMaxChars: Int
    ): FunctionKitInputSnapshot {
        val inputConnection = service.currentInputConnection
        val editorInfo = service.currentInputEditorInfo
        val packageName = editorInfo.packageName.orEmpty()
        val fieldId = editorInfo.fieldId
        val inputType = editorInfo.inputType
        val extractedText = inputConnection?.getExtractedText(ExtractedTextRequest(), 0)

        val beforeCursor =
            inputConnection?.getTextBeforeCursor(cursorContextChars, 0)?.toString().orEmpty()
                .ifBlank {
                    extractBeforeCursor(extractedText, cursorContextChars)
                }
                .ifBlank {
                    EditorInfoCompat.getInitialTextBeforeCursor(editorInfo, cursorContextChars, 0)
                        ?.toString()
                        .orEmpty()
                }
        val afterCursor =
            inputConnection?.getTextAfterCursor(cursorContextChars, 0)?.toString().orEmpty()
                .ifBlank {
                    extractAfterCursor(extractedText, cursorContextChars)
                }
                .ifBlank {
                    EditorInfoCompat.getInitialTextAfterCursor(editorInfo, cursorContextChars, 0)
                        ?.toString()
                        .orEmpty()
                }
        val selectedText =
            inputConnection?.getSelectedText(0)?.toString().orEmpty()
                .ifBlank {
                    extractSelectedText(extractedText)
                }
                .ifBlank {
                    EditorInfoCompat.getInitialSelectedText(editorInfo, 0)?.toString().orEmpty()
                }
                .trim()
                .let { value ->
                    if (value.length <= selectionTextMaxChars) {
                        value
                    } else {
                        value.take(selectionTextMaxChars)
                    }
                }

        val snapshot =
            FunctionKitInputSnapshot(
                beforeCursor = beforeCursor,
                afterCursor = afterCursor,
                selectedText = selectedText
            )

        if (snapshot.hasText()) {
            cachedSnapshot =
                CachedSnapshot(
                    packageName = packageName,
                    fieldId = fieldId,
                    inputType = inputType,
                    snapshot = snapshot,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            return snapshot
        }

        val cached = cachedSnapshot
        if (cached != null &&
            cached.packageName == packageName &&
            cached.fieldId == fieldId &&
            cached.inputType == inputType &&
            System.currentTimeMillis() - cached.updatedAtEpochMs <= CachedSnapshotMaxAgeMs
        ) {
            return cached.snapshot.truncate(
                cursorContextChars = cursorContextChars,
                selectionTextMaxChars = selectionTextMaxChars
            )
        }

        return snapshot
    }

    fun warmUp(service: FcitxInputMethodService) {
        capture(
            service = service,
            cursorContextChars = WarmCursorContextChars,
            selectionTextMaxChars = WarmSelectionTextMaxChars
        )
    }

    private fun FunctionKitInputSnapshot.hasText(): Boolean =
        beforeCursor.isNotBlank() || afterCursor.isNotBlank() || selectedText.isNotBlank()

    private fun FunctionKitInputSnapshot.truncate(
        cursorContextChars: Int,
        selectionTextMaxChars: Int
    ): FunctionKitInputSnapshot {
        fun trimCursor(value: String): String {
            if (value.length <= cursorContextChars) {
                return value
            }
            return value.takeLast(cursorContextChars)
        }

        fun trimSelection(value: String): String {
            if (value.length <= selectionTextMaxChars) {
                return value
            }
            return value.take(selectionTextMaxChars)
        }

        return FunctionKitInputSnapshot(
            beforeCursor = trimCursor(beforeCursor),
            afterCursor = afterCursor.take(cursorContextChars),
            selectedText = trimSelection(selectedText)
        )
    }

    fun clearCache() {
        cachedSnapshot = null
    }

    private fun extractBeforeCursor(
        extractedText: android.view.inputmethod.ExtractedText?,
        cursorContextChars: Int
    ): String {
        val text = extractedText?.text?.toString().orEmpty()
        if (text.isBlank()) {
            return ""
        }
        val selectionEnd = extractedText?.selectionEnd ?: text.length
        val safeEnd = selectionEnd.coerceIn(0, text.length)
        return text.substring(0, safeEnd).takeLast(cursorContextChars)
    }

    private fun extractAfterCursor(
        extractedText: android.view.inputmethod.ExtractedText?,
        cursorContextChars: Int
    ): String {
        val text = extractedText?.text?.toString().orEmpty()
        if (text.isBlank()) {
            return ""
        }
        val selectionEnd = extractedText?.selectionEnd ?: text.length
        val safeEnd = selectionEnd.coerceIn(0, text.length)
        return text.substring(safeEnd).take(cursorContextChars)
    }

    private fun extractSelectedText(extractedText: android.view.inputmethod.ExtractedText?): String {
        val text = extractedText?.text?.toString().orEmpty()
        if (text.isBlank()) {
            return ""
        }
        val selectionStart = extractedText?.selectionStart ?: 0
        val selectionEnd = extractedText?.selectionEnd ?: selectionStart
        val safeStart = selectionStart.coerceIn(0, text.length)
        val safeEnd = selectionEnd.coerceIn(safeStart, text.length)
        return text.substring(safeStart, safeEnd)
    }
}
