/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.BroadcastReceiver
import android.content.ClipboardManager as AndroidClipboardManager
import android.content.Context
import android.content.Intent
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import timber.log.Timber

/**
 * Notification click handler for the clipboard prompt (when overlay permission is missing).
 */
internal class ClipboardActionsPromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_OPEN_CLIPBOARD_ACTIONS) return
        ClipboardOverlayPromptManager.init(context)
        val text =
            intent.getStringExtra(EXTRA_CLIPBOARD_TEXT)
                .orEmpty()
                .ifBlank { ClipboardManager.lastEntry?.text.orEmpty() }
                .ifBlank {
                    val clip =
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? AndroidClipboardManager)
                            ?.primaryClip
                    clip?.getItemAt(0)?.text?.toString().orEmpty()
                }
        if (text.isBlank()) {
            Timber.d("Clipboard prompt clicked but clipboard text is empty")
            return
        }
        ClipboardOverlayPromptManager.handlePromptClicked(text)
    }

    companion object {
        const val ACTION_OPEN_CLIPBOARD_ACTIONS =
            "org.fcitx.fcitx5.android.action.OPEN_CLIPBOARD_ACTIONS"
        const val EXTRA_CLIPBOARD_TEXT = "org.fcitx.fcitx5.android.extra.CLIPBOARD_TEXT"
    }
}
