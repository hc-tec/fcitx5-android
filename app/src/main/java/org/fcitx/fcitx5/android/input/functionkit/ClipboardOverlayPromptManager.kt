/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.notificationManager
import timber.log.Timber

/**
 * Experimental: show a short-lived overlay "chip" when new clipboard content is detected.
 *
 * - Requires SYSTEM_ALERT_WINDOW + TYPE_APPLICATION_OVERLAY (Android O+).
 * - Gracefully degrades to a short-lived heads-up notification when permission is missing.
 */
internal object ClipboardOverlayPromptManager {

    private const val AUTO_DISMISS_MS = 5_000L
    private const val NOTIFICATION_CHANNEL_ID = "function-kit-clipboard-actions"
    private const val NOTIFICATION_ID = 0xfcb1
    private const val DUPLICATE_TEXT_SUPPRESS_MS = 10_000L
    private const val BINDINGS_CACHE_MS = 3_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var initialized = false

    private lateinit var appContext: Context

    // ClipboardManager stores listeners in a WeakHashSet, so we must keep a strong reference here.
    private val clipboardUpdateListener =
        ClipboardManager.OnClipboardUpdateListener { entry ->
            // ClipboardManager invokes listeners on Dispatchers.Default. Hop onto main thread for UI.
            mainHandler.post { onClipboardUpdated(entry) }
        }

    private var lastPromptText: String? = null
    private var lastPromptAtElapsedMs: Long = 0L

    private var didNotifyMissingPermission: Boolean = false

    private var bindingsCacheAtElapsedMs: Long = 0L
    private var bindingsCacheHasClipboardBindings: Boolean? = null

    private var overlayWindowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayAdded: Boolean = false

    private var imeBridgeOverlayView: View? = null
    private var imeBridgeOverlayParams: WindowManager.LayoutParams? = null
    private var imeBridgeOverlayAdded: Boolean = false
    private var imeBridgeEditText: EditText? = null

    @Volatile
    private var pendingClipboardText: String? = null

    private val dismissRunnable = Runnable { dismissOverlay() }
    private val dismissNotificationRunnable = Runnable { dismissNotification() }

    @Volatile
    private var pendingOpenClipboardText: String? = null

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        overlayWindowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ClipboardManager.addOnUpdateListener(clipboardUpdateListener)
        Timber.d("ClipboardOverlayPromptManager initialized")
    }

    private fun onClipboardUpdated(entry: ClipboardEntry) {
        val text = entry.text
        if (text.isBlank()) return

        val now = SystemClock.elapsedRealtime()
        // Avoid spamming: suppress repeated prompts for the same text in a short window.
        if (text == lastPromptText && now - lastPromptAtElapsedMs < DUPLICATE_TEXT_SUPPRESS_MS) return

        if (!hasClipboardBindings(now)) return

        val overlayEnabled = canDrawOverlays(appContext)
        var shown = false
        if (overlayEnabled) {
            shown = showOverlay(text)
        }
        if (!shown) {
            shown = showNotificationPrompt()
        }
        if (!shown) {
            Timber.i("Clipboard prompt unavailable (no overlay permission and notifications disabled)")
            if (BuildConfig.DEBUG && !didNotifyMissingPermission) {
                didNotifyMissingPermission = true
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.function_kit_clipboard_prompt_unavailable_hint),
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        if (BuildConfig.DEBUG && !overlayEnabled && !didNotifyMissingPermission) {
            didNotifyMissingPermission = true
            Toast.makeText(
                appContext,
                appContext.getString(R.string.function_kit_clipboard_prompt_overlay_permission_missing_hint),
                Toast.LENGTH_LONG
            ).show()
        }

        lastPromptText = text
        lastPromptAtElapsedMs = now
    }

    private fun hasClipboardBindings(nowElapsedMs: Long): Boolean {
        val cached = bindingsCacheHasClipboardBindings
        if (cached != null && nowElapsedMs - bindingsCacheAtElapsedMs < BINDINGS_CACHE_MS) {
            return cached
        }
        val has =
            try {
                FunctionKitBindingRegistry
                    .listForTrigger(appContext, FunctionKitBindingTrigger.Clipboard)
                    .isNotEmpty()
            } catch (t: Throwable) {
                Timber.w(t, "Failed to list clipboard bindings")
                false
            }
        bindingsCacheHasClipboardBindings = has
        bindingsCacheAtElapsedMs = nowElapsedMs
        return has
    }

    private fun showOverlay(clipboardText: String): Boolean {
        dismissNotification()
        mainHandler.removeCallbacks(dismissNotificationRunnable)

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

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                appContext.getString(R.string.function_kit_clipboard_prompt_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = NOTIFICATION_CHANNEL_ID
            }
        appContext.notificationManager.createNotificationChannel(channel)
    }

    private fun showNotificationPrompt(): Boolean {
        dismissOverlay()
        mainHandler.removeCallbacks(dismissRunnable)

        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            Timber.i("Notifications disabled; skip clipboard prompt notification")
            return false
        }
        ensureNotificationChannel()

        val intent =
            Intent(appContext, ClipboardActionsPromptReceiver::class.java).apply {
                action = ClipboardActionsPromptReceiver.ACTION_OPEN_CLIPBOARD_ACTIONS
            }
        val pi =
            PendingIntent.getBroadcast(
                appContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        val notification =
            NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_content_paste_24)
                .setContentTitle(appContext.getString(R.string.function_kit_bindings_clipboard))
                .setContentText(appContext.getString(R.string.function_kit_clipboard_prompt_content))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()

        appContext.notificationManager.notify(NOTIFICATION_ID, notification)

        // Reset auto-dismiss timer.
        mainHandler.removeCallbacks(dismissNotificationRunnable)
        mainHandler.postDelayed(dismissNotificationRunnable, AUTO_DISMISS_MS)
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

    private fun dismissNotification() {
        try {
            appContext.notificationManager.cancel(NOTIFICATION_ID)
        } catch (t: Throwable) {
            Timber.w(t, "Failed to cancel clipboard prompt notification")
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

    internal fun handlePromptClicked(clipboardText: String) {
        pendingClipboardText = null
        dismissOverlay()
        dismissNotification()

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

        pendingOpenClipboardText = clipboardText

        // No input focus: if overlay is available, focus a hidden EditText to bring up IME in-place.
        if (canDrawOverlays(appContext) && showImeBridgeOverlay()) {
            return
        }

        // Fallback: open the app, user can tap an input field to show IME.
        AppUtil.launchMain(appContext)
        Toast.makeText(
            appContext,
            appContext.getString(R.string.function_kit_clipboard_prompt_focus_input_hint),
            Toast.LENGTH_SHORT
        ).show()
    }

    internal fun consumePendingOpenClipboardText(): String? {
        val text = pendingOpenClipboardText
        pendingOpenClipboardText = null
        return text
    }

    private fun showImeBridgeOverlay(): Boolean {
        val wm = overlayWindowManager ?: return false
        val view = ensureImeBridgeOverlayView()
        val params = ensureImeBridgeOverlayParams()

        if (!imeBridgeOverlayAdded) {
            try {
                wm.addView(view, params)
                imeBridgeOverlayAdded = true
            } catch (t: Throwable) {
                Timber.w(t, "Failed to add IME bridge overlay view")
                imeBridgeOverlayAdded = false
                return false
            }
        }

        val editText = imeBridgeEditText ?: return false
        editText.requestFocus()
        val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        // Best effort: show IME for this temporary input target.
        imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        return true
    }

    private fun dismissImeBridgeOverlay() {
        val wm = overlayWindowManager
        val view = imeBridgeOverlayView
        if (!imeBridgeOverlayAdded || wm == null || view == null) return
        try {
            wm.removeView(view)
        } catch (t: Throwable) {
            Timber.w(t, "Failed to remove IME bridge overlay view")
        } finally {
            imeBridgeOverlayAdded = false
        }
    }

    private fun ensureImeBridgeOverlayView(): View {
        imeBridgeOverlayView?.let { return it }

        val dp12 = dp(12)
        val dp10 = dp(10)
        val dp16 = dp(16)

        val root =
            LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp16, dp10, dp16, dp10)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(999).toFloat()
                    setColor(0xCC000000.toInt())
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    elevation = dp12.toFloat()
                }
            }

        val label =
            TextView(appContext).apply {
                text = appContext.getString(R.string.function_kit_bindings_clipboard)
                setTextColor(Color.WHITE)
                textSize = 14f
            }
        root.addView(label)

        val close =
            TextView(appContext).apply {
                text = " x"
                setTextColor(Color.WHITE)
                textSize = 14f
                isClickable = true
                setOnClickListener {
                    val editText = imeBridgeEditText
                    if (editText != null) {
                        val imm =
                            appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.hideSoftInputFromWindow(editText.windowToken, 0)
                    }
                    dismissImeBridgeOverlay()
                }
            }
        root.addView(close)

        val editText =
            EditText(appContext).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.TRANSPARENT)
                setHintTextColor(Color.TRANSPARENT)
                isCursorVisible = false
                // Keep it effectively hidden, only used to summon IME.
                layoutParams = LinearLayout.LayoutParams(1, 1)
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        dismissImeBridgeOverlay()
                    }
                }
            }
        imeBridgeEditText = editText
        root.addView(editText)

        root.isClickable = true
        root.setOnClickListener {
            editText.requestFocus()
            val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }

        imeBridgeOverlayView = root
        return root
    }

    private fun ensureImeBridgeOverlayParams(): WindowManager.LayoutParams {
        imeBridgeOverlayParams?.let { return it }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        // Focusable window to host the hidden EditText (needed to summon IME).
        val flags =
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(72)
                title = "FcitxClipboardImeBridge"
            }
        imeBridgeOverlayParams = params
        return params
    }

    private fun onOverlayClicked() {
        val clipboardText = pendingClipboardText ?: return
        handlePromptClicked(clipboardText)
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
