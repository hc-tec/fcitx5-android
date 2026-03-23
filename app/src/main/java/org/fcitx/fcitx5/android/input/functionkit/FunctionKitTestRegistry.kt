/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.os.SystemClock
import android.webkit.WebView
import java.lang.ref.WeakReference

/**
 * Small in-process test hook used by instrumentation to interact with the active Function Kit WebView.
 *
 * This keeps the end-to-end tests stable by avoiding brittle WebView accessibility queries.
 */
internal object FunctionKitTestRegistry {
    private val lock = java.lang.Object()
    private val webViews = mutableMapOf<String, WeakReference<WebView>>()

    fun onWindowAttached(
        kitId: String,
        webView: WebView
    ) {
        synchronized(lock) {
            webViews[kitId] = WeakReference(webView)
            lock.notifyAll()
        }
    }

    fun onWindowDetached(kitId: String) {
        synchronized(lock) {
            webViews.remove(kitId)
            lock.notifyAll()
        }
    }

    fun clear() {
        synchronized(lock) {
            webViews.clear()
            lock.notifyAll()
        }
    }

    fun awaitWebView(
        kitId: String,
        timeoutMs: Long
    ): WebView {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        synchronized(lock) {
            while (true) {
                webViews[kitId]?.get()?.let { return it }
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0) {
                    break
                }
                lock.wait(remaining)
            }
        }
        throw AssertionError("Timed out waiting for Function Kit WebView. kitId=$kitId")
    }
}
