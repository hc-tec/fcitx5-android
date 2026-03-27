/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.net.Uri

internal object FunctionKitFilePickerRegistry {

    data class Result(
        val uris: List<Uri>
    )

    private val handlers = mutableMapOf<String, (Result) -> Unit>()

    @Synchronized
    fun register(
        requestId: String,
        handler: (Result) -> Unit
    ) {
        handlers[requestId] = handler
    }

    @Synchronized
    fun consume(requestId: String): ((Result) -> Unit)? = handlers.remove(requestId)

    fun deliver(
        requestId: String,
        uris: List<Uri>
    ) {
        val handler = consume(requestId) ?: return
        handler(Result(uris))
    }

    fun cancel(requestId: String) {
        deliver(requestId, emptyList())
    }
}

