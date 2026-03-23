/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView

/**
 * Debug-only activity used by UIAutomator instrumentation tests.
 *
 * It hosts a single EditText so the IME can be shown reliably on emulators.
 */
class FunctionKitImeE2EPlaygroundActivity : Activity() {
    lateinit var input: AppCompatEditText
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val instructions =
            AppCompatTextView(this).apply {
                text =
                    "E2E Playground\n" +
                        "Focus the input below to show the IME, then open Function Kit from the toolbar."
                textSize = 14f
            }

        input =
            AppCompatEditText(this).apply {
                hint = "E2E input field"
                minLines = 4
                gravity = Gravity.TOP or Gravity.START
                isSingleLine = false
            }

        setContentView(
            FrameLayout(this).apply {
                addView(
                    instructions,
                    FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
                        leftMargin = 24
                        topMargin = 24
                        rightMargin = 24
                        bottomMargin = 24
                    }
                )
                addView(
                    input,
                    FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
                        leftMargin = 24
                        topMargin = 140
                        rightMargin = 24
                        bottomMargin = 24
                    }
                )
            }
        )
    }

    override fun onResume() {
        super.onResume()
        focusInputAndShowIme()
    }

    fun focusInputAndShowIme() {
        input.requestFocus()
        input.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun currentText(): String = input.text?.toString().orEmpty()
}

