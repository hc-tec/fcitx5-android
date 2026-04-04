/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.updateLayoutParams
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.SnackbarContentLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import splitties.dimensions.dp
import splitties.views.dsl.core.withTheme

internal object FunctionKitSnackbars {

    private var snackbarInstance: Snackbar? = null

    @SuppressLint("RestrictedApi")
    fun showKitInstalled(
        context: Context,
        windowManager: InputWindowManager,
        theme: Theme,
        kitId: String,
        replaced: Boolean,
        onOpen: () -> Unit
    ) {
        snackbarInstance?.dismiss()
        val snackbarCtx = context.withTheme(R.style.InputViewSnackbarTheme)
        val message =
            context.getString(
                if (replaced) {
                    R.string.function_kit_download_center_installed_replaced
                } else {
                    R.string.function_kit_download_center_installed
                },
                kitId
            )

        snackbarInstance =
            Snackbar.make(snackbarCtx, windowManager.view, message, Snackbar.LENGTH_LONG)
                .setDuration(5_000)
                .setBackgroundTint(theme.popupBackgroundColor)
                .setTextColor(theme.popupTextColor)
                .setActionTextColor(theme.genericActiveBackgroundColor)
                .setAction(R.string.function_kit_binding_snackbar_open) {
                    onOpen()
                }
                .addCallback(
                    object : Snackbar.Callback() {
                        override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
                            if (snackbarInstance === transientBottomBar) {
                                snackbarInstance = null
                            }
                        }
                    }
                ).apply {
                    val hMargin = snackbarCtx.dp(24)
                    val vMargin = snackbarCtx.dp(16)
                    view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        leftMargin = hMargin
                        rightMargin = hMargin
                        bottomMargin = vMargin
                    }
                    ((view as FrameLayout).getChildAt(0) as SnackbarContentLayout).apply {
                        messageView.letterSpacing = 0f
                        actionView.letterSpacing = 0f
                    }
                    show()
                }
    }

    @SuppressLint("RestrictedApi")
    fun showBindingTriggered(
        context: Context,
        windowManager: InputWindowManager,
        theme: Theme,
        bindingTitle: String,
        onOpen: () -> Unit
    ) {
        snackbarInstance?.dismiss()
        val snackbarCtx = context.withTheme(R.style.InputViewSnackbarTheme)
        val message = context.getString(R.string.function_kit_binding_snackbar_triggered, bindingTitle)

        snackbarInstance =
            Snackbar.make(snackbarCtx, windowManager.view, message, Snackbar.LENGTH_LONG)
                .setBackgroundTint(theme.popupBackgroundColor)
                .setTextColor(theme.popupTextColor)
                .setActionTextColor(theme.genericActiveBackgroundColor)
                .setAction(R.string.function_kit_binding_snackbar_open) {
                    onOpen()
                }
                .addCallback(
                    object : Snackbar.Callback() {
                        override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
                            if (snackbarInstance === transientBottomBar) {
                                snackbarInstance = null
                            }
                        }
                    }
                ).apply {
                    val hMargin = snackbarCtx.dp(24)
                    val vMargin = snackbarCtx.dp(16)
                    view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        leftMargin = hMargin
                        rightMargin = hMargin
                        bottomMargin = vMargin
                    }
                    ((view as FrameLayout).getChildAt(0) as SnackbarContentLayout).apply {
                        messageView.letterSpacing = 0f
                        actionView.letterSpacing = 0f
                    }
                    show()
                }
    }
}
