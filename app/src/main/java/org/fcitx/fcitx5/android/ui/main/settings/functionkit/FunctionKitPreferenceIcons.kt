/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.functionkit

import androidx.preference.Preference
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitDisplayIconLoader
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitManifest

internal fun Preference.applyFunctionKitPreferenceIcon(
    manifest: FunctionKitManifest,
    targetSizePx: Int = 96
) {
    val drawable = FunctionKitDisplayIconLoader.loadDrawable(context, manifest, targetSizePx)
    icon = drawable
    isIconSpaceReserved = drawable != null
}
