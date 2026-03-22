/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

internal data class ComposerDraftBufferState(
    val text: String = "",
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0
)

internal object FunctionKitComposerDraftBuffer {
    fun normalize(state: ComposerDraftBufferState): ComposerDraftBufferState {
        val start = state.selectionStart.coerceIn(0, state.text.length)
        val end = state.selectionEnd.coerceIn(0, state.text.length)
        return state.copy(
            selectionStart = minOf(start, end),
            selectionEnd = maxOf(start, end)
        )
    }

    fun commitText(
        state: ComposerDraftBufferState,
        text: String,
        cursor: Int = -1
    ): ComposerDraftBufferState {
        val normalized = normalize(state)
        val nextText =
            buildString {
                append(normalized.text, 0, normalized.selectionStart)
                append(text)
                append(normalized.text, normalized.selectionEnd, normalized.text.length)
            }
        val cursorTarget =
            if (cursor == -1) {
                normalized.selectionStart + text.length
            } else {
                (normalized.selectionStart + cursor).coerceIn(0, nextText.length)
            }
        return ComposerDraftBufferState(
            text = nextText,
            selectionStart = cursorTarget,
            selectionEnd = cursorTarget
        )
    }

    fun backspace(state: ComposerDraftBufferState): ComposerDraftBufferState {
        val normalized = normalize(state)
        if (normalized.selectionStart != normalized.selectionEnd) {
            return commitText(normalized, "", 0)
        }
        if (normalized.selectionStart <= 0) {
            return normalized
        }
        val deleteStart = normalized.text.offsetByCodePoints(normalized.selectionStart, -1)
        return deleteRange(normalized, deleteStart, normalized.selectionStart)
    }

    fun deleteSurrounding(
        state: ComposerDraftBufferState,
        before: Int,
        after: Int
    ): ComposerDraftBufferState {
        val normalized = normalize(state)
        val deleteStart =
            offsetByCodePointsClamped(
                text = normalized.text,
                index = normalized.selectionStart,
                delta = -before.coerceAtLeast(0)
            )
        val deleteEnd =
            offsetByCodePointsClamped(
                text = normalized.text,
                index = normalized.selectionEnd,
                delta = after.coerceAtLeast(0)
            )
        return deleteRange(normalized, deleteStart, deleteEnd)
    }

    fun applySelectionOffset(
        state: ComposerDraftBufferState,
        offsetStart: Int,
        offsetEnd: Int = 0
    ): ComposerDraftBufferState {
        val normalized = normalize(state)
        val start = (normalized.selectionStart + offsetStart).coerceIn(0, normalized.text.length)
        val end = (normalized.selectionEnd + offsetEnd).coerceIn(0, normalized.text.length)
        return normalized.copy(
            selectionStart = minOf(start, end),
            selectionEnd = maxOf(start, end)
        )
    }

    fun cancelSelection(state: ComposerDraftBufferState): ComposerDraftBufferState {
        val normalized = normalize(state)
        return normalized.copy(
            selectionStart = normalized.selectionEnd,
            selectionEnd = normalized.selectionEnd
        )
    }

    fun setSelection(
        state: ComposerDraftBufferState,
        start: Int,
        end: Int
    ): ComposerDraftBufferState =
        normalize(
            state.copy(
                selectionStart = start,
                selectionEnd = end
            )
        )

    private fun deleteRange(
        state: ComposerDraftBufferState,
        start: Int,
        end: Int
    ): ComposerDraftBufferState {
        val deleteStart = minOf(start, end).coerceIn(0, state.text.length)
        val deleteEnd = maxOf(start, end).coerceIn(0, state.text.length)
        if (deleteStart == deleteEnd) {
            return normalize(state)
        }
        val nextText =
            buildString {
                append(state.text, 0, deleteStart)
                append(state.text, deleteEnd, state.text.length)
            }
        return ComposerDraftBufferState(
            text = nextText,
            selectionStart = deleteStart,
            selectionEnd = deleteStart
        )
    }

    private fun offsetByCodePointsClamped(
        text: String,
        index: Int,
        delta: Int
    ): Int {
        if (delta == 0) {
            return index.coerceIn(0, text.length)
        }

        var target = index.coerceIn(0, text.length)
        repeat(kotlin.math.abs(delta)) {
            target =
                if (delta > 0) {
                    if (target >= text.length) {
                        return text.length
                    }
                    text.offsetByCodePoints(target, 1)
                } else {
                    if (target <= 0) {
                        return 0
                    }
                    text.offsetByCodePoints(target, -1)
                }
        }
        return target
    }
}
