/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
import timber.log.Timber

/**
 * Experimental: show a short-lived overlay "chip" when new clipboard content is detected.
 *
 * - Requires SYSTEM_ALERT_WINDOW + TYPE_APPLICATION_OVERLAY (Android O+).
 * - Gracefully degrades when permission is missing.
 */
internal object ClipboardOverlayPromptManager {

    private const val AUTO_DISMISS_MS = 5_000L
    private const val DUPLICATE_TEXT_SUPPRESS_MS = 10_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var initialized = false

    private lateinit var appContext: Context

    private var lastPromptText: String? = null
    private var lastPromptAtElapsedMs: Long = 0L

    private var didNotifyMissingPermission: Boolean = false

    private var overlayWindowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayAdded: Boolean = false

    @Volatile
    private var pendingClipboardText: String? = null

    private val dismissRunnable = Runnable { dismissOverlay() }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        overlayWindowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ClipboardManager.addOnUpdateListener(ClipboardManager.OnClipboardUpdateListener { entry ->
            // ClipboardManager invokes listeners on Dispatchers.Default. Hop onto main thread for UI.
            mainHandler.post { onClipboardUpdated(entry) }
        })
        Timber.d("ClipboardOverlayPromptManager initialized")
    }

    private fun onClipboardUpdated(entry: ClipboardEntry) {
        val text = entry.text
        if (text.isBlank()) return

        val now = SystemClock.elapsedRealtime()
        // Avoid spamming: suppress repeated prompts for the same text in a short window.
        if (text == lastPromptText && now - lastPromptAtElapsedMs < DUPLICATE_TEXT_SUPPRESS_MS) return

        if (!canDrawOverlays(appContext)) {
            Timber.i("Overlay permission missing; skip clipboard overlay prompt")
            if (BuildConfig.DEBUG && !didNotifyMissingPermission) {
                didNotifyMissingPermission = true
                Toast.makeText(
                    appContext,
                    "剪贴板悬浮提示需要「显示在其他应用上层」权限，当前已降级不显示。",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        if (showOverlay(text)) {
            lastPromptText = text
            lastPromptAtElapsedMs = now
        }
    }

    private fun showOverlay(clipboardText: String): Boolean {
        val wm = overlayWindowManager ?: return false
        val view = ensureOverlayView()
        val params = ensureOverlayParams()

        pendingClipboardText = clipboardText

        if (!overlayAdded) {
            try {
                wm.addView(view, params)
                overlayAdded = true
            } catch (t: Throwable) {
                Timber.w(t, "Failed to add clipboard overlay view")
                overlayAdded = false
                // Degrade: keep silent (no crash).
                return false
            }
        }

        // Reset auto-dismiss timer.
        mainHandler.removeCallbacks(dismissRunnable)
        mainHandler.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
        return true
    }

    private fun dismissOverlay() {
        val wm = overlayWindowManager
        val view = overlayView
        if (!overlayAdded || wm == null || view == null) return
        try {
            wm.removeView(view)
        } catch (t: Throwable) {
            Timber.w(t, "Failed to remove clipboard overlay view")
        } finally {
            overlayAdded = false
        }
    }

    private fun ensureOverlayView(): View {
        overlayView?.let { return it }
        val dp12 = dp(12)
        val dp10 = dp(10)
        val dp16 = dp(16)

        val tv = TextView(appContext).apply {
            text = appContext.getString(R.string.function_kit_bindings_clipboard)
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp16, dp10, dp16, dp10)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(0xCC000000.toInt())
            }
            // Small elevation so it's readable on busy screens.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp12.toFloat()
            }
            isClickable = true
            isFocusable = false
            setOnClickListener { onOverlayClicked() }
        }

        overlayView = tv
        return tv
    }

    private fun ensureOverlayParams(): WindowManager.LayoutParams {
        overlayParams?.let { return it }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // Avoid status bar / notch area.
            y = dp(72)
            title = "FcitxClipboardOverlayPrompt"
        }
        overlayParams = params
        return params
    }

    private fun onOverlayClicked() {
        val clipboardText = pendingClipboardText ?: return
        pendingClipboardText = null
        dismissOverlay()

        val activeWm = InputWindowManager.activeOrNull()
        if (activeWm != null && activeWm.view.isAttachedToWindow) {
            activeWm.view.post {
                activeWm.attachWindow(
                    FunctionKitBindingsWindow(
                        trigger = FunctionKitBindingTrigger.Clipboard,
                        clipboardText = clipboardText
                    )
                )
            }
            return
        }

        AppUtil.launchMain(appContext)
        // Best-effort hint: user needs to focus an input field to show IME.
        Toast.makeText(
            appContext,
            "已复制。请点输入框打开键盘后使用剪贴板动作。",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun canDrawOverlays(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).toInt()
}
