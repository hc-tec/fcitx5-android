/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

internal data class FunctionKitEffectiveMode(
    val localAiActive: Boolean,
    val executionMode: String,
    val transport: String,
    val modeMessage: String
)

internal fun resolveFunctionKitEffectiveMode(
    remoteEnabled: Boolean,
    executionMode: String,
    transport: String,
    modeMessage: String,
    localAiEligible: Boolean,
    localAiEndpointForMessage: String?
): FunctionKitEffectiveMode {
    val localAiActive = !remoteEnabled && localAiEligible
    if (!localAiActive) {
        return FunctionKitEffectiveMode(
            localAiActive = false,
            executionMode = executionMode,
            transport = transport,
            modeMessage = modeMessage
        )
    }

    val endpointHint = localAiEndpointForMessage?.trim().orEmpty()
    val hint = if (endpointHint.isNotBlank()) endpointHint else "shared Android AI chat"
    return FunctionKitEffectiveMode(
        localAiActive = true,
        executionMode = "direct-model",
        transport = "android-direct-http",
        modeMessage = "Using Android shared AI chat via $hint"
    )
}

