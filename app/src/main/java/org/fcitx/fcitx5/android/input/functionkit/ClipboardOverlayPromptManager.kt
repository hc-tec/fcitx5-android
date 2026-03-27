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
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
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
import org.fcitx.fcitx5.android.input.wm.ImeBridgeState
import org.fcitx.fcitx5.android.input.wm.ImeWindowResumeManager
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
    private const val RESUME_SOURCE = "clipboard.prompt"
    private const val DUPLICATE_TEXT_SUPPRESS_MS = 10_000L
    private const val BINDINGS_CACHE_MS = 3_000L
    // The IME bridge overlay is a focusable TYPE_APPLICATION_OVERLAY hosting a hidden EditText.
    // Some ROMs refuse to show IME for overlays; in that case we should clean it up to avoid
    // leaving an invisible focused window around.
    private const val IME_BRIDGE_NO_IME_CLEANUP_MS = 5_000L

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
    private val dismissImeBridgeIfNoImeRunnable =
        Runnable {
            val activeWm = InputWindowManager.activeOrNull()
            val imeShown = activeWm != null && activeWm.view.isAttachedToWindow && activeWm.view.isShown
            if (imeShown) {
                Timber.d("IME bridge overlay: IME is shown; keep focus bridge until user closes it")
                return@Runnable
            }
            Timber.d("IME bridge overlay: IME still not shown; dismiss focus bridge")
            dismissImeBridgeOverlay()
        }

    @Volatile
    private var pendingClipboardText: String? = null

    private val dismissRunnable = Runnable { dismissOverlay() }
    private val dismissNotificationRunnable = Runnable { dismissNotification() }

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
        if (text.isBlank()) {
            Timber.d("Clipboard updated but text is blank; skip prompt")
            return
        }

        val now = SystemClock.elapsedRealtime()
        // Avoid spamming: suppress repeated prompts for the same text in a short window.
        if (text == lastPromptText && now - lastPromptAtElapsedMs < DUPLICATE_TEXT_SUPPRESS_MS) {
            Timber.d("Clipboard prompt suppressed (duplicate within %dms)", DUPLICATE_TEXT_SUPPRESS_MS)
            return
        }

        if (!hasClipboardBindings(now)) {
            Timber.d("No clipboard bindings; skip prompt")
            return
        }

        val overlayEnabled = canDrawOverlays(appContext)
        var shown = false
        if (overlayEnabled) shown = showOverlay(text)
        if (!shown) shown = showNotificationPrompt(text)
        Timber.d(
            "Clipboard prompt attempt: overlayEnabled=%s shown=%s textLen=%d sensitive=%s type=%s",
            overlayEnabled,
            shown,
            text.length,
            entry.sensitive,
            entry.type
        )
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
        val entries =
            try {
                FunctionKitBindingRegistry
                    .listForTrigger(appContext, FunctionKitBindingTrigger.Clipboard)
            } catch (t: Throwable) {
                Timber.w(t, "Failed to list clipboard bindings")
                emptyList()
            }
        val has = entries.isNotEmpty()
        Timber.d("Clipboard bindings count=%d", entries.size)
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

        try {
            if (!overlayAdded) {
                wm.addView(view, params)
                overlayAdded = true
            } else {
                wm.updateViewLayout(view, params)
            }
        } catch (t: Throwable) {
            Timber.w(t, "Failed to show clipboard overlay view")
            overlayAdded = false
            // Degrade: keep silent (no crash).
            return false
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

    private fun showNotificationPrompt(clipboardText: String): Boolean {
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
                putExtra(ClipboardActionsPromptReceiver.EXTRA_CLIPBOARD_TEXT, clipboardText)
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
        overlayParams?.let {
            it.y = computeOverlayBottomOffsetPx()
            return it
        }
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
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = computeOverlayBottomOffsetPx()
            title = "FcitxClipboardOverlayPrompt"
        }
        overlayParams = params
        return params
    }

    private fun computeOverlayBottomOffsetPx(): Int {
        val navigationBarHeightPx = resolveNavigationBarHeightPx()
        val imeHeightPx = resolveImeWindowHeightPx()

        val desired =
            if (imeHeightPx > 0) {
                // When IME is visible, place the prompt just above the IME so it isn't covered.
                navigationBarHeightPx + imeHeightPx + dp(16)
            } else {
                // Otherwise, keep it near the bottom (like a snackbar).
                navigationBarHeightPx + dp(72)
            }

        val screenHeightPx = appContext.resources.displayMetrics.heightPixels
        // Clamp to keep it within screen.
        val maxOffset = (screenHeightPx - dp(32)).coerceAtLeast(0)
        return desired.coerceAtMost(maxOffset)
    }

    private fun resolveImeWindowHeightPx(): Int {
        val activeWm = InputWindowManager.activeOrNull() ?: return 0
        val view = activeWm.view
        if (!view.isAttachedToWindow || !view.isShown) return 0
        return view.height.coerceAtLeast(0)
    }

    private fun resolveNavigationBarHeightPx(): Int {
        val resources = appContext.resources
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId <= 0) {
            return 0
        }
        return runCatching { resources.getDimensionPixelSize(resourceId) }.getOrDefault(0)
    }

    internal fun handlePromptClicked(clipboardText: String) {
        pendingClipboardText = null
        dismissOverlay()
        dismissNotification()

        val activeWm = InputWindowManager.activeOrNull()
        if (activeWm != null && activeWm.view.isAttachedToWindow && activeWm.view.isShown) {
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

        ImeWindowResumeManager.schedule(
            ImeWindowResumeManager.Request.FunctionKitBindings(
                clipboardText = clipboardText,
                source = RESUME_SOURCE
            )
        )

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

    internal fun dismissImeBridgeOverlayIfPresent() {
        // No-op if not present.
        dismissImeBridgeOverlay()
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
        ImeBridgeState.markActive(RESUME_SOURCE)
        requestImeFor(editText)
        // Only clean up automatically when IME didn't show. If IME is shown, keep the bridge
        // so the user has time to pick actions in the IME UI.
        mainHandler.removeCallbacks(dismissImeBridgeIfNoImeRunnable)
        mainHandler.postDelayed(dismissImeBridgeIfNoImeRunnable, IME_BRIDGE_NO_IME_CLEANUP_MS)
        return true
    }

    private fun requestImeFor(editText: EditText) {
        editText.isFocusableInTouchMode = true
        editText.requestFocus()
        try {
            editText.requestFocusFromTouch()
        } catch (_: Throwable) {
            // Some ROMs throw if the view isn't touch-focused; ignore.
        }
        val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        // Best effort: force-show IME for this temporary input target.
        imm?.showSoftInput(editText, InputMethodManager.SHOW_FORCED)
        // Some systems ignore the first call; retry shortly.
        mainHandler.postDelayed(
            { imm?.showSoftInput(editText, InputMethodManager.SHOW_FORCED) },
            200L
        )
    }

    private fun dismissImeBridgeOverlay() {
        val wm = overlayWindowManager
        val view = imeBridgeOverlayView
        if (!imeBridgeOverlayAdded || wm == null || view == null) return
        ImeBridgeState.clearIfSource(RESUME_SOURCE)
        mainHandler.removeCallbacks(dismissImeBridgeIfNoImeRunnable)
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

        // Invisible focus bridge (do NOT look like an "ad" on screen).
        // We only need a real focused view to be able to summon the IME.
        val root =
            LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                // User may want to cancel by tapping outside the keyboard area.
                // With FLAG_WATCH_OUTSIDE_TOUCH we can observe those touches without blocking the app.
                setOnTouchListener { _, event ->
                    if (event.action != MotionEvent.ACTION_OUTSIDE) return@setOnTouchListener false

                    val rawX = event.rawX.toInt()
                    val rawY = event.rawY.toInt()
                    val imeRect = getImeVisibleRectOrNull()

                    Timber.d(
                        "IME bridge overlay: ACTION_OUTSIDE raw=(%d,%d) imeRect=%s",
                        rawX,
                        rawY,
                        imeRect?.toShortString()
                    )

                    if (imeRect != null && imeRect.contains(rawX, rawY)) {
                        // Touch is within IME window area (keyboard / bindings UI). Don't close.
                        return@setOnTouchListener false
                    }

                    // Treat as cancel: hide IME and dismiss focus bridge.
                    ImeWindowResumeManager.clearIfSource(RESUME_SOURCE)
                    val editText = imeBridgeEditText
                    val imm =
                        appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    if (editText != null) {
                        imm?.hideSoftInputFromWindow(editText.windowToken, 0)
                    }
                    dismissImeBridgeOverlay()
                    true
                }
            }

        val editText =
            object : EditText(appContext) {
                override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        ImeWindowResumeManager.clearIfSource(RESUME_SOURCE)
                        val imm =
                            appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.hideSoftInputFromWindow(windowToken, 0)
                        dismissImeBridgeOverlay()
                        return true
                    }
                    return super.onKeyPreIme(keyCode, event)
                }
            }.apply {
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.TRANSPARENT)
                setHintTextColor(Color.TRANSPARENT)
                isCursorVisible = false
                // Keep it effectively hidden, only used to summon IME.
                layoutParams = LinearLayout.LayoutParams(1, 1)
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val imm =
                            appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.hideSoftInputFromWindow(windowToken, 0)
                        dismissImeBridgeOverlay()
                    }
                }
            }
        imeBridgeEditText = editText
        root.addView(editText)

        imeBridgeOverlayView = root
        return root
    }

    private fun getImeVisibleRectOrNull(): Rect? {
        val activeWm = InputWindowManager.activeOrNull() ?: return null
        val view = activeWm.view
        if (!view.isAttachedToWindow || !view.isShown) return null

        fun rectFor(v: View): Rect? {
            val rect = Rect()
            if (!v.getGlobalVisibleRect(rect) || rect.isEmpty) return null
            return rect
        }

        val screenH = appContext.resources.displayMetrics.heightPixels
        val rootRect = rectFor(view.rootView)
        val viewRect = rectFor(view)

        return when {
            rootRect == null -> viewRect
            viewRect == null -> rootRect
            // Some ROMs report a fullscreen root; fall back to the content rect in that case.
            rootRect.height() > (screenH * 0.9f) -> viewRect
            else -> rootRect
        }
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
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
