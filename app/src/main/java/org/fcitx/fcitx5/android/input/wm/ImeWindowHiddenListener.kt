/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.wm

/**
 * Optional hook for windows that need to reset temporary UI state when the IME is dismissed.
 */
interface ImeWindowHiddenListener {
    fun onImeWindowHidden()
}

