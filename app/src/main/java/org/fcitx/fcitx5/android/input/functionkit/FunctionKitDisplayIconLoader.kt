/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.Context
import android.graphics.drawable.Drawable

internal object FunctionKitDisplayIconLoader {
    fun loadDrawable(
        context: Context,
        manifest: FunctionKitManifest,
        targetSizePx: Int? = null
    ): Drawable? = FunctionKitIconLoader.loadDrawable(context, manifest.preferredIconAssetPath(targetSizePx))
}
