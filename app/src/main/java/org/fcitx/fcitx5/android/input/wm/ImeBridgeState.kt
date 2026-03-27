/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.wm

/**
 * Tracks whether the IME is currently being shown via a temporary "focus bridge" input target
 * (typically an overlay EditText used only to summon the IME when no app input field is focused).
 *
 * When active, committing text into the current input connection may not have any visible effect
 * for users because the target is an invisible bridge view. UI actions should fall back to
 * clipboard writes + user-facing hints in this mode.
 */
internal object ImeBridgeState {
    @Volatile
    private var activeSource: String? = null

    fun markActive(source: String) {
        activeSource = source
    }

    fun clearIfSource(source: String) {
        if (activeSource == source) {
            activeSource = null
        }
    }

    fun clear() {
        activeSource = null
    }

    fun isActive(): Boolean = activeSource != null

    fun isActive(source: String): Boolean = activeSource == source
}

