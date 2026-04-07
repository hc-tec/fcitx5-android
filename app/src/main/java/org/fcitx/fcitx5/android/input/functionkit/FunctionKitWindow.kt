/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.Context
import android.content.ClipDescription
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.preedit.PreeditUi
import org.fcitx.fcitx5.android.input.wm.ImeBridgeState
import org.fcitx.fcitx5.android.input.wm.ImeWindowHiddenListener
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.withBatchEdit
import org.mechdancer.dependency.manager.must
import org.json.JSONArray
import org.json.JSONObject
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.horizontalPadding
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

data class FunctionKitImeSendIntent(
    val kind: String,
    val actionId: Int? = null,
    val actionLabel: String? = null
)

internal interface FunctionKitImeActionSendInterceptor {
    fun maybeInterceptImeActionSend(
        intent: FunctionKitImeSendIntent,
        onDecision: (Boolean) -> Unit
    ): Boolean
}

private const val VerboseFunctionKitWindowLogs = false

private inline fun debugLog(message: () -> String) {
    if (BuildConfig.DEBUG && VerboseFunctionKitWindowLogs) {
        Log.d("FunctionKitWindow", message())
    }
}

class FunctionKitWindow(
    private val requestedKitId: String? = null
) : org.fcitx.fcitx5.android.input.wm.InputWindow.ExtendedInputWindow<FunctionKitWindow>(),
    InputBroadcastReceiver,
    FcitxInputMethodService.LocalInputTarget,
    FunctionKitImeActionSendInterceptor,
    ImeWindowHiddenListener {

    override val type = FunctionKitWindow::class

    private val windowKey: String =
        requestedKitId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "__default__"

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is FunctionKitWindow) {
            return false
        }
        return windowKey == other.windowKey
    }

    override fun hashCode(): Int = windowKey.hashCode()

    override fun toString(): String = "FunctionKitWindow($windowKey)"

    private data class CandidateDraft(
        val id: String,
        val text: String,
        val tone: String,
        val risk: String,
        val rationale: String
    )

    private data class BindingInvocation(
        val invocationId: String,
        val trigger: FunctionKitBindingTrigger,
        val bindingId: String,
        val bindingTitle: String,
        val bindingPreferredPresentation: String?,
        val bindingCategories: Set<String>,
        val bindingEntry: JSONObject?,
        val requestedPayloads: Set<String>,
        val providedPayloads: Set<String>,
        val missingPermissions: Set<String>,
        val payloadTruncated: Boolean,
        val capturedContext: JSONObject,
        val clipboardText: String? = null,
        val createdAtEpochMs: Long = System.currentTimeMillis()
    )

    private data class RuntimeMessageDelivery(
        val fromKitId: String,
        val fromSurface: String?,
        val channel: String?,
        val data: Any?,
        val sentAtEpochMs: Long
    )

    private data class ExecutionConfig(
        val remoteEnabled: Boolean,
        val configuredBaseUrl: String,
        val normalizedBaseUrl: String,
        val remoteAuthToken: String,
        val timeoutSeconds: Int,
        val requestedExecutionMode: String,
        val preferredBackendClass: String?,
        val preferredAdapter: String?,
        val latencyBudgetMs: Int,
        val latencyTier: String?,
        val requireStructuredJson: Boolean,
        val requiredCapabilities: List<String>,
        val notes: List<String>,
        val remoteRenderPath: String
    ) {
        val timeoutMs: Int = timeoutSeconds.coerceAtLeast(1) * 1000
        val remoteAuthConfigured: Boolean = remoteAuthToken.isNotBlank()
        val renderEndpoint: String? =
            normalizedBaseUrl.takeIf { it.isNotBlank() }?.let { "$it$remoteRenderPath" }
        val executionMode: String = if (remoteEnabled) requestedExecutionMode else "local-demo"
        val transport: String = if (remoteEnabled) "remote-http" else "local-webview"
        val modeMessage: String =
            if (remoteEnabled) {
                if (renderEndpoint.isNullOrBlank()) {
                    "Remote inference is enabled, but the host service base URL is blank."
                } else {
                    // Avoid leaking local endpoint/baseUrl to kits through hostInfo.modeMessage.
                    "Remote inference enabled ($requestedExecutionMode)"
                }
            } else {
                "Using local demo candidates because remote inference is disabled or the manifest route is not active."
            }
    }

    private class RemoteInferenceException(
        val code: String,
        override val message: String,
        val retryable: Boolean,
        val statusCode: Int? = null,
        val details: Any? = null
    ) : Exception(message)

    private data class ComposerState(
        val open: Boolean = false,
        val focused: Boolean = false,
        val text: String = "",
        val selectionStart: Int = 0,
        val selectionEnd: Int = 0,
        val revision: Int = 0,
        val mode: String = "embedded",
        val source: String = "host",
        val lastAction: String? = null,
        val updatedAtEpochMs: Long = 0L
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("open", open)
                .put("focused", focused)
                .put("text", text)
                .put("selectionStart", selectionStart)
                .put("selectionEnd", selectionEnd)
                .put("revision", revision)
                .put("mode", mode)
                .put("source", source)
                .put("lastAction", lastAction)
                .put("updatedAtEpochMs", updatedAtEpochMs)
    }

    private data class PendingImeActionSendIntercept(
        val requestMessageId: String,
        val createdAtEpochMs: Long,
        val timeoutRunnable: Runnable,
        val onDecision: (Boolean) -> Unit
    )

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val theme: Theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()
    private val kawaiiBar: KawaiiBarComponent by manager.must()
    private val preedit: PreeditComponent by manager.must()
    private val windowPool: FunctionKitWindowPool by manager.must()
    private val aiPrefs = AppPrefs.getInstance().ai
    private val functionKitPrefs = AppPrefs.getInstance().functionKit
    private val functionKitManifest by lazy {
        FunctionKitRegistry.resolve(context, requestedKitId)
    }
    private val kitStudioAttach by lazy(LazyThreadSafetyMode.NONE) {
        FunctionKitKitStudioRemoteAttach(
            context = context,
            prefs = functionKitPrefs
        )
    }

    private val hostConfig by lazy {
        FunctionKitWebViewHost.Config(
            expectedKitId = functionKitId,
            expectedSurface = null,
            kitAssetResolution =
                if (functionKitManifest.isUserInstalled) {
                    FunctionKitWebViewHost.KitAssetResolution.InstalledFirst
                } else {
                    FunctionKitWebViewHost.KitAssetResolution.BundledOnly
                }
        )
    }
    private val webView by lazy {
        WebView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(theme.barColor)
        }
    }
    private val host by lazy {
        FunctionKitWebViewHost(
            webView = webView,
            assetLoader = FunctionKitWebViewHost.createDefaultAssetLoader(context, hostConfig),
            onUiEnvelope = ::handleUiEnvelope,
            onHostEnvelope = ::handleHostEnvelope,
            onHostEvent = ::handleHostEvent,
            config = hostConfig
        )
    }
    private val taskTracker by lazy {
        FunctionKitTaskTracker(
            kitId = functionKitId,
            host = host
        )
    }

    private var panelPeekHeightPx: Int = 0
    private var windowManagerHeightBeforeAttach: Int? = null
    private var embeddedKeyboardView: View? = null
    private var embeddedKeyboardWindow: KeyboardWindow? = null
    private var embeddedKeyboardPinned: Boolean = false

    private fun resolvePanelPeekHeightPx(baseHeightPx: Int): Int {
        val preferred = context.dp(320)
        val minHeight = context.dp(200)
        if (baseHeightPx <= 0) {
            return preferred
        }
        val maxHeight = (baseHeightPx * 0.8f).toInt().coerceAtLeast(minHeight)
        return preferred.coerceIn(minHeight, maxHeight)
    }

    private fun resolvePanelExpandedHeightPx(baseHeightPx: Int): Int {
        val preferred = context.dp(460)
        val minHeight = context.dp(260)
        if (baseHeightPx <= 0) {
            return preferred
        }
        val maxHeight = (baseHeightPx * 0.92f).toInt().coerceAtLeast(minHeight)
        val scaled = (baseHeightPx * 0.75f).toInt()
        return maxOf(preferred, scaled).coerceIn(minHeight, maxHeight)
    }

    private fun resolveExpandedWindowHeightPx(baseHeightPx: Int): Int {
        val minHeight = baseHeightPx.coerceAtLeast(context.dp(360))
        val screenHeight = context.resources.displayMetrics.heightPixels
        if (screenHeight <= 0) {
            return minHeight
        }
        val maxHeight = (screenHeight * 0.92f).toInt().coerceAtLeast(minHeight)
        val preferred = (baseHeightPx * 1.35f).toInt().coerceAtLeast(minHeight)
        return preferred.coerceIn(minHeight, maxHeight)
    }

    private fun resolveKeyboardHeightFromPrefsPx(): Int {
        val keyboardPrefs = AppPrefs.getInstance().keyboard
        val percentPref =
            when (context.resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardPrefs.keyboardHeightPercentLandscape
                else -> keyboardPrefs.keyboardHeightPercent
            }
        val percent = percentPref.getValue()
        return context.resources.displayMetrics.heightPixels * percent / 100
    }

    private fun resolveKeyboardBaseHeightPx(): Int {
        val prefHeight = resolveKeyboardHeightFromPrefsPx()
        if (prefHeight > 0) {
            return prefHeight
        }
        return windowManager.view.layoutParams?.height ?: 0
    }

    private fun shouldShowEmbeddedKeyboard(): Boolean =
        embeddedKeyboardPinned || (composerState.open && composerState.focused)

    private fun applyEmbeddedKeyboardPinnedButtonState(button: ToolButton, pinned: Boolean) {
        if (pinned) {
            button.setIcon(R.drawable.ic_baseline_arrow_drop_down_24)
            button.contentDescription = context.getString(R.string.hide_keyboard)
        } else {
            button.setIcon(R.drawable.ic_baseline_keyboard_24)
            button.contentDescription = context.getString(R.string.back_to_keyboard)
        }
    }

    private fun applyPanelExpandButtonState(button: ToolButton, expanded: Boolean) {
        if (expanded) {
            button.setIcon(R.drawable.ic_baseline_expand_less_24)
            button.contentDescription = context.getString(R.string.function_kit_panel_collapse)
        } else {
            button.setIcon(R.drawable.ic_baseline_expand_more_24)
            button.contentDescription = context.getString(R.string.function_kit_panel_expand)
        }
    }

    private fun applyPanelPeekHeightPx(heightPx: Int) {
        if (heightPx <= 0) {
            return
        }
        if (panelPeekHeightPx != heightPx) {
            panelPeekHeightPx = heightPx
        }
        syncEmbeddedCandidateDock(shouldShowEmbeddedKeyboard())
    }

    private fun setWindowManagerHeightPx(heightPx: Int) {
        if (heightPx <= 0) {
            return
        }
        windowManager.view.layoutParams?.let { params ->
            if (params.height != heightPx) {
                params.height = heightPx
                windowManager.view.layoutParams = params
            }
        }
    }

    private var panelExpanded = false
    private var panelPeekHeightBeforeExpandPx: Int? = null
    private var windowManagerHeightBeforeExpandPx: Int? = null

    private fun togglePanelExpanded() {
        if (panelExpanded) {
            collapsePanelExpanded(reason = "toggle")
        } else {
            expandPanel(reason = "toggle")
        }
    }

    private fun expandPanel(reason: String) {
        val currentWindowHeightPx = windowManager.view.layoutParams?.height ?: 0
        val baseHeightPx =
            currentWindowHeightPx.takeIf { it > 0 }
                ?: windowManagerHeightBeforeAttach
                ?: resolveKeyboardHeightFromPrefsPx()
        if (windowManagerHeightBeforeExpandPx == null && baseHeightPx > 0) {
            windowManagerHeightBeforeExpandPx = baseHeightPx
        }
        if (panelPeekHeightBeforeExpandPx == null && panelPeekHeightPx > 0) {
            panelPeekHeightBeforeExpandPx = panelPeekHeightPx
        }

        val expandedWindowHeightPx = resolveExpandedWindowHeightPx(baseHeightPx)
        val expandedPanelHeightPx = resolvePanelExpandedHeightPx(expandedWindowHeightPx)
        setWindowManagerHeightPx(expandedWindowHeightPx)
        applyPanelPeekHeightPx(expandedPanelHeightPx)

        panelExpanded = true
        applyPanelExpandButtonState(panelExpandButton, expanded = true)
        pushHostState("面板已放大 ($reason)")
    }

    private fun collapsePanelExpanded(reason: String) {
        val restoredWindowHeightPx =
            windowManagerHeightBeforeExpandPx
                ?: windowManagerHeightBeforeAttach
                ?: resolveKeyboardHeightFromPrefsPx()
        setWindowManagerHeightPx(restoredWindowHeightPx)

        val restoredPanelHeightPx =
            panelPeekHeightBeforeExpandPx
                ?: resolvePanelPeekHeightPx(restoredWindowHeightPx)
        applyPanelPeekHeightPx(restoredPanelHeightPx)

        panelExpanded = false
        windowManagerHeightBeforeExpandPx = null
        panelPeekHeightBeforeExpandPx = null
        applyPanelExpandButtonState(panelExpandButton, expanded = false)
        pushHostState("面板已收起 ($reason)")
    }

    private fun collapsePanelExpandedIfNeeded(reason: String) {
        if (!panelExpanded) {
            return
        }
        collapsePanelExpanded(reason)
    }

    private val embeddedCandidateDockHeightPx by lazy { context.dp(KawaiiBarComponent.HEIGHT) }
    private val embeddedPreeditDockFallbackHeightPx by lazy { context.dp(56) }
    private val embeddedPreeditUi by lazy {
        val keyBorder = ThemeManager.prefs.keyBorder.getValue()
        val bkgColor =
            if (!keyBorder && theme is Theme.Builtin) theme.barColor else theme.backgroundColor
        PreeditUi(context, theme, setupTextView = {
            backgroundColor = bkgColor
            horizontalPadding = dp(8)
        }).apply {
            root.alpha = 0.8f
            root.visibility = View.INVISIBLE
        }
    }

    private fun syncEmbeddedCandidateDock(showKeyboard: Boolean) {
        val dockActive = windowAttached && showKeyboard
        if (dockActive) {
            kawaiiBar.setFunctionKitCandidateDock(embeddedCandidateDockContainer)
        } else {
            kawaiiBar.setFunctionKitCandidateDock(null)
        }
        preedit.setFunctionKitSuppressed(dockActive)

        val showCandidates = dockActive && currentCandidateCount > 0
        val showPreedit = dockActive && embeddedPreeditUi.visible

        val candidateHeightPx = if (showCandidates) embeddedCandidateDockHeightPx else 0
        val preeditHeightPx =
            if (showPreedit) {
                val widthPx =
                    (embeddedDockContainer.width.takeIf { it > 0 }
                        ?: windowManager.view.width.takeIf { it > 0 }
                        ?: context.resources.displayMetrics.widthPixels)
                        .coerceAtLeast(1)
                val preeditRoot = embeddedPreeditUi.root
                val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                preeditRoot.measure(widthSpec, heightSpec)
                preeditRoot.measuredHeight.takeIf { it > 0 } ?: embeddedPreeditDockFallbackHeightPx
            } else {
                0
            }

        val dockHeightPx = preeditHeightPx + candidateHeightPx
        val shouldShowDock = dockActive && dockHeightPx > 0

        embeddedDockContainer.visibility = if (shouldShowDock) View.VISIBLE else View.GONE
        (embeddedDockContainer.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            if (params.height != dockHeightPx) {
                params.height = dockHeightPx
                embeddedDockContainer.layoutParams = params
            }
        }

        embeddedPreeditDockContainer.visibility = if (preeditHeightPx > 0) View.VISIBLE else View.GONE
        (embeddedPreeditDockContainer.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            if (params.height != preeditHeightPx) {
                params.height = preeditHeightPx
                embeddedPreeditDockContainer.layoutParams = params
            }
        }

        embeddedCandidateDockContainer.visibility = if (candidateHeightPx > 0) View.VISIBLE else View.GONE
        (embeddedCandidateDockContainer.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            if (params.height != candidateHeightPx) {
                params.height = candidateHeightPx
                embeddedCandidateDockContainer.layoutParams = params
            }
        }

        // Keep the overall panel area height stable by borrowing space from the WebView panel when
        // the candidate dock is visible, instead of shrinking the embedded keyboard.
        val panelHeightPx = (panelPeekHeightPx - dockHeightPx).coerceAtLeast(0)
        (panelContainer.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            if (params.height != panelHeightPx) {
                params.height = panelHeightPx
                panelContainer.layoutParams = params
            }
        }
    }

    private fun syncEmbeddedKeyboardLayout() {
        val showKeyboard = shouldShowEmbeddedKeyboard()
        syncEmbeddedCandidateDock(showKeyboard)
        embeddedKeyboardContainer.visibility = if (showKeyboard) View.VISIBLE else View.GONE

        // The keyboard container defaults to VISIBLE; always force the correct visibility and window height
        // even if we failed to capture the base keyboard height (some devices may report 0 during init).
        val baseHeight = windowManagerHeightBeforeAttach ?: 0
        val desiredHeight = if (showKeyboard) panelPeekHeightPx + baseHeight else panelPeekHeightPx
        windowManager.view.layoutParams?.let { params ->
            if (params.height != desiredHeight) {
                params.height = desiredHeight
                windowManager.view.layoutParams = params
            }
        }
    }

    private val panelContainer by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.barColor)
            addView(
                webView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }
    }

    private val embeddedKeyboardContainer by lazy {
        context.frameLayout {
            backgroundColor = theme.barColor
            visibility = View.GONE
        }
    }

    private val embeddedPreeditDockContainer by lazy {
        context.frameLayout {
            backgroundColor = theme.barColor
            visibility = View.GONE
            addView(
                embeddedPreeditUi.root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private val embeddedCandidateDockContainer by lazy {
        context.frameLayout {
            backgroundColor = theme.barColor
            visibility = View.GONE
        }
    }

    private val embeddedDockContainer by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.barColor)
            visibility = View.GONE
            addView(
                embeddedPreeditDockContainer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0
                )
            )
            addView(
                embeddedCandidateDockContainer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0
                )
            )
        }
    }

    private val rootView by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.barColor)
            addView(
                panelContainer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    panelPeekHeightPx.takeIf { it > 0 } ?: context.dp(320)
                )
            )
            addView(
                embeddedDockContainer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0
                )
            )
            addView(
                embeddedKeyboardContainer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }
    }
    private val refreshButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_sync_24, theme).apply {
            contentDescription = context.getString(R.string.reload_function_kit)
            setOnClickListener { reloadPanel() }
        }
    }
    private val settingsButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_settings_24, theme).apply {
            contentDescription = context.getString(R.string.function_kit_settings)
            setOnClickListener { AppUtil.launchMainToFunctionKitDetail(context, functionKitId) }
        }
    }
    private val panelExpandButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_expand_more_24, theme).apply {
            applyPanelExpandButtonState(this, panelExpanded)
            setOnClickListener { togglePanelExpanded() }
        }
    }
    private val pinnedKeyboardButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_keyboard_24, theme).apply {
            applyEmbeddedKeyboardPinnedButtonState(this, embeddedKeyboardPinned)
            setOnClickListener {
                embeddedKeyboardPinned = !embeddedKeyboardPinned
                applyEmbeddedKeyboardPinnedButtonState(this, embeddedKeyboardPinned)
                syncEmbeddedKeyboardLayout()
                pushHostState(if (embeddedKeyboardPinned) "键盘已固定" else "键盘已恢复自动隐藏")
            }
        }
    }
    private val barExtension by lazy {
        context.horizontalLayout {
            add(pinnedKeyboardButton, lParams(dp(40), dp(40)))
            add(settingsButton, lParams(dp(40), dp(40)))
            add(panelExpandButton, lParams(dp(40), dp(40)))
            add(refreshButton, lParams(dp(40), dp(40)))
        }
    }
    private val storage by lazy {
        context.getSharedPreferences("function_kit_storage", Context.MODE_PRIVATE)
    }
    private val fileStoreLazy =
        lazy(LazyThreadSafetyMode.NONE) {
            FunctionKitFileStore(
                context = context,
                kitId = functionKitId
            )
        }
    private val fileStore: FunctionKitFileStore
        get() = fileStoreLazy.value
    private var pendingFilePickRequestId: String? = null
    private val remoteExecutor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "FunctionKitRemoteInference").apply {
                isDaemon = true
            }
        }
    }
    private val contentExecutor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "FunctionKitContentCommit").apply {
                isDaemon = true
            }
        }
    }

    private var panelInitialized = false
    private var windowAttached = false
    private var webViewResumed = false
    private var bridgeReady = false
    private var pendingOpenOptionsIntent = false
    private var pendingOpenInvocationIntent: JSONObject? = null
    private var pendingEntryHash: String? = null
    private val pendingBindingInvocations = ArrayDeque<BindingInvocation>()
    private val recentBindingInvocations = LinkedHashMap<String, BindingInvocation>(16, 0.75f, true)
    private val pendingRuntimeMessages = ArrayDeque<RuntimeMessageDelivery>()
    private var renderSeed = 0
    private var sessionId = newSessionId()
    private var requestedPermissions = emptyList<String>()
    private var grantedPermissions = emptyList<String>()
    private var currentPackageName = ""
    private var currentSelectionStart = 0
    private var currentSelectionEnd = 0
    private var currentInputType = 0
    private var currentCandidateCount = 0
    private var currentPreeditText = ""
    private var composerState = ComposerState(updatedAtEpochMs = System.currentTimeMillis())

    private var inputObserveEnabled = false
    private var inputObserveThrottleMs: Int = 120
    private var inputObserveMaxChars: Int = 64
    private var inputObserveLastSignature: String? = null
    private var inputObserveLastSentAtEpochMs: Long = 0L
    private var inputObservePendingRunnable: Runnable? = null

    private var hostStateThrottledLastSentAtEpochMs: Long = 0L
    private var hostStateThrottledLastLabel: String? = null

    private var sendInterceptImeActionRegistered = false
    private var sendInterceptImeActionTimeoutMs: Int = 800
    private var pendingImeActionSendIntercept: PendingImeActionSendIntercept? = null
    private val functionKitId: String
        get() = functionKitManifest.id
    private val supportedRuntimePermissions: List<String>
        // Manifest runtimePermissions is an allowlist. Never "union" in host permissions here.
        get() =
            FunctionKitRuntimePermissionResolver.resolveSupported(
                manifestDeclared = functionKitManifest.runtimePermissions,
                hostSupported = FunctionKitDefaults.supportedPermissions
            )

    fun requestOpenOptions() {
        pendingOpenOptionsIntent = true
        flushPendingUiIntents()
    }

    fun requestOpenInvocation(
        invocationId: String?,
        bindingId: String? = null,
        bindingTitle: String? = null
    ) {
        val normalizedInvocationId = invocationId?.trim().orEmpty()
        if (normalizedInvocationId.isBlank()) {
            return
        }
        pendingEntryHash = buildOpenInvocationHash(normalizedInvocationId, bindingId, bindingTitle)
        val intentPayload =
            JSONObject()
                .put("kind", "open_invocation")
                .put("invocationId", normalizedInvocationId)
                .apply {
                    bindingId?.trim()?.takeIf { it.isNotBlank() }?.let { id ->
                        put("bindingId", id)
                    }
                    bindingTitle?.trim()?.takeIf { it.isNotBlank() }?.let { title ->
                        put("bindingTitle", title)
                    }
                }
        pendingOpenInvocationIntent = intentPayload
        flushPendingUiIntents()
        flushPendingEntryHash()
    }

    private fun flushPendingUiIntents() {
        val intentPayload =
            pendingOpenInvocationIntent
                ?: if (pendingOpenOptionsIntent) JSONObject().put("kind", "open_options") else null
                ?: return
        if (!windowAttached || !panelInitialized || !bridgeReady) {
            return
        }
        val kind = intentPayload.optString("kind")
        if (pendingOpenInvocationIntent != null) {
            pendingOpenInvocationIntent = null
        } else {
            pendingOpenOptionsIntent = false
        }
        host.dispatchHostStateUpdate(
            kitId = functionKitId,
            surface = Surface,
            label =
                when (kind) {
                    "open_invocation" -> "已打开执行详情"
                    else -> "已打开功能件设置"
                },
            details =
                JSONObject()
                    .put("intent", intentPayload)
        )
    }

    private fun buildEntryUrl(entryRelativePath: String): String {
        val normalizedEntryPath =
            entryRelativePath
                .replace("\\", "/")
                .trim('/')
                .also { value ->
                    require(value.isNotEmpty()) { "entryRelativePath must not be blank" }
                    require(!value.split('/').any { segment -> segment.isBlank() || segment == ".." }) {
                        "entryRelativePath must stay inside the local asset root"
                    }
                }

        return "${hostConfig.localOrigin}${hostConfig.normalizedAssetPathPrefix}$normalizedEntryPath"
    }

    private fun buildOpenInvocationHash(
        invocationId: String,
        bindingId: String? = null,
        bindingTitle: String? = null
    ): String {
        val parts = mutableListOf("fk_intent=open_invocation", "invocationId=${Uri.encode(invocationId)}")
        bindingId?.trim()?.takeIf { it.isNotBlank() }?.let { value ->
            parts += "bindingId=${Uri.encode(value)}"
        }
        bindingTitle?.trim()?.takeIf { it.isNotBlank() }?.let { value ->
            parts += "bindingTitle=${Uri.encode(value)}"
        }
        return "#${parts.joinToString("&")}"
    }

    private fun flushPendingEntryHash() {
        val hash = pendingEntryHash ?: return
        if (!panelInitialized) {
            return
        }
        if (!windowAttached || !bridgeReady) {
            return
        }
        if (!webViewResumed) {
            return
        }
        val baseUrl =
            webView.url
                ?.substringBefore("#")
                ?.takeIf { it.isNotBlank() }
                ?: buildEntryUrl(functionKitManifest.entryHtmlAssetPath)
        val targetUrl = "$baseUrl$hash"
        if (webView.url == targetUrl) {
            pendingEntryHash = null
            return
        }
        val jsHash = JSONObject.quote(hash)
        webView.post {
            if (webView.url?.substringBefore("#") == baseUrl) {
                webView.evaluateJavascript(
                    "try{window.location.hash=$jsHash;}catch(_e){}",
                    null
                )
            } else {
                webView.loadUrl(targetUrl)
            }
        }
        pendingEntryHash = null
    }

    override fun onCreateView(): View {
        ensureManifestStateInitialized()
        val baseHeight = resolveKeyboardBaseHeightPx()
        panelPeekHeightPx = resolvePanelPeekHeightPx(baseHeight)
        return rootView
    }

    override fun onAttached() {
        windowAttached = true
        ensureManifestStateInitialized()
        val baseHeight = resolveKeyboardBaseHeightPx()
        if (baseHeight > 0) {
            windowManagerHeightBeforeAttach = baseHeight
        } else if (windowManagerHeightBeforeAttach == null) {
            resolveKeyboardHeightFromPrefsPx().takeIf { it > 0 }?.let { fallbackHeight ->
                windowManagerHeightBeforeAttach = fallbackHeight
            }
        }
        applyPanelPeekHeightPx(resolvePanelPeekHeightPx(windowManagerHeightBeforeAttach ?: baseHeight))

        val keyboardView = windowManager.requireEssentialWindowView(KeyboardWindow)
        (keyboardView.parent as? ViewGroup)?.removeView(keyboardView)
        embeddedKeyboardContainer.removeAllViews()
        embeddedKeyboardContainer.addView(
            keyboardView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        embeddedKeyboardView = keyboardView
        embeddedKeyboardWindow = windowManager.getEssentialWindow(KeyboardWindow) as KeyboardWindow
        embeddedKeyboardWindow?.onAttached()

        syncCurrentInputState()
        refreshGrantedPermissions(notifyUi = panelInitialized)
        service.localInputTarget = this

        // Default to the expanded panel mode when users open a Function Kit, so the kit UI is
        // immediately usable without extra taps.
        if (!panelExpanded) {
            expandPanel(reason = "auto-open")
        }
        syncEmbeddedKeyboardLayout()

        val reloadCachedPanelForPendingLaunch = shouldReloadCachedPanelForPendingLaunch()
        if (reloadCachedPanelForPendingLaunch) {
            debugLog {
                "Reloading cached panel before attach kitId=$functionKitId " +
                    "pendingBindings=${pendingBindingInvocations.size} " +
                    "pendingRuntime=${pendingRuntimeMessages.size} " +
                    "hasOpenInvocation=${pendingOpenInvocationIntent != null} " +
                    "hasOpenOptions=$pendingOpenOptionsIntent " +
                    "hasEntryHash=${pendingEntryHash != null}"
            }
            reloadPanel()
        } else if (!panelInitialized) {
            val entryPath = functionKitManifest.entryHtmlAssetPath + pendingEntryHash.orEmpty()
            pendingEntryHash = null
            host.initialize(entryPath)
            panelInitialized = true
        }

        webView.onResume()
        webViewResumed = true
        FunctionKitTestRegistry.onWindowAttached(functionKitId, webView)
        host.dispatchHostStateUpdate(
            kitId = functionKitId,
            surface = Surface,
            label = "Android Function Kit 面板已打开 · ${FunctionKitHostDiagnostics.buildDisplayName(Const.versionName, BuildConfig.BUILD_GIT_HASH)}",
            details = buildHostDetails()
        )
        if (reloadCachedPanelForPendingLaunch) {
            debugLog { "Deferred pending launch delivery until bridge.ready kitId=$functionKitId" }
        }
        flushPendingBindingInvocations()
        flushPendingUiIntents()
        flushPendingEntryHash()
    }

    override fun onDetached() {
        windowAttached = false
        collapsePanelExpandedIfNeeded(reason = "host-detach")
        if (service.localInputTarget === this) {
            service.localInputTarget = null
        }
        inputObservePendingRunnable?.let { runnable ->
            webView.removeCallbacks(runnable)
        }
        inputObservePendingRunnable = null
        inputObserveEnabled = false
        inputObserveLastSignature = null
        pendingImeActionSendIntercept?.let { pending ->
            webView.removeCallbacks(pending.timeoutRunnable)
            pending.onDecision(true)
        }
        pendingImeActionSendIntercept = null
        sendInterceptImeActionRegistered = false
        embeddedKeyboardPinned = false
        applyEmbeddedKeyboardPinnedButtonState(pinnedKeyboardButton, embeddedKeyboardPinned)
        kawaiiBar.setFunctionKitCandidateDock(null)
        preedit.setFunctionKitSuppressed(false)
        if (composerState.open || composerState.focused) {
            // Never keep the composer open after the panel is detached, otherwise users will see it
            // unexpectedly next time they open the panel (or worse: typing gets routed to it).
            composerState =
                normalizeComposerState(
                    composerState.copy(
                        open = false,
                        focused = false,
                        source = "host",
                        lastAction = "host-detach"
                    )
                )
            syncEmbeddedKeyboardLayout()
        }
        embeddedKeyboardWindow?.onDetached()
        embeddedKeyboardWindow = null

        embeddedKeyboardView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        embeddedKeyboardView = null

        windowManagerHeightBeforeAttach?.let { height ->
            windowManager.view.layoutParams?.let { params ->
                if (params.height != height) {
                    params.height = height
                    windowManager.view.layoutParams = params
                }
            }
        }
        windowManagerHeightBeforeAttach = null

        webViewResumed = false
        webView.onPause()
        FunctionKitTestRegistry.onWindowDetached(functionKitId)
    }

    override fun onImeWindowHidden() {
        collapsePanelExpandedIfNeeded(reason = "ime-hidden")
    }

    private fun ensureManifestStateInitialized() {
        if (requestedPermissions.isNotEmpty() && grantedPermissions.isNotEmpty()) {
            return
        }
        if (requestedPermissions.isEmpty()) {
            requestedPermissions = supportedRuntimePermissions
        }
        if (grantedPermissions.isEmpty()) {
            grantedPermissions =
                FunctionKitPermissionPolicy.grantedPermissions(
                    requestedPermissions = requestedPermissions,
                    prefs = functionKitPrefs,
                    kitId = functionKitId
                )
        }
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        currentPackageName = info.packageName.orEmpty()
        currentInputType = info.inputType
        rememberCurrentInputSnapshot()
        syncEmbeddedKeyboardLayout()
        pushHostState("输入上下文已切换")
        scheduleInputObserveSync("start-input")
    }

    override fun onSelectionUpdate(start: Int, end: Int) {
        currentSelectionStart = start
        currentSelectionEnd = end
        rememberCurrentInputSnapshot()
        pushHostStateThrottled("光标或选择区已更新", throttleMs = 250)
        scheduleInputObserveSync("selection-update")
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        currentCandidateCount = data.candidates.size
        syncEmbeddedCandidateDock(shouldShowEmbeddedKeyboard())
    }

    override fun onClientPreeditUpdate(data: FormattedText) {
        currentPreeditText = data.toString()
        rememberCurrentInputSnapshot()
        if (windowAttached) {
            embeddedPreeditUi.update(
                FcitxEvent.InputPanelEvent.Data(
                    preedit = data,
                    auxUp = FormattedText.Empty,
                    auxDown = FormattedText.Empty
                )
            )
            embeddedPreeditUi.root.visibility =
                if (embeddedPreeditUi.visible) View.VISIBLE else View.INVISIBLE
        }
        syncEmbeddedCandidateDock(shouldShowEmbeddedKeyboard())
        scheduleInputObserveSync("preedit-update")
    }

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        rememberCurrentInputSnapshot()
        if (windowAttached) {
            embeddedPreeditUi.update(data)
            embeddedPreeditUi.root.visibility =
                if (embeddedPreeditUi.visible) View.VISIBLE else View.INVISIBLE
        }
        syncEmbeddedCandidateDock(shouldShowEmbeddedKeyboard())
    }

    override val title: String by lazy { FunctionKitRegistry.displayName(context, functionKitManifest) }

    override fun onCreateBarExtension(): View = barExtension

    internal fun enqueueBindingInvocation(
        binding: FunctionKitBindingEntry,
        trigger: FunctionKitBindingTrigger,
        clipboardText: String? = null,
        startHeadless: Boolean = false
    ): String? {
        val resolvedKitId =
            requestedKitId?.takeIf { it.isNotBlank() }
                ?: runCatching { functionKitId }.getOrNull()
        if (resolvedKitId != null && binding.kitId != resolvedKitId) {
            Log.w(
                "FunctionKitWindow",
                "Dropped binding invocation for mismatched kitId=${binding.kitId} resolved=$resolvedKitId"
            )
            return null
        }

        ensureManifestStateInitialized()
        refreshGrantedPermissions(notifyUi = false)

        val requestedPayloads = resolveBindingRequestedPayloads(binding, trigger)
        val missingPermissions = mutableSetOf<String>()
        val allowedPayloads = requestedPayloads.toMutableSet()

        if ("context.read" !in grantedPermissions &&
            (allowedPayloads.any { it.startsWith("selection.") } || "clipboard.text" in allowedPayloads)
        ) {
            missingPermissions += "context.read"
            allowedPayloads.removeAll(BindingSelectionPayloads)
            allowedPayloads.remove("clipboard.text")
        }

        val captureResult =
            FunctionKitBindingInvocationContext.capture(
                service = service,
                requestedPayloads = allowedPayloads,
                candidateCount = currentCandidateCount,
                preeditText = currentPreeditText
            )

        var payloadTruncated = captureResult.payloadTruncated
        val providedPayloads = captureResult.providedPayloads.toMutableSet()
        val clipboardTextForPayload =
            if ("clipboard.text" in allowedPayloads) {
                val raw = clipboardText ?: ClipboardManager.lastEntry?.text
                if (raw == null) {
                    null
                } else {
                    val (normalized, truncated) = truncateTextForBridge(raw, BindingClipboardTextMaxChars)
                    payloadTruncated = payloadTruncated || truncated
                    providedPayloads += "clipboard.text"
                    normalized
                }
            } else {
                null
            }

        val invocationId = "invk-${UUID.randomUUID()}"
        val invocation =
            BindingInvocation(
                invocationId = invocationId,
                trigger = trigger,
                bindingId = binding.bindingId,
                bindingTitle = binding.title,
                bindingPreferredPresentation = binding.preferredPresentation,
                bindingCategories = binding.categories ?: emptySet(),
                bindingEntry = binding.entry,
                requestedPayloads = requestedPayloads,
                providedPayloads = providedPayloads,
                missingPermissions = missingPermissions,
                payloadTruncated = payloadTruncated,
                capturedContext = captureResult.context,
                clipboardText = clipboardTextForPayload
            )
        rememberRecentBindingInvocation(invocation)
        pendingBindingInvocations.addLast(invocation)
        debugLog {
            "Enqueued binding invocation kitId=$functionKitId bindingId=${binding.bindingId} " +
                "title=${binding.title} trigger=${trigger.name.lowercase()} startHeadless=$startHeadless " +
                "windowAttached=$windowAttached panelInitialized=$panelInitialized webViewResumed=$webViewResumed " +
                "selectedLen=${captureResult.context.optString("selectedText").length} " +
                "beforeLen=${captureResult.context.optString("beforeCursor").length} " +
                "afterLen=${captureResult.context.optString("afterCursor").length} " +
                "preeditLen=${captureResult.context.optString("preeditText").length} " +
                "clipboardLen=${clipboardTextForPayload?.length ?: 0} " +
                "missingPermissions=${missingPermissions.joinToString(",")}"
        }
        if (startHeadless) {
            ensureHeadlessPanelInitialized(reason = "binding.invoke")
        }
        // Only flush immediately when the WebView is resumed.
        //
        // For panel-style bindings, callers typically enqueue first and then attach the window on the
        // next frame. Flushing while the window is detached would dispatch to a paused WebView and
        // the kit might never receive the invocation, leading to stale UI state (wrong tone/view).
        if (startHeadless || windowAttached) {
            flushPendingBindingInvocations()
        }
        return invocationId
    }

    private fun resolveBindingRequestedPayloads(
        binding: FunctionKitBindingEntry,
        trigger: FunctionKitBindingTrigger
    ): Set<String> {
        val declaredPayloads = binding.requestedPayloads
        if (binding.isTextBinding) {
            val payloads =
                when {
                    declaredPayloads != null -> declaredPayloads.toMutableSet()
                    else -> mutableSetOf<String>()
                }
            payloads.addAll(BindingSelectionPayloads)
            payloads.add("clipboard.text")
            return payloads
        }

        if (declaredPayloads != null) {
            return declaredPayloads
        }

        return when (trigger) {
            FunctionKitBindingTrigger.Clipboard -> DefaultBindingRequestedPayloadsClipboard
            FunctionKitBindingTrigger.Selection -> DefaultBindingRequestedPayloadsSelection
            FunctionKitBindingTrigger.Manual -> DefaultBindingRequestedPayloadsManual
        }
    }

    private fun truncateTextForBridge(
        value: String,
        maxChars: Int
    ): Pair<String, Boolean> {
        if (value.length <= maxChars) {
            return value to false
        }
        return value.take(maxChars) to true
    }

    private fun ensureHeadlessPanelInitialized(reason: String) {
        ensureManifestStateInitialized()
        syncCurrentInputState()
        refreshGrantedPermissions(notifyUi = false)

        if (!panelInitialized) {
            val entryPath = functionKitManifest.entryHtmlAssetPath + pendingEntryHash.orEmpty()
            pendingEntryHash = null
            host.initialize(entryPath)
            panelInitialized = true
        }
        webView.onResume()
        webViewResumed = true
        host.dispatchHostStateUpdate(
            kitId = functionKitId,
            surface = Surface,
            label = "Android Function Kit 运行中（后台）",
            details = buildHostDetails(currentExecutionConfig()).put("reason", reason)
        )
        flushPendingEntryHash()
    }

    private fun handleUiEnvelope(envelope: JSONObject) {
        FunctionKitEnvelopeProbe.recordInbound(envelope)
        kitStudioAttach.recordInbound(envelope)
        val type = envelope.optString("type")
        val replyTo = envelope.optString("messageId")
        val replyToHostMessageId = envelope.optString("replyTo")
        val surface = envelope.optString("surface").ifBlank { Surface }
        val payload = envelope.optJSONObject("payload") ?: JSONObject()

        if (type != "bridge.ready" && !bridgeReady) {
            debugLog { "Ignored UI envelope before bridge.ready kitId=$functionKitId type=$type surface=$surface" }
            return
        }

        when (type) {
            "bridge.ready" -> handleBridgeReady(replyTo, surface, payload)
            "context.request" -> handleContextRequest(replyTo, payload)
            "candidates.regenerate" -> handleRegenerate(replyTo, payload)
            "candidate.insert" -> handleCommit(replyTo, payload, "input.insert", replace = false)
            "candidate.replace" -> handleCommit(replyTo, payload, "input.replace", replace = true)
            "input.commitImage" -> handleCommitImage(replyTo, surface, payload)
            "input.observe.best_effort.start" -> handleInputObserveBestEffortStart(replyTo, surface, payload)
            "input.observe.best_effort.stop" -> handleInputObserveBestEffortStop(replyTo, surface, payload)
            "storage.get" -> handleStorageGet(replyTo, payload)
            "storage.set" -> handleStorageSet(replyTo, payload)
            "panel.state.update" -> handlePanelStateUpdate(replyTo, payload)
            "files.pick" -> handleFilesPick(replyTo, surface, payload)
            "files.download" -> handleFilesDownload(replyTo, surface, payload)
            "files.getUrl" -> handleFilesGetUrl(replyTo, surface, payload)
            "network.fetch" -> handleNetworkFetch(replyTo, surface, payload)
            "ai.request" -> handleAiRequest(replyTo, surface, payload)
            "ai.agent.list" -> handleAiAgentList(replyTo, surface)
            "ai.agent.run" -> handleAiAgentRun(replyTo, surface, payload)
            "runtime.message.send" -> handleRuntimeMessageSend(replyTo, surface, payload)
            "tasks.sync.request" -> handleTasksSyncRequest(replyTo, surface, payload)
            "task.cancel" -> handleTaskCancel(replyTo, surface, payload)
            "kits.sync.request" -> handleKitsSyncRequest(replyTo, surface, payload)
            "kits.open" -> handleKitsOpen(replyTo, surface, payload)
            "kits.install" -> handleKitsInstall(replyTo, surface, payload)
            "kits.uninstall" -> handleKitsUninstall(replyTo, surface, payload)
            "kits.settings.update" -> handleKitsSettingsUpdate(replyTo, surface, payload)
            "catalog.sources.get" -> handleCatalogSourcesGet(replyTo, surface, payload)
            "catalog.sources.set" -> handleCatalogSourcesSet(replyTo, surface, payload)
            "catalog.refresh" -> handleCatalogRefresh(replyTo, surface, payload)
            "send.intercept.ime_action.register" -> handleSendInterceptImeActionRegister(replyTo, surface, payload)
            "send.intercept.ime_action.unregister" -> handleSendInterceptImeActionUnregister(replyTo, surface, payload)
            "send.intercept.ime_action.result" -> handleSendInterceptImeActionResult(replyToHostMessageId, surface, payload)
            "composer.open" -> handleComposerOpen(replyTo, surface, payload)
            "composer.focus" -> handleComposerFocus(replyTo, surface, payload)
            "composer.update" -> handleComposerUpdate(replyTo, surface, payload)
            "composer.close" -> handleComposerClose(replyTo, surface, payload)
            "settings.open" -> handleSettingsOpen(replyTo, payload)
            else -> {
                host.dispatchBridgeError(
                    replyTo = replyTo,
                    kitId = functionKitId,
                    surface = surface,
                    code = "unsupported_message_type",
                    message = "Unsupported message type: $type",
                    retryable = false,
                    details = JSONObject().put("type", type)
                )
            }
        }
    }

    private fun handleHostEnvelope(envelope: JSONObject) {
        kitStudioAttach.recordOutbound(envelope)
    }

    private fun handleBridgeReady(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        val executionConfig = currentExecutionConfig()
        val supported = supportedRuntimePermissions
        val uiRequested = payload.optJSONArray("requestedPermissions").toStringList()
        requestedPermissions =
            FunctionKitRuntimePermissionResolver.resolveRequested(
                uiRequested = uiRequested,
                supported = supported,
                fallback = supported
            )
        refreshGrantedPermissions()
        renderSeed = 0
        sessionId = newSessionId()
        bridgeReady = true

        host.dispatchReadyAck(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = surface,
            sessionId = sessionId,
            grantedPermissions = grantedPermissions,
            hostInfo = buildHostInfo(executionConfig)
        )
        host.dispatchPermissionsSync(
            kitId = functionKitId,
            surface = surface,
            grantedPermissions = grantedPermissions
        )
        dispatchComposerStateSync(replyTo = null, surface = surface, reason = "bridge.ready")
        pushHostState("宿主握手完成")
        flushPendingBindingInvocations()
        flushPendingRuntimeMessages()
        flushPendingUiIntents()
        flushPendingEntryHash()
    }

    private fun flushPendingBindingInvocations() {
        if (!panelInitialized || !bridgeReady) {
            return
        }

        while (pendingBindingInvocations.isNotEmpty()) {
            val invocation = pendingBindingInvocations.removeFirst()
            debugLog {
                "Flushing binding invocation kitId=$functionKitId invocationId=${invocation.invocationId} " +
                    "bindingId=${invocation.bindingId} remaining=${pendingBindingInvocations.size}"
            }
            host.dispatchBindingInvoke(
                kitId = functionKitId,
                surface = Surface,
                payload = buildBindingInvokePayload(invocation)
            )
        }
    }

    private fun rememberRecentBindingInvocation(invocation: BindingInvocation) {
        synchronized(recentBindingInvocations) {
            recentBindingInvocations[invocation.invocationId] = invocation
            while (recentBindingInvocations.size > 16) {
                val eldestKey = recentBindingInvocations.entries.firstOrNull()?.key ?: break
                recentBindingInvocations.remove(eldestKey)
            }
        }
    }

    private fun requeueRecentBindingInvocationIfNeeded(invocationId: String) {
        if (invocationId.isBlank()) {
            return
        }
        if (pendingBindingInvocations.any { it.invocationId == invocationId }) {
            return
        }
        val invocation =
            synchronized(recentBindingInvocations) {
                recentBindingInvocations[invocationId]
            } ?: return
        pendingBindingInvocations.addLast(invocation)
        debugLog {
            "Requeued recent binding invocation kitId=$functionKitId invocationId=$invocationId " +
                "bindingId=${invocation.bindingId} for open"
        }
    }

    private fun shouldReloadCachedPanelForPendingLaunch(): Boolean {
        if (!panelInitialized || webViewResumed) {
            return false
        }
        return pendingBindingInvocations.isNotEmpty() ||
            pendingRuntimeMessages.isNotEmpty() ||
            pendingOpenInvocationIntent != null ||
            pendingOpenOptionsIntent ||
            pendingEntryHash != null
    }

    private fun flushPendingRuntimeMessages() {
        if (!panelInitialized || !bridgeReady) {
            return
        }

        while (pendingRuntimeMessages.isNotEmpty()) {
            val message = pendingRuntimeMessages.removeFirst()
            host.dispatchRuntimeMessage(
                kitId = functionKitId,
                surface = Surface,
                payload = buildRuntimeMessagePayload(message)
            )
        }
    }

    private fun buildRuntimeMessagePayload(message: RuntimeMessageDelivery): JSONObject =
        JSONObject()
            .put("fromKitId", message.fromKitId)
            .put("fromSurface", message.fromSurface?.trim()?.takeIf { it.isNotBlank() })
            .put("channel", message.channel?.trim()?.takeIf { it.isNotBlank() })
            .put("data", message.data)
            .put("sentAtEpochMs", message.sentAtEpochMs)

    private fun buildBindingInvokePayload(invocation: BindingInvocation): JSONObject {
        val binding =
            JSONObject()
                .put("id", invocation.bindingId)
                .put("title", invocation.bindingTitle)
                .apply {
                    invocation.bindingPreferredPresentation?.let { value ->
                        put("preferredPresentation", value)
                    }
                    if (invocation.bindingCategories.isNotEmpty()) {
                        put("categories", JSONArray(invocation.bindingCategories))
                    }
                    invocation.bindingEntry?.let { value ->
                        put("entry", value)
                    }
                }

        val payload =
            JSONObject()
                .put("invocationId", invocation.invocationId)
                .put("trigger", invocation.trigger.name.lowercase())
                .put(
                    "binding",
                    binding
                )
                .put("context", invocation.capturedContext)
                .put("createdAtEpochMs", invocation.createdAtEpochMs)
                .put("requestedPayloads", JSONArray(invocation.requestedPayloads))
                .put("providedPayloads", JSONArray(invocation.providedPayloads))
                .put("payloadLimits", FunctionKitBindingInvocationContext.payloadLimits())
                .put("payloadTruncated", invocation.payloadTruncated)

        invocation.clipboardText?.let { text ->
            payload.put("clipboardText", text)
        }
        if (invocation.missingPermissions.isNotEmpty()) {
            payload.put("missingPermissions", JSONArray(invocation.missingPermissions))
        }

        return payload
    }

    private fun handleContextRequest(replyTo: String, payload: JSONObject) {
        ensurePermission(replyTo, "context.read") ?: return

        val executionConfig = currentExecutionConfig()
        val preferredTone = payload.optString("preferredTone").ifBlank { "balanced" }
        val modifiers = payload.optJSONArray("modifiers").toStringList()
        val reason = payload.optString("reason").ifBlank { DefaultContextRequestReason }
        val contextSnapshot = buildContextSnapshot(preferredTone, modifiers)
        debugLog {
            "Context request snapshot kitId=$functionKitId " +
                "selectedLen=${contextSnapshot.optString("selectedText").length} " +
                "beforeLen=${contextSnapshot.optString("beforeCursor").length} " +
                "afterLen=${contextSnapshot.optString("afterCursor").length} " +
                "preeditLen=${contextSnapshot.optString("preeditText").length} " +
                "reason=$reason"
        }
        val contextPayload = buildContextPayload(contextSnapshot, preferredTone, modifiers, reason)

        host.dispatchContextSync(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = Surface,
            payload = contextPayload
        )
        if ("candidates.regenerate" in grantedPermissions) {
            renderCandidates(
                replyTo = replyTo,
                reason = reason,
                preferredTone = preferredTone,
                modifiers = modifiers,
                requestContext = contextSnapshot,
                executionConfig = executionConfig
            )
        }
    }

    private fun handleRegenerate(replyTo: String, payload: JSONObject) {
        ensurePermission(replyTo, "candidates.regenerate") ?: return

        val executionConfig = currentExecutionConfig()
        if (!executionConfig.remoteEnabled && !canUseLocalAiForCandidates(currentAiChatConfig())) {
            renderSeed += 1
        }
        val preferredTone = payload.optString("preferredTone").ifBlank { "balanced" }
        val modifiers = payload.optJSONArray("modifiers").toStringList()
        val contextSnapshot = buildContextSnapshot(preferredTone, modifiers)

        renderCandidates(
            replyTo = replyTo,
            reason = RemoteRegenerateReason,
            preferredTone = preferredTone,
            modifiers = modifiers,
            requestContext = contextSnapshot,
            executionConfig = executionConfig
        )
        if (!executionConfig.remoteEnabled) {
        pushHostState("已生成新一批候选")
        }
    }

    private fun handleCommit(
        replyTo: String,
        payload: JSONObject,
        permission: String,
        replace: Boolean
    ) {
        ensurePermission(replyTo, permission) ?: return

        val text = payload.optString("text").trim()
        if (text.isBlank()) {
            host.dispatchBridgeError(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = Surface,
                code = "invalid_commit_payload",
                message = "Missing candidate text",
                retryable = false
            )
            return
        }

        ContextCompat.getMainExecutor(service).execute {
            if (ImeBridgeState.isActive()) {
                val mode =
                    if (replace) {
                        FcitxInputMethodService.PendingImeBridgeCommitMode.ReplaceAllIfNoSelection
                    } else {
                        FcitxInputMethodService.PendingImeBridgeCommitMode.Insert
                    }
                service.queueImeBridgeCommit(text, mode)
                Toast.makeText(context, R.string.ime_bridge_pending_insert, Toast.LENGTH_SHORT).show()
                host.dispatchHostStateUpdate(
                    kitId = functionKitId,
                    surface = Surface,
                    label = "候选已准备写回输入框（请点击目标输入框）",
                    details = buildHostDetails().put("candidateId", payload.optString("candidateId"))
                )
                return@execute
            }

            val ic = service.currentInputConnection
            if (!replace || ic == null) {
                service.commitText(text, bypassLocalInputTarget = true)
            } else {
                // "Replace" semantics for Function Kit candidates should behave like chat-app smart replies:
                // replace the entire existing editor content when there is no explicit selection.
                ic.withBatchEdit {
                    ic.finishComposingText()
                    val hasSelection = currentSelectionStart != currentSelectionEnd
                    if (!hasSelection) {
                        val selectedAll = ic.performContextMenuAction(android.R.id.selectAll)
                        if (!selectedAll) {
                            val beforeCursor = ic.getTextBeforeCursor(10_000, 0)?.toString().orEmpty()
                            val afterCursor = ic.getTextAfterCursor(10_000, 0)?.toString().orEmpty()
                            if (beforeCursor.isNotEmpty() || afterCursor.isNotEmpty()) {
                                ic.deleteSurroundingText(beforeCursor.length, afterCursor.length)
                            }
                        }
                    }
                    ic.commitText(text, 1)
                }
            }
            host.dispatchHostStateUpdate(
                kitId = functionKitId,
                surface = Surface,
                label = if (replace) "候选已写回输入框（replace）" else "候选已写回输入框（insert）",
                details = buildHostDetails().put("candidateId", payload.optString("candidateId"))
            )
        }
    }

    private data class DecodedImagePayload(
        val mimeType: String,
        val bytes: ByteArray
    )

    private sealed class CommitImageDecodeResult {
        data class Ok(
            val payload: DecodedImagePayload
        ) : CommitImageDecodeResult()

        data class TooLarge(
            val mimeType: String,
            val approxBytes: Int
        ) : CommitImageDecodeResult()

        object Invalid : CommitImageDecodeResult()
    }

    private fun handleCommitImage(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "input.commitImage") ?: return

        val dataUrl = payload.optString("dataUrl").trim()
        val mimeTypeHint = payload.optString("mimeType").trim()
        val fileNameHint = payload.optString("fileName").trim()
        val label = payload.optString("label").trim().ifBlank { "Function Kit image" }

        if (dataUrl.isBlank()) {
            host.dispatchBridgeError(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                code = "invalid_commit_image_payload",
                message = "Missing image dataUrl",
                retryable = false
            )
            return
        }

        contentExecutor.execute {
            val decoded =
                when (
                    val decodedResult =
                        decodeCommitImagePayload(
                            dataUrl = dataUrl,
                            mimeTypeHint = mimeTypeHint
                        )
                ) {
                    is CommitImageDecodeResult.Ok -> decodedResult.payload
                    is CommitImageDecodeResult.TooLarge -> {
                        host.dispatchBridgeError(
                            replyTo = replyTo,
                            kitId = functionKitId,
                            surface = surface,
                            code = "image_too_large",
                            message = "Image exceeds size limit (${decodedResult.approxBytes} bytes).",
                            retryable = false,
                            details =
                                JSONObject()
                                    .put("maxBytes", MaxInlineImageBytes)
                                    .put("sizeBytes", decodedResult.approxBytes)
                                    .put("mimeType", decodedResult.mimeType)
                        )
                        return@execute
                    }
                    CommitImageDecodeResult.Invalid -> {
                        host.dispatchBridgeError(
                            replyTo = replyTo,
                            kitId = functionKitId,
                            surface = surface,
                            code = "invalid_commit_image_payload",
                            message = "Unable to decode image data",
                            retryable = false
                        )
                        return@execute
                    }
                }

            if (!decoded.mimeType.startsWith("image/")) {
                host.dispatchBridgeError(
                    replyTo = replyTo,
                    kitId = functionKitId,
                    surface = surface,
                    code = "unsupported_image_mime_type",
                    message = "Unsupported mime type: ${decoded.mimeType}",
                    retryable = false
                )
                return@execute
            }

            val safeFileStem = sanitizeCommitImageFileStem(fileNameHint).ifBlank { "function-kit-image" }
            val contentUri =
                try {
                    writeCommitImageToCache(
                        mimeType = decoded.mimeType,
                        bytes = decoded.bytes,
                        fileStem = safeFileStem
                    )
                } catch (error: Exception) {
                    host.dispatchBridgeError(
                        replyTo = replyTo,
                        kitId = functionKitId,
                        surface = surface,
                        code = "commit_image_store_failed",
                        message = "Failed to store image: ${error.message ?: "I/O error"}",
                        retryable = true
                    )
                    return@execute
                }

            ContextCompat.getMainExecutor(service).execute {
                val ic = service.currentInputConnection
                if (ic == null) {
                    host.dispatchBridgeError(
                        replyTo = replyTo,
                        kitId = functionKitId,
                        surface = surface,
                        code = "missing_input_connection",
                        message = "No active input connection.",
                        retryable = true
                    )
                    return@execute
                }

                val editorInfo = service.currentInputEditorInfo
                val supportedMimeTypes = EditorInfoCompat.getContentMimeTypes(editorInfo)
                val acceptsMimeType = supportedMimeTypes.any {
                    ClipDescription.compareMimeTypes(decoded.mimeType, it)
                }
                if (!acceptsMimeType) {
                    host.dispatchBridgeError(
                        replyTo = replyTo,
                        kitId = functionKitId,
                        surface = surface,
                        code = "target_does_not_accept_image",
                        message = "Target editor does not accept ${decoded.mimeType}.",
                        retryable = false,
                        details =
                            JSONObject()
                                .put("mimeType", decoded.mimeType)
                                .put("supportedMimeTypes", JSONArray(supportedMimeTypes.toList()))
                                .put("sourcePackage", editorInfo.packageName.orEmpty())
                    )
                    return@execute
                }

                val description = ClipDescription(label, arrayOf(decoded.mimeType))
                val contentInfo = InputContentInfoCompat(contentUri, description, null)
                val committed =
                    InputConnectionCompat.commitContent(
                        ic,
                        editorInfo,
                        contentInfo,
                        InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                        Bundle().apply { putString("kitId", functionKitId) }
                    )

                if (!committed) {
                    host.dispatchBridgeError(
                        replyTo = replyTo,
                        kitId = functionKitId,
                        surface = surface,
                        code = "commit_image_failed",
                        message = "InputConnectionCompat.commitContent returned false.",
                        retryable = false,
                        details =
                            JSONObject()
                                .put("mimeType", decoded.mimeType)
                                .put("supportedMimeTypes", JSONArray(supportedMimeTypes.toList()))
                    )
                    return@execute
                }

                host.dispatchHostStateUpdate(
                    kitId = functionKitId,
                    surface = surface,
                    label = "图片已写回输入框",
                    details =
                        buildHostDetails()
                            .put("mimeType", decoded.mimeType)
                            .put("fileName", safeFileStem)
                )
            }
        }
    }

    private fun decodeCommitImagePayload(
        dataUrl: String,
        mimeTypeHint: String
    ): CommitImageDecodeResult {
        val trimmed = dataUrl.trim()
        if (trimmed.isBlank()) {
            return CommitImageDecodeResult.Invalid
        }

        val (mimeType, encoded) =
            if (trimmed.startsWith("data:", ignoreCase = true)) {
                val commaIndex = trimmed.indexOf(',')
                if (commaIndex <= 5 || commaIndex >= trimmed.length - 1) {
                    return CommitImageDecodeResult.Invalid
                }
                val meta = trimmed.substring(5, commaIndex)
                val payload = trimmed.substring(commaIndex + 1)
                if (!meta.contains(";base64", ignoreCase = true)) {
                    return CommitImageDecodeResult.Invalid
                }
                val parsedMime = meta.substringBefore(';').trim().ifBlank { mimeTypeHint }
                if (parsedMime.isBlank()) {
                    return CommitImageDecodeResult.Invalid
                }
                parsedMime to payload
            } else {
                if (mimeTypeHint.isBlank()) {
                    return CommitImageDecodeResult.Invalid
                }
                mimeTypeHint to trimmed
            }

        val approxBytes = (encoded.length * 3) / 4
        if (approxBytes > MaxInlineImageBytes) {
            return CommitImageDecodeResult.TooLarge(mimeType, approxBytes)
        }

        val bytes =
            try {
                Base64.decode(encoded, Base64.DEFAULT)
            } catch (_error: IllegalArgumentException) {
                return CommitImageDecodeResult.Invalid
            }
        if (bytes.size > MaxInlineImageBytes) {
            return CommitImageDecodeResult.TooLarge(mimeType, bytes.size)
        }
        return CommitImageDecodeResult.Ok(DecodedImagePayload(mimeType, bytes))
    }

    private fun sanitizeCommitImageFileStem(rawName: String): String {
        val trimmed = rawName.trim()
        if (trimmed.isBlank()) {
            return ""
        }
        val baseName =
            trimmed
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .substringBeforeLast('.')
                .trim()
        if (baseName.isBlank()) {
            return ""
        }
        return baseName
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .take(48)
    }

    private fun commitImageExtension(mimeType: String): String {
        val normalized = mimeType.trim().lowercase()
        return when (normalized) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            "image/x-icon", "image/vnd.microsoft.icon" -> "ico"
            else ->
                normalized
                    .substringAfter('/', "img")
                    .replace(Regex("[^a-z0-9]+"), "")
                    .ifBlank { "img" }
        }
    }

    private fun writeCommitImageToCache(
        mimeType: String,
        bytes: ByteArray,
        fileStem: String
    ): Uri {
        val rootDir = File(context.cacheDir, "function-kit-content/${functionKitId}")
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }

        val cutoffMs = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
        rootDir.listFiles()?.forEach { entry ->
            if (entry.isFile && entry.lastModified() < cutoffMs) {
                entry.delete()
            }
        }

        val extension = commitImageExtension(mimeType)
        val filename = "${System.currentTimeMillis()}-${UUID.randomUUID()}-${fileStem}.${extension}"
        val file = File(rootDir, filename)
        file.outputStream().use { it.write(bytes) }

        return FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.functionkit.fileprovider",
            file
        )
    }

    private fun handleInputObserveBestEffortStart(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "input.observe.best_effort") ?: return

        inputObserveThrottleMs = payload.optInt("throttleMs", 120).coerceIn(16, 2000)
        inputObserveMaxChars = payload.optInt("maxChars", 64).coerceIn(16, 1024)
        inputObserveEnabled = true
        inputObserveLastSignature = null

        host.dispatchInputObserveBestEffortAck(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = surface,
            payload =
                JSONObject()
                    .put("enabled", true)
                    .put("bestEffort", true)
                    .put("throttleMs", inputObserveThrottleMs)
                    .put("maxChars", inputObserveMaxChars)
        )
        scheduleInputObserveSync("observe-start")
        pushHostState("已启用输入监听（best-effort）")
    }

    private fun handleInputObserveBestEffortStop(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        inputObservePendingRunnable?.let { runnable ->
            webView.removeCallbacks(runnable)
        }
        inputObservePendingRunnable = null
        inputObserveEnabled = false
        inputObserveLastSignature = null

        host.dispatchInputObserveBestEffortAck(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = surface,
            payload =
                JSONObject()
                    .put("enabled", false)
                    .put("bestEffort", true)
        )
        pushHostState("已关闭输入监听（best-effort）")
    }

    private fun scheduleInputObserveSync(trigger: String) {
        if (!inputObserveEnabled) {
            return
        }
        if (!windowAttached || !panelInitialized || !bridgeReady) {
            return
        }
        refreshGrantedPermissions(notifyUi = false)
        if ("input.observe.best_effort" !in grantedPermissions) {
            return
        }

        inputObservePendingRunnable?.let { runnable ->
            webView.removeCallbacks(runnable)
        }

        val nowMs = System.currentTimeMillis()
        val delayMs = (inputObserveThrottleMs.toLong() - (nowMs - inputObserveLastSentAtEpochMs)).coerceAtLeast(0L)
        val runnable =
            Runnable {
                inputObservePendingRunnable = null

                if (!inputObserveEnabled) {
                    return@Runnable
                }
                if (!windowAttached || !panelInitialized || !bridgeReady) {
                    return@Runnable
                }
                refreshGrantedPermissions(notifyUi = false)
                if ("input.observe.best_effort" !in grantedPermissions) {
                    return@Runnable
                }

                val contextSnapshot =
                    buildContextSnapshot(
                        preferredTone = "balanced",
                        modifiers = emptyList(),
                        maxChars = inputObserveMaxChars
                    )
                val signature =
                    buildString {
                        append(contextSnapshot.optString("sourcePackage"))
                        append('|')
                        append(contextSnapshot.optInt("selectionStart"))
                        append('-')
                        append(contextSnapshot.optInt("selectionEnd"))
                        append('|')
                        append(contextSnapshot.optString("selectedText"))
                        append('|')
                        append(contextSnapshot.optString("beforeCursor"))
                        append('|')
                        append(contextSnapshot.optString("afterCursor"))
                        append('|')
                        append(contextSnapshot.optString("preeditText"))
                    }
                if (signature == inputObserveLastSignature) {
                    return@Runnable
                }

                inputObserveLastSignature = signature
                inputObserveLastSentAtEpochMs = System.currentTimeMillis()

                val observePayload =
                    JSONObject()
                        .put("context", contextSnapshot)
                        .put(
                            "request",
                            JSONObject()
                                .put("reason", "input.observe.best_effort")
                                .put("trigger", trigger)
                        )
                        .put(
                            "observe",
                            JSONObject()
                                .put("bestEffort", true)
                                .put("throttleMs", inputObserveThrottleMs)
                                .put("maxChars", inputObserveMaxChars)
                        )

                host.dispatchContextSync(
                    replyTo = null,
                    kitId = functionKitId,
                    surface = Surface,
                    payload = observePayload
                )
            }

        inputObservePendingRunnable = runnable
        if (delayMs <= 0L) {
            webView.post(runnable)
        } else {
            webView.postDelayed(runnable, delayMs)
        }
    }

    private fun handleSendInterceptImeActionRegister(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "send.intercept.ime_action") ?: return

        sendInterceptImeActionTimeoutMs = payload.optInt("timeoutMs", 800).coerceIn(100, 5000)
        sendInterceptImeActionRegistered = true

        host.dispatchSendInterceptImeActionAck(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = surface,
            payload =
                JSONObject()
                    .put("registered", true)
                    .put("timeoutMs", sendInterceptImeActionTimeoutMs)
        )
        syncEmbeddedKeyboardLayout()
        pushHostState("已启用发送拦截（IME action）；如需测试请点面板右上角键盘图标固定键盘")
    }

    private fun handleSendInterceptImeActionUnregister(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        sendInterceptImeActionRegistered = false
        pendingImeActionSendIntercept?.let { pending ->
            webView.removeCallbacks(pending.timeoutRunnable)
            pending.onDecision(true)
        }
        pendingImeActionSendIntercept = null

        host.dispatchSendInterceptImeActionAck(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = surface,
            payload = JSONObject().put("registered", false)
        )
        syncEmbeddedKeyboardLayout()
        pushHostState("已关闭发送拦截（IME action）")
    }

    private fun handleSendInterceptImeActionResult(
        replyToHostMessageId: String,
        surface: String,
        payload: JSONObject
    ) {
        if (replyToHostMessageId.isBlank()) {
            return
        }

        val pending = pendingImeActionSendIntercept ?: return
        if (pending.requestMessageId != replyToHostMessageId) {
            return
        }

        webView.removeCallbacks(pending.timeoutRunnable)
        pendingImeActionSendIntercept = null

        val allow = payload.optBoolean("allow", true)
        pushHostState(if (allow) "发送已放行" else "发送已被拦截")
        pending.onDecision(allow)
    }

    private fun handleStorageGet(replyTo: String, payload: JSONObject) {
        ensurePermission(replyTo, "storage.read") ?: return

        val values = JSONObject()
        payload.optJSONArray("keys").toStringList().forEach { key ->
            readStorageValue(key)?.let { values.put(key, JSONObject.wrap(it)) }
        }

        host.dispatchStorageSync(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = Surface,
            payload = JSONObject().put("values", values)
        )
    }

    private fun handleStorageSet(replyTo: String, payload: JSONObject) {
        ensurePermission(replyTo, "storage.write") ?: return

        val values = payload.optJSONObject("values") ?: JSONObject()
        val editor = storage.edit()
        values.keys().forEach { key ->
            writeStorageValue(editor, key, values.opt(key))
        }
        editor.apply()

        host.dispatchStorageSync(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = Surface,
            payload = JSONObject().put("values", readAllStorageValues())
        )
        pushHostState("功能件存储已更新")
    }

    private fun handlePanelStateUpdate(replyTo: String, payload: JSONObject) {
        ensurePermission(replyTo, "panel.state.write") ?: return

        host.dispatchPanelStateAck(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = Surface,
            payload = JSONObject().put("patch", payload.optJSONObject("patch") ?: JSONObject())
        )
    }

    private fun handleFilesPick(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "files.pick") ?: return

        if (pendingFilePickRequestId != null) {
            host.dispatchBridgeError(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                code = "files_pick_in_progress",
                message = "files.pick already has an in-flight request.",
                retryable = true
            )
            return
        }

        val requestId = "files-pick-${UUID.randomUUID()}"
        pendingFilePickRequestId = requestId

        val allowMultiple = payload.optBoolean("multiple", false)
        val acceptMimeTypes = payload.optJSONArray("acceptMimeTypes").toStringList()

        FunctionKitFilePickerRegistry.register(requestId) { result ->
            pendingFilePickRequestId = null

            val files = JSONArray()
            result.uris.forEach { uri ->
                val entry = fileStore.put(uri)
                files.put(
                    JSONObject()
                        .put("fileId", entry.fileId)
                        .put("name", entry.name)
                        .put("mimeType", entry.mimeType)
                        .put("sizeBytes", entry.sizeBytes)
                )
            }

            host.dispatchFilesPickResult(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                payload =
                    JSONObject()
                        .put("ok", true)
                        .put("canceled", result.uris.isEmpty())
                        .put("files", files)
            )
        }

        val pickerIntent =
            Intent(service, FunctionKitFilePickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(FunctionKitFilePickerActivity.ExtraRequestId, requestId)
                putExtra(FunctionKitFilePickerActivity.ExtraResumeKitId, functionKitId)
                putExtra(FunctionKitFilePickerActivity.ExtraResumePackageName, currentPackageName)
                putExtra(FunctionKitFilePickerActivity.ExtraAllowMultiple, allowMultiple)
                if (acceptMimeTypes.isNotEmpty()) {
                    putExtra(
                        FunctionKitFilePickerActivity.ExtraAcceptMimeTypes,
                        acceptMimeTypes.toTypedArray()
                    )
                }
            }

        runCatching {
            service.startActivity(pickerIntent)
        }.onFailure { error ->
            pendingFilePickRequestId = null
            FunctionKitFilePickerRegistry.cancel(requestId)
            host.dispatchBridgeError(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                code = "files_pick_failed_to_launch",
                message = "files.pick failed to launch Android picker: ${error.message ?: "unknown error"}",
                retryable = true
            )
        }
    }

    private fun handleFilesDownload(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "files.download") ?: return
        host.dispatchBridgeError(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = surface,
            code = "files_download_not_implemented",
            message = "files.download is not implemented on Android host yet.",
            retryable = false,
            details = JSONObject().put("payload", payload)
        )
    }

    private fun handleFilesGetUrl(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "files.download") ?: return
        host.dispatchBridgeError(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = surface,
            code = "files_get_url_not_implemented",
            message = "files.getUrl is not implemented on Android host yet.",
            retryable = false,
            details = JSONObject().put("payload", payload)
        )
    }

    private fun handleSettingsOpen(
        replyTo: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "settings.open") ?: return

        val section = payload.optString("section").trim().lowercase()
        if (section == "ai") {
            AppUtil.launchMainToAiSettings(context)
        } else {
            AppUtil.launchMainToFunctionKitDetail(context, functionKitId)
        }
        host.dispatchHostStateUpdate(
            kitId = functionKitId,
            surface = Surface,
            label = if (section == "ai") "已打开 AI 设置" else "已打开功能件设置"
        )
    }

    private fun handleTasksSyncRequest(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        val includeHistory = payload.optBoolean("includeHistory", true)
        val historyLimit = payload.optInt("historyLimit", 30).coerceAtLeast(0)

        host.dispatchTasksSync(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = surface,
            payload =
                taskTracker.buildSyncPayload(
                    surface = surface,
                    includeHistory = includeHistory,
                    historyLimit = historyLimit
                )
        )
    }

    private fun handleTaskCancel(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        val taskId = payload.optString("taskId").trim()
        if (taskId.isBlank()) {
            host.dispatchBridgeError(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                code = "task_cancel_invalid_payload",
                message = "task.cancel requires a non-empty taskId.",
                retryable = false
            )
            return
        }

        val reason = payload.optString("reason").trim().takeIf { it.isNotBlank() }
        val decision = taskTracker.requestCancel(taskId = taskId, reason = reason)
        host.dispatchTaskCancelAck(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = surface,
            payload = decision.toJson(taskId)
        )
    }

    private fun handleKitsSyncRequest(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "kits.manage") ?: return
        val includeDisabled = payload.optBoolean("includeDisabled", false)
        dispatchKitsSyncSnapshot(replyTo = replyTo, surface = surface, includeDisabled = includeDisabled)
    }

    private fun handleKitsOpen(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "kits.manage") ?: return

        val targetKitId = payload.optString("kitId").trim()
        if (targetKitId.isBlank()) {
            host.dispatchBridgeError(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                code = "kits_open_invalid_payload",
                message = "kits.open requires a non-empty kitId.",
                retryable = false
            )
            return
        }

        if (targetKitId == functionKitId) {
            host.dispatchKitsOpenResult(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                payload = JSONObject().put("ok", true).put("kitId", targetKitId)
            )
            return
        }

        val installed = FunctionKitRegistry.listInstalled(context).any { it.id == targetKitId }
        if (!installed) {
            host.dispatchBridgeError(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                code = "kit_not_found",
                message = "Kit is not installed: $targetKitId",
                retryable = false
            )
            return
        }

        if (!FunctionKitKitSettings.isKitEnabled(targetKitId)) {
            host.dispatchBridgeError(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                code = "kit_disabled",
                message = "Kit is disabled: $targetKitId",
                retryable = false
            )
            return
        }

        host.dispatchKitsOpenResult(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = surface,
            payload = JSONObject().put("ok", true).put("kitId", targetKitId)
        )

        webView.post {
            windowManager.attachWindow(windowPool.require(targetKitId))
        }
    }

    private fun dispatchKitsSyncSnapshot(
        replyTo: String?,
        surface: String,
        includeDisabled: Boolean
    ) {
        val installed = FunctionKitRegistry.listInstalled(context)
        val kitsJson = JSONArray()

        installed.forEach { manifest ->
            val kitId = manifest.id
            val enabled = FunctionKitKitSettings.isKitEnabled(kitId)
            if (!includeDisabled && !enabled) {
                return@forEach
            }

            kitsJson.put(
                manifest.toJson()
                    .put("kitId", kitId)
                    .put("displayName", FunctionKitRegistry.displayName(context, manifest))
                    .put("enabled", enabled)
                    .put("pinned", FunctionKitKitSettings.isKitPinned(kitId))
                    .put("userInstalled", FunctionKitPackageManager.isUserInstalled(context, kitId))
                    .put(
                        "source",
                        if (FunctionKitPackageManager.isUserInstalled(context, kitId)) "user" else "bundled"
                    )
            )
        }

        host.dispatchKitsSync(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = surface,
            payload = JSONObject().put("kits", kitsJson)
        )
    }

    private fun handleKitsSettingsUpdate(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "kits.manage") ?: return

        val kitId = payload.optString("kitId").trim()
        if (kitId.isBlank()) {
            host.dispatchBridgeError(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                code = "kits_settings_invalid_payload",
                message = "kits.settings.update requires a non-empty kitId.",
                retryable = false
            )
            return
        }

        val installed = FunctionKitRegistry.listInstalled(context).any { it.id == kitId }
        if (!installed) {
            host.dispatchBridgeError(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                code = "kit_not_found",
                message = "Kit is not installed: $kitId",
                retryable = false
            )
            return
        }

        val patch = payload.optJSONObject("patch") ?: JSONObject()
        if (patch.has("enabled")) {
            FunctionKitKitSettings.setKitEnabled(kitId, patch.optBoolean("enabled", true))
        }
        if (patch.has("pinned")) {
            FunctionKitKitSettings.setKitPinned(kitId, patch.optBoolean("pinned", false))
        }

        patch.optJSONObject("permissionOverrides")?.let { overrides ->
            val iterator = overrides.keys()
            while (iterator.hasNext()) {
                val permission = iterator.next().trim()
                if (permission.isBlank()) {
                    continue
                }
                when (val value = overrides.opt(permission)) {
                    JSONObject.NULL -> FunctionKitKitSettings.setPermissionOverride(kitId, permission, null)
                    is Boolean -> FunctionKitKitSettings.setPermissionOverride(kitId, permission, value)
                }
            }
        }

        host.dispatchKitsSettingsUpdateResult(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = surface,
            payload = JSONObject().put("ok", true).put("kitId", kitId)
        )
        dispatchKitsSyncSnapshot(replyTo = null, surface = surface, includeDisabled = true)
    }

    private fun handleKitsInstall(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "kits.manage") ?: return

        val source = payload.optJSONObject("source") ?: JSONObject()
        val kind = source.optString("kind").trim().lowercase()
        val taskTitle: String? =
            payload.optJSONObject("task")?.optString("title")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        val taskId =
            taskTracker.create(
                kind = "kits.install",
                surface = surface,
                title = taskTitle,
                status = "queued",
                cancellable = true,
                progress = JSONObject().put("stage", "queued").put("sourceKind", kind)
            )

        val requestSessionId = sessionId
        val future =
            remoteExecutor.submit {
                try {
                    taskTracker.update(
                        taskId = taskId,
                        status = "running",
                        progress = JSONObject().put("stage", "install").put("sourceKind", kind)
                    )
                    val outcome = executeKitInstall(source)
                    ContextCompat.getMainExecutor(service).execute {
                        if (requestSessionId != sessionId) {
                            return@execute
                        }

                        if (taskTracker.cancelRequested(taskId)) {
                            taskTracker.update(taskId = taskId, status = "canceled")
                            host.dispatchBridgeError(
                                replyTo = replyTo,
                                kitId = functionKitId,
                                surface = surface,
                                code = "task_canceled",
                                message = "kits.install canceled.",
                                retryable = false,
                                details = JSONObject().put("taskId", taskId)
                            )
                            return@execute
                        }

                        when (outcome) {
                            is FunctionKitPackageManager.InstallOutcome.Ok -> {
                                taskTracker.update(
                                    taskId = taskId,
                                    status = "succeeded",
                                    result = JSONObject().put("summary", "Installed ${outcome.kitId}")
                                )
                                host.dispatchKitsInstallResult(
                                    replyTo = replyTo,
                                    kitId = functionKitId,
                                    surface = surface,
                                    payload =
                                        JSONObject()
                                            .put("ok", true)
                                            .put("kitId", outcome.kitId)
                                            .put("replaced", outcome.replaced)
                                            .put("taskId", taskId)
                                )
                                dispatchKitsSyncSnapshot(replyTo = null, surface = surface, includeDisabled = true)
                                FunctionKitSnackbars.showKitInstalled(
                                    context = context,
                                    windowManager = windowManager,
                                    theme = theme,
                                    kitId = outcome.kitId,
                                    replaced = outcome.replaced
                                ) {
                                    val window = windowPool.require(outcome.kitId)
                                    windowManager.view.post { windowManager.attachWindow(window) }
                                }
                            }
                            is FunctionKitPackageManager.InstallOutcome.Error -> {
                                taskTracker.update(
                                    taskId = taskId,
                                    status = "failed",
                                    error =
                                        JSONObject()
                                            .put("code", "kits_install_failed")
                                            .put("message", outcome.message)
                                            .put("retryable", true)
                                )
                                host.dispatchBridgeError(
                                    replyTo = replyTo,
                                    kitId = functionKitId,
                                    surface = surface,
                                    code = "kits_install_failed",
                                    message = outcome.message,
                                    retryable = true,
                                    details = JSONObject().put("taskId", taskId)
                                )
                            }
                        }
                    }
                } catch (error: RemoteInferenceException) {
                    ContextCompat.getMainExecutor(service).execute {
                        taskTracker.update(
                            taskId = taskId,
                            status = "failed",
                            error =
                                JSONObject()
                                    .put("code", error.code)
                                    .put("message", error.message)
                                    .put("retryable", error.retryable)
                                    .put("details", JSONObject.wrap(error.details))
                        )
                        if (requestSessionId != sessionId) {
                            return@execute
                        }
                        host.dispatchBridgeError(
                            replyTo = replyTo,
                            kitId = functionKitId,
                            surface = surface,
                            code = error.code,
                            message = error.message,
                            retryable = error.retryable,
                            details =
                                JSONObject()
                                    .put("taskId", taskId)
                                    .put("statusCode", error.statusCode)
                                    .put("details", JSONObject.wrap(error.details))
                        )
                    }
                } catch (error: Throwable) {
                    ContextCompat.getMainExecutor(service).execute {
                        taskTracker.update(
                            taskId = taskId,
                            status = "failed",
                            error =
                                JSONObject()
                                    .put("code", "kits_install_unexpected_error")
                                    .put("message", error.message ?: "Unexpected error")
                                    .put("retryable", false)
                                    .put("details", JSONObject().put("kind", error.javaClass.name))
                        )
                        if (requestSessionId != sessionId) {
                            return@execute
                        }
                        host.dispatchBridgeError(
                            replyTo = replyTo,
                            kitId = functionKitId,
                            surface = surface,
                            code = "kits_install_unexpected_error",
                            message = "kits.install failed: ${error.message ?: "Unexpected error"}",
                            retryable = false,
                            details =
                                JSONObject()
                                    .put("taskId", taskId)
                                    .put("kind", error.javaClass.name)
                        )
                    }
                }
            }

        taskTracker.attachFuture(taskId, future)
    }

    private fun handleKitsUninstall(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "kits.manage") ?: return

        val targetKitId = payload.optString("kitId").trim()
        if (targetKitId.isBlank()) {
            host.dispatchBridgeError(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                code = "kits_uninstall_invalid_payload",
                message = "kits.uninstall requires a non-empty kitId.",
                retryable = false
            )
            return
        }

        if (targetKitId == functionKitId) {
            host.dispatchBridgeError(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                code = "kits_uninstall_blocked",
                message = "Cannot uninstall the currently running kit.",
                retryable = false
            )
            return
        }

        if (!FunctionKitPackageManager.isUserInstalled(context, targetKitId)) {
            host.dispatchBridgeError(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                code = "kits_uninstall_not_user_installed",
                message = "Only user-installed kits can be uninstalled: $targetKitId",
                retryable = false
            )
            return
        }

        val taskTitle: String? =
            payload.optJSONObject("task")?.optString("title")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        val taskId =
            taskTracker.create(
                kind = "kits.uninstall",
                surface = surface,
                title = taskTitle,
                status = "queued",
                cancellable = true,
                progress = JSONObject().put("stage", "queued").put("kitId", targetKitId)
            )

        val requestSessionId = sessionId
        val future =
            remoteExecutor.submit {
                try {
                    taskTracker.update(
                        taskId = taskId,
                        status = "running",
                        progress = JSONObject().put("stage", "uninstall").put("kitId", targetKitId)
                    )
                    val ok = FunctionKitPackageManager.uninstall(context, targetKitId)
                    ContextCompat.getMainExecutor(service).execute {
                        if (requestSessionId != sessionId) {
                            return@execute
                        }

                        if (taskTracker.cancelRequested(taskId)) {
                            taskTracker.update(taskId = taskId, status = "canceled")
                            host.dispatchBridgeError(
                                replyTo = replyTo,
                                kitId = functionKitId,
                                surface = surface,
                                code = "task_canceled",
                                message = "kits.uninstall canceled.",
                                retryable = false,
                                details = JSONObject().put("taskId", taskId)
                            )
                            return@execute
                        }

                        if (!ok) {
                            taskTracker.update(
                                taskId = taskId,
                                status = "failed",
                                error =
                                    JSONObject()
                                        .put("code", "kits_uninstall_failed")
                                        .put("message", "Failed to uninstall kit: $targetKitId")
                                        .put("retryable", true)
                            )
                            host.dispatchBridgeError(
                                replyTo = replyTo,
                                kitId = functionKitId,
                                surface = surface,
                                code = "kits_uninstall_failed",
                                message = "Failed to uninstall kit: $targetKitId",
                                retryable = true,
                                details = JSONObject().put("taskId", taskId)
                            )
                            return@execute
                        }

                        FunctionKitKitSettings.clearAll(targetKitId)
                        taskTracker.update(
                            taskId = taskId,
                            status = "succeeded",
                            result = JSONObject().put("summary", "Uninstalled $targetKitId")
                        )
                        host.dispatchKitsUninstallResult(
                            replyTo = replyTo,
                            kitId = functionKitId,
                            surface = surface,
                            payload =
                                JSONObject()
                                    .put("ok", true)
                                    .put("kitId", targetKitId)
                                    .put("taskId", taskId)
                        )
                        dispatchKitsSyncSnapshot(replyTo = null, surface = surface, includeDisabled = true)
                    }
                } catch (error: Throwable) {
                    ContextCompat.getMainExecutor(service).execute {
                        taskTracker.update(
                            taskId = taskId,
                            status = "failed",
                            error =
                                JSONObject()
                                    .put("code", "kits_uninstall_unexpected_error")
                                    .put("message", error.message ?: "Unexpected error")
                                    .put("retryable", false)
                                    .put("details", JSONObject().put("kind", error.javaClass.name))
                        )
                        if (requestSessionId != sessionId) {
                            return@execute
                        }
                        host.dispatchBridgeError(
                            replyTo = replyTo,
                            kitId = functionKitId,
                            surface = surface,
                            code = "kits_uninstall_unexpected_error",
                            message = "kits.uninstall failed: ${error.message ?: "Unexpected error"}",
                            retryable = false,
                            details =
                                JSONObject()
                                    .put("taskId", taskId)
                                    .put("kind", error.javaClass.name)
                        )
                    }
                }
            }

        taskTracker.attachFuture(taskId, future)
    }

    private fun handleCatalogSourcesGet(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "kits.manage") ?: return
        dispatchCatalogSourcesSnapshot(replyTo = replyTo, surface = surface)
    }

    private fun handleCatalogSourcesSet(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "kits.manage") ?: return

        val sourcesNode = payload.optJSONArray("sources")
        if (sourcesNode == null) {
            host.dispatchBridgeError(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                code = "catalog_sources_invalid_payload",
                message = "catalog.sources.set requires sources[].",
                retryable = false
            )
            return
        }

        val sources =
            buildList {
                for (index in 0 until sourcesNode.length()) {
                    val item = sourcesNode.optJSONObject(index) ?: continue
                    val url = item.optString("url").trim()
                    if (url.isBlank()) continue
                    val enabled = if (item.has("enabled")) item.optBoolean("enabled", true) else true
                    add(FunctionKitCatalogStore.Source(url = url, enabled = enabled))
                }
            }

        FunctionKitCatalogStore.saveSources(sources)
        dispatchCatalogSourcesSnapshot(replyTo = replyTo, surface = surface)
    }

    private fun dispatchCatalogSourcesSnapshot(
        replyTo: String?,
        surface: String
    ) {
        val sourcesJson = JSONArray()
        FunctionKitCatalogStore.getSources(context).forEach { source ->
            sourcesJson.put(source.toJson())
        }
        host.dispatchCatalogSourcesSync(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = surface,
            payload = JSONObject().put("sources", sourcesJson)
        )
    }

    private fun handleCatalogRefresh(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "kits.manage") ?: return

        val requestedUrl = payload.optString("url").trim()
        val sources =
            if (requestedUrl.isNotBlank()) {
                listOf(FunctionKitCatalogStore.Source(url = requestedUrl, enabled = true))
            } else {
                FunctionKitCatalogStore.getSources(context).filter { it.enabled }
            }

        val taskTitle: String? =
            payload.optJSONObject("task")?.optString("title")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        val taskId =
            taskTracker.create(
                kind = "catalog.refresh",
                surface = surface,
                title = taskTitle,
                status = "queued",
                cancellable = true,
                progress = JSONObject().put("stage", "queued").put("sourceCount", sources.size)
            )

        val requestSessionId = sessionId
        val future =
            remoteExecutor.submit {
                try {
                    taskTracker.update(
                        taskId = taskId,
                        status = "running",
                        progress = JSONObject().put("stage", "fetch").put("sourceCount", sources.size)
                    )
                    val packages = executeCatalogRefresh(sources)
                    ContextCompat.getMainExecutor(service).execute {
                        if (requestSessionId != sessionId) {
                            return@execute
                        }

                        if (taskTracker.cancelRequested(taskId)) {
                            taskTracker.update(taskId = taskId, status = "canceled")
                            host.dispatchBridgeError(
                                replyTo = replyTo,
                                kitId = functionKitId,
                                surface = surface,
                                code = "task_canceled",
                                message = "catalog.refresh canceled.",
                                retryable = false,
                                details = JSONObject().put("taskId", taskId)
                            )
                            return@execute
                        }

                        taskTracker.update(
                            taskId = taskId,
                            status = "succeeded",
                            result = JSONObject().put("summary", "Fetched ${packages.size} packages")
                        )

                        val packagesJson = JSONArray()
                        packages.forEach { pkg -> packagesJson.put(pkg) }

                        host.dispatchCatalogSync(
                            replyTo = replyTo,
                            kitId = functionKitId,
                            surface = surface,
                            payload =
                                JSONObject()
                                    .put("packages", packagesJson)
                                    .put("taskId", taskId)
                        )
                    }
                } catch (error: RemoteInferenceException) {
                    ContextCompat.getMainExecutor(service).execute {
                        taskTracker.update(
                            taskId = taskId,
                            status = "failed",
                            error =
                                JSONObject()
                                    .put("code", error.code)
                                    .put("message", error.message)
                                    .put("retryable", error.retryable)
                                    .put("details", JSONObject.wrap(error.details))
                        )
                        if (requestSessionId != sessionId) {
                            return@execute
                        }
                        host.dispatchBridgeError(
                            replyTo = replyTo,
                            kitId = functionKitId,
                            surface = surface,
                            code = error.code,
                            message = error.message,
                            retryable = error.retryable,
                            details =
                                JSONObject()
                                    .put("taskId", taskId)
                                    .put("statusCode", error.statusCode)
                                    .put("details", JSONObject.wrap(error.details))
                        )
                    }
                } catch (error: Throwable) {
                    ContextCompat.getMainExecutor(service).execute {
                        taskTracker.update(
                            taskId = taskId,
                            status = "failed",
                            error =
                                JSONObject()
                                    .put("code", "catalog_refresh_unexpected_error")
                                    .put("message", error.message ?: "Unexpected error")
                                    .put("retryable", false)
                                    .put("details", JSONObject().put("kind", error.javaClass.name))
                        )
                        if (requestSessionId != sessionId) {
                            return@execute
                        }
                        host.dispatchBridgeError(
                            replyTo = replyTo,
                            kitId = functionKitId,
                            surface = surface,
                            code = "catalog_refresh_unexpected_error",
                            message = "catalog.refresh failed: ${error.message ?: "Unexpected error"}",
                            retryable = false,
                            details =
                                JSONObject()
                                    .put("taskId", taskId)
                                    .put("kind", error.javaClass.name)
                        )
                    }
                }
            }

        taskTracker.attachFuture(taskId, future)
    }

    private fun handleNetworkFetch(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "network.fetch") ?: return

        val rawUrl =
            payload.optString("url")
                .ifBlank { payload.optJSONObject("request")?.optString("url").orEmpty() }
                .trim()
        if (rawUrl.isBlank()) {
            host.dispatchBridgeError(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                code = "network_fetch_invalid_payload",
                message = "network.fetch requires a non-empty url.",
                retryable = false
            )
            return
        }

        val init = payload.optJSONObject("init") ?: JSONObject()
        val executionConfig = currentExecutionConfig()
        val requestSessionId = sessionId
        val taskTitle: String? =
            payload.optJSONObject("task")?.optString("title")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: init.optJSONObject("task")?.optString("title")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
        val taskId =
            taskTracker.create(
                kind = "network.fetch",
                surface = surface,
                title = taskTitle,
                status = "queued",
                cancellable = true,
                progress = JSONObject().put("stage", "queued").put("url", rawUrl)
            )

        val future =
            try {
                remoteExecutor.submit {
                    try {
                        taskTracker.update(
                            taskId = taskId,
                            status = "running",
                            progress = JSONObject().put("stage", "request").put("url", rawUrl)
                        )
                        val responsePayload = executeNetworkFetch(rawUrl, init, executionConfig)
                        ContextCompat.getMainExecutor(service).execute {
                            val sessionMismatch = requestSessionId != sessionId
                            val canceled =
                                taskTracker.cancelRequested(taskId) ||
                                    taskTracker.status(taskId) in setOf("canceling", "canceled")

                            if (canceled) {
                                taskTracker.update(taskId = taskId, status = "canceled")
                                if (!sessionMismatch) {
                                    host.dispatchBridgeError(
                                        replyTo = replyTo,
                                        kitId = functionKitId,
                                        surface = surface,
                                        code = "task_canceled",
                                        message = "network.fetch canceled.",
                                        retryable = false,
                                        details = JSONObject().put("taskId", taskId).put("url", rawUrl)
                                    )
                                }
                                return@execute
                            }

                            taskTracker.update(
                                taskId = taskId,
                                status = "succeeded",
                                result =
                                    JSONObject()
                                        .put(
                                            "summary",
                                            "HTTP ${responsePayload.optJSONObject("response")?.optInt("status")}"
                                        )
                            )
                            if (sessionMismatch) {
                                return@execute
                            }

                            host.dispatchNetworkFetchResult(
                                replyTo = replyTo,
                                kitId = functionKitId,
                                surface = surface,
                                payload = responsePayload.put("taskId", taskId)
                            )
                            host.dispatchHostStateUpdate(
                                kitId = functionKitId,
                                surface = surface,
                                label = "network.fetch 已完成",
                                details =
                                    buildHostDetails(executionConfig)
                                        .put("taskId", taskId)
                                        .put("url", rawUrl)
                                        .put("status", responsePayload.optJSONObject("response")?.optInt("status"))
                            )
                        }
                    } catch (error: RemoteInferenceException) {
                        ContextCompat.getMainExecutor(service).execute {
                            Log.e(
                                "FunctionKitWindow",
                                "network.fetch failed code=${error.code} url=$rawUrl message=${error.message} details=${error.details}",
                                error
                            )
                            taskTracker.update(
                                taskId = taskId,
                                status = "failed",
                                error =
                                    JSONObject()
                                        .put("code", error.code)
                                        .put("message", error.message)
                                        .put("retryable", error.retryable)
                                        .put("details", JSONObject.wrap(error.details))
                            )
                            if (requestSessionId != sessionId) {
                                return@execute
                            }

                            host.dispatchBridgeError(
                                replyTo = replyTo,
                                kitId = functionKitId,
                                surface = surface,
                                code = error.code,
                                message = error.message,
                                retryable = error.retryable,
                                details =
                                    JSONObject()
                                        .put("taskId", taskId)
                                        .put("url", rawUrl)
                                        .put("statusCode", error.statusCode)
                                        .put("details", JSONObject.wrap(error.details))
                            )
                        }
                    } catch (error: Throwable) {
                        ContextCompat.getMainExecutor(service).execute {
                            Log.e(
                                "FunctionKitWindow",
                                "network.fetch crashed url=$rawUrl: ${error.message ?: "unknown error"}",
                                error
                            )
                            taskTracker.update(
                                taskId = taskId,
                                status = "failed",
                                error =
                                    JSONObject()
                                        .put("code", "network_fetch_unexpected_error")
                                        .put("message", error.message ?: "Unexpected error")
                                        .put("retryable", false)
                                        .put("details", JSONObject().put("kind", error.javaClass.name))
                            )
                            if (requestSessionId != sessionId) {
                                return@execute
                            }
                            host.dispatchBridgeError(
                                replyTo = replyTo,
                                kitId = functionKitId,
                                surface = surface,
                                code = "network_fetch_unexpected_error",
                                message = "network.fetch failed: ${error.message ?: "Unexpected error"}",
                                retryable = false,
                                details =
                                    JSONObject()
                                        .put("taskId", taskId)
                                        .put("url", rawUrl)
                                        .put("kind", error.javaClass.name)
                            )
                        }
                    }
                }
            } catch (error: Throwable) {
                Log.e("FunctionKitWindow", "network.fetch scheduling failed url=$rawUrl", error)
                taskTracker.update(
                    taskId = taskId,
                    status = "failed",
                    error =
                        JSONObject()
                            .put("code", "network_fetch_schedule_failed")
                            .put("message", error.message ?: "Failed to schedule network.fetch")
                            .put("retryable", true)
                            .put("details", JSONObject().put("kind", error.javaClass.name))
                )
                host.dispatchBridgeError(
                    replyTo = replyTo,
                    kitId = functionKitId,
                    surface = surface,
                    code = "network_fetch_schedule_failed",
                    message = "network.fetch failed to schedule.",
                    retryable = true,
                    details =
                        JSONObject()
                            .put("taskId", taskId)
                            .put("url", rawUrl)
                            .put("kind", error.javaClass.name)
                )
                return
            }
        taskTracker.attachFuture(taskId, future)
    }

    private fun handleAiRequest(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "ai.request") ?: return

        val requestId = payload.optString("requestId").trim().ifBlank { "req-${UUID.randomUUID()}" }
        val routeKind =
            payload.optJSONObject("route")?.optString("kind")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "host-shared"

        if (routeKind != "host-shared") {
            host.dispatchBridgeError(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                code = "ai_route_not_supported",
                message = "AI route is not supported yet: $routeKind",
                retryable = false,
                details =
                    JSONObject()
                        .put("requestId", requestId)
                        .put("routeKind", routeKind)
            )
            return
        }

        val executionConfig = currentExecutionConfig()
        val aiChatConfig = currentAiChatConfig()
        val statusPayload = buildAiChatStatusPayload(executionConfig)
        if (statusPayload.optString("status") != "ready") {
            host.dispatchBridgeError(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                code = "ai_request_not_ready",
                message = "Android shared AI backend is not ready yet.",
                retryable = false,
                details =
                    JSONObject(statusPayload.toString())
                        .put("requestId", requestId)
                        .put("routeKind", routeKind)
            )
            return
        }

        val requestSessionId = sessionId
        val messages = buildAiChatMessages(payload)
        val temperature = payload.optDouble("temperature").takeIf { payload.has("temperature") }
        val maxOutputTokens = payload.optInt("maxTokens").takeIf { payload.has("maxTokens") }
        val taskTitle: String? =
            payload.optJSONObject("task")?.optString("title")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        val taskId =
            taskTracker.create(
                kind = "ai.request",
                surface = surface,
                title = taskTitle,
                status = "queued",
                cancellable = true,
                progress =
                    JSONObject()
                        .put("stage", "queued")
                        .put("requestId", requestId)
                        .put("routeKind", routeKind)
                        .put("model", aiChatConfig.model)
            )

        host.dispatchHostStateUpdate(
            kitId = functionKitId,
            surface = surface,
            label = "Android AI request 请求中",
            details =
                buildHostDetails(executionConfig)
                    .put("reason", "ai.request")
                    .put("taskId", taskId)
                    .put("requestId", requestId)
                    .put("routeKind", routeKind)
                    .put("model", aiChatConfig.model)
        )

        val future =
            remoteExecutor.submit {
                try {
                    taskTracker.update(
                        taskId = taskId,
                        status = "running",
                        progress =
                            JSONObject()
                                .put("stage", "request")
                                .put("requestId", requestId)
                                .put("routeKind", routeKind)
                                .put("model", aiChatConfig.model)
                    )
                    val completion =
                        requestLocalAiChatCompletion(
                            aiChatConfig = aiChatConfig,
                            messages = messages,
                            temperature = temperature,
                            maxOutputTokens = maxOutputTokens
                        )
                    ContextCompat.getMainExecutor(service).execute {
                        val sessionMismatch = requestSessionId != sessionId
                        val canceled =
                            taskTracker.cancelRequested(taskId) ||
                                taskTracker.status(taskId) in setOf("canceling", "canceled")

                        if (canceled) {
                            taskTracker.update(taskId = taskId, status = "canceled")
                            if (!sessionMismatch) {
                                host.dispatchBridgeError(
                                    replyTo = replyTo,
                                    kitId = functionKitId,
                                    surface = surface,
                                    code = "task_canceled",
                                    message = "ai.request canceled.",
                                    retryable = false,
                                    details =
                                        JSONObject()
                                            .put("taskId", taskId)
                                            .put("requestId", requestId)
                                )
                            }
                            return@execute
                        }

                        taskTracker.update(taskId = taskId, status = "succeeded")
                        if (sessionMismatch) {
                            return@execute
                        }

                        val structured = completion.structured?.let { JSONObject(it.toString()) }
                        val outputType = if (structured != null) "json" else "text"
                        val output =
                            JSONObject()
                                .put("type", outputType)
                                .put("text", completion.text)
                                .put("json", structured ?: JSONObject.NULL)

                        host.dispatchAiResponse(
                            replyTo = replyTo,
                            kitId = functionKitId,
                            surface = surface,
                            payload =
                                JSONObject()
                                    .put("requestId", requestId)
                                    .put("taskId", taskId)
                                    .put("status", "succeeded")
                                    .put("output", output)
                                    .put("usage", completion.usage?.let { JSONObject(it.toString()) } ?: JSONObject())
                        )
                        host.dispatchHostStateUpdate(
                            kitId = functionKitId,
                            surface = surface,
                            label = "Android AI request 已完成",
                            details =
                                buildHostDetails(executionConfig)
                                    .put("reason", "ai.request")
                                    .put("taskId", taskId)
                                    .put("requestId", requestId)
                                    .put("routeKind", routeKind)
                                    .put("model", aiChatConfig.model)
                        )
                    }
                } catch (error: RemoteInferenceException) {
                    ContextCompat.getMainExecutor(service).execute {
                        taskTracker.update(
                            taskId = taskId,
                            status = "failed",
                            error =
                                JSONObject()
                                    .put("code", error.code)
                                    .put("message", error.message)
                                    .put("retryable", error.retryable)
                                    .put("details", JSONObject.wrap(error.details))
                        )
                        if (requestSessionId != sessionId) {
                            return@execute
                        }

                        dispatchLocalAiChatError(
                            replyTo = replyTo,
                            surface = surface,
                            reason = "ai.request",
                            executionConfig = executionConfig,
                            aiChatConfig = aiChatConfig,
                            error = error,
                            taskId = taskId
                        )
                    }
                }
            }
        taskTracker.attachFuture(taskId, future)
    }

    private fun handleAiAgentList(
        replyTo: String,
        surface: String
    ) {
        ensurePermission(replyTo, "ai.agent.list") ?: return

        val executionConfig = currentExecutionConfig()
        host.dispatchAiAgentListResult(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = surface,
            payload = buildAgentListPayload(executionConfig)
        )
    }

    private fun handleAiAgentRun(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "ai.agent.run") ?: return

        val executionConfig = currentExecutionConfig()
        if (!executionConfig.remoteEnabled || executionConfig.renderEndpoint.isNullOrBlank()) {
            host.dispatchBridgeError(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                code = "ai_agent_not_ready",
                message = "Desktop-backed agent routing is not configured.",
                retryable = false,
                details = buildAgentListPayload(executionConfig)
            )
            return
        }

        host.dispatchBridgeError(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = surface,
            code = "ai_agent_run_not_implemented",
            message = "Agent execution still requires a registered desktop adapter endpoint.",
            retryable = false,
            details =
                JSONObject()
                    .put("request", JSONObject(payload.toString()))
        )
    }

    private fun handleRuntimeMessageSend(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "runtime.message.send") ?: return

        val toKitId = payload.optString("toKitId").trim()
        if (toKitId.isBlank()) {
            host.dispatchBridgeError(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                code = "invalid_payload",
                message = "Missing toKitId for runtime message.",
                retryable = false,
                details = JSONObject().put("payload", JSONObject(payload.toString()))
            )
            return
        }

        val channel = payload.optString("channel").trim().takeIf { it.isNotBlank() }
        val data = payload.takeIf { it.has("data") }?.opt("data")
        val sentAtEpochMs = System.currentTimeMillis()

        val installed = FunctionKitRegistry.listInstalled(context)
        val receiverManifest = installed.firstOrNull { it.id == toKitId }
        val ackPayload =
            JSONObject()
                .put("toKitId", toKitId)
                .put("channel", channel)
                .put("sentAtEpochMs", sentAtEpochMs)

        if (receiverManifest == null) {
            host.dispatchRuntimeMessageSendAck(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                payload =
                    ackPayload
                        .put("delivered", 0)
                        .put("reason", "kit_not_found")
            )
            return
        }

        if (!FunctionKitKitSettings.isKitEnabled(toKitId)) {
            host.dispatchRuntimeMessageSendAck(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                payload =
                    ackPayload
                        .put("delivered", 0)
                        .put("reason", "kit_disabled")
            )
            return
        }

        if (!receiverManifest.runtimePermissions.contains("runtime.message.receive")) {
            host.dispatchRuntimeMessageSendAck(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                payload =
                    ackPayload
                        .put("delivered", 0)
                        .put("reason", "receiver_permission_missing")
            )
            return
        }

        if (!FunctionKitPermissionPolicy.isEnabled("runtime.message.receive", functionKitPrefs, toKitId)) {
            host.dispatchRuntimeMessageSendAck(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                payload =
                    ackPayload
                        .put("delivered", 0)
                        .put("reason", "receiver_permission_denied")
            )
            return
        }

        val delivered =
            try {
                windowPool
                    .require(toKitId)
                    .receiveRuntimeMessage(
                        fromKitId = functionKitId,
                        fromSurface = surface,
                        channel = channel,
                        data = data,
                        sentAtEpochMs = sentAtEpochMs
                    )
            } catch (error: Throwable) {
                false
            }

        host.dispatchRuntimeMessageSendAck(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = surface,
            payload =
                ackPayload
                    .put("delivered", if (delivered) 1 else 0)
                    .apply {
                        if (!delivered) {
                            put("reason", "not_delivered")
                        }
                    }
        )
    }

    internal fun receiveRuntimeMessage(
        fromKitId: String,
        fromSurface: String?,
        channel: String?,
        data: Any?,
        sentAtEpochMs: Long
    ): Boolean {
        ensureManifestStateInitialized()
        val permission = "runtime.message.receive"
        val declared = permission in supportedRuntimePermissions
        if (!declared) {
            return false
        }
        if (!FunctionKitPermissionPolicy.isEnabled(permission, functionKitPrefs, functionKitId)) {
            return false
        }
        if (!panelInitialized) {
            ensureHeadlessPanelInitialized(reason = "runtime.message")
        }

        pendingRuntimeMessages.addLast(
            RuntimeMessageDelivery(
                fromKitId = fromKitId,
                fromSurface = fromSurface,
                channel = channel,
                data = data,
                sentAtEpochMs = sentAtEpochMs
            )
        )
        flushPendingRuntimeMessages()
        return true
    }

    private fun handleComposerOpen(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        updateComposerState(replyTo = replyTo, surface = surface, reason = "composer.open") { current ->
            current.copy(
                open = true,
                focused = payload.optBoolean("focused", true),
                text = resolveComposerText(payload, current.text),
                selectionStart =
                    payload.optInt("selectionStart").takeIf { payload.has("selectionStart") }
                        ?: current.selectionStart,
                selectionEnd =
                    payload.optInt("selectionEnd").takeIf { payload.has("selectionEnd") }
                        ?: current.selectionEnd,
                mode = "embedded",
                source = "kit",
                lastAction = "open"
            )
        }
    }

    private fun handleComposerFocus(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        updateComposerState(replyTo = replyTo, surface = surface, reason = "composer.focus") { current ->
            current.copy(
                open = true,
                focused = payload.optBoolean("focused", true),
                source = "kit",
                lastAction = "focus"
            )
        }
    }

    private fun handleComposerUpdate(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        updateComposerState(replyTo = replyTo, surface = surface, reason = "composer.update") { current ->
            current.copy(
                open = true,
                focused = payload.optBoolean("focused", current.focused),
                text = resolveComposerText(payload, current.text),
                selectionStart =
                    payload.optInt("selectionStart").takeIf { payload.has("selectionStart") }
                        ?: current.selectionStart,
                selectionEnd =
                    payload.optInt("selectionEnd").takeIf { payload.has("selectionEnd") }
                        ?: current.selectionEnd,
                source = "kit",
                lastAction = "update"
            )
        }
    }

    private fun handleComposerClose(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        updateComposerState(replyTo = replyTo, surface = surface, reason = "composer.close") { current ->
            current.copy(
                open = payload.optBoolean("open", false),
                focused = false,
                source = "kit",
                lastAction = "close"
            )
        }
    }

    private fun updateComposerState(
        replyTo: String?,
        surface: String,
        reason: String,
        transform: (ComposerState) -> ComposerState
    ) {
        composerState = normalizeComposerState(transform(composerState))
        syncEmbeddedKeyboardLayout()
        dispatchComposerStateSync(replyTo = replyTo, surface = surface, reason = reason)
    }

    private fun dispatchComposerStateSync(
        replyTo: String?,
        surface: String,
        reason: String
    ) {
        host.dispatchComposerStateSync(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = surface,
            payload = buildComposerStatePayload(reason)
        )
    }

    private fun buildComposerStatePayload(reason: String): JSONObject =
        JSONObject()
            .put("reason", reason)
            .put("composer", composerState.toJson())
            .put(
                "context",
                JSONObject()
                    .put("packageName", currentPackageName)
                    .put("selectionStart", currentSelectionStart)
                    .put("selectionEnd", currentSelectionEnd)
                    .put("selectedText", currentSelectedText())
            )
            .put("grantedPermissions", JSONArray(grantedPermissions))
            .put(
                "capabilities",
                JSONObject()
                    .put("canInsert", "input.insert" in grantedPermissions)
                    .put("canReplace", "input.replace" in grantedPermissions)
                    .put("targetAvailable", canApplyComposerToTarget())
            )

    private fun normalizeComposerState(state: ComposerState): ComposerState {
        val clampedStart = state.selectionStart.coerceIn(0, state.text.length)
        val clampedEnd = state.selectionEnd.coerceIn(0, state.text.length)
        return state.copy(
            selectionStart = minOf(clampedStart, clampedEnd),
            selectionEnd = maxOf(clampedStart, clampedEnd),
            revision = state.revision + 1,
            mode = "embedded",
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }

    private fun resolveComposerText(
        payload: JSONObject,
        fallback: String
    ): String =
        when {
            payload.has("text") -> payload.optString("text")
            payload.has("initialText") -> payload.optString("initialText")
            else -> fallback
        }

    private fun currentSelectedText(): String =
        FunctionKitInputSnapshotReader.capture(
            service = service,
            cursorContextChars = 64,
            selectionTextMaxChars = 8 * 1024
        ).selectedText

    private fun canApplyComposerToTarget(): Boolean = service.currentInputConnection != null

    private fun buildComposerDraftState(state: ComposerState = composerState): ComposerDraftBufferState =
        ComposerDraftBufferState(
            text = state.text,
            selectionStart = state.selectionStart,
            selectionEnd = state.selectionEnd
        )

    private fun mutateComposerDraft(
        reason: String,
        transform: (ComposerDraftBufferState) -> ComposerDraftBufferState
    ) {
        updateComposerState(replyTo = null, surface = Surface, reason = reason) { current ->
            val draft = transform(buildComposerDraftState(current))
            current.copy(
                open = true,
                focused = true,
                text = draft.text,
                selectionStart = draft.selectionStart,
                selectionEnd = draft.selectionEnd,
                source = "host",
                lastAction = reason
            )
        }
    }

    override fun isActive(): Boolean = composerState.open && composerState.focused

    override fun maybeInterceptImeActionSend(
        intent: FunctionKitImeSendIntent,
        onDecision: (Boolean) -> Unit
    ): Boolean {
        if (!sendInterceptImeActionRegistered) {
            return false
        }
        if (!windowAttached || !panelInitialized || !bridgeReady) {
            return false
        }
        refreshGrantedPermissions(notifyUi = false)
        if ("send.intercept.ime_action" !in grantedPermissions) {
            return false
        }
        if (pendingImeActionSendIntercept != null) {
            // The UI can only handle a single pending intent at a time. Fail open to avoid blocking
            // users from sending messages.
            return false
        }

        val contextSnapshot =
            buildContextSnapshot(
                preferredTone = "balanced",
                modifiers = emptyList(),
                maxChars = 64
            )
        val intentPayload =
            JSONObject()
                .put("kind", intent.kind)
                .put("actionId", intent.actionId)
                .put("actionLabel", intent.actionLabel)
        val requestPayload =
            JSONObject()
                .put("intent", intentPayload)
                .put("context", contextSnapshot)
                .put("bestEffort", true)

        val requestMessageId =
            host.dispatchSendInterceptImeActionIntent(
                kitId = functionKitId,
                surface = Surface,
                payload = requestPayload
            )
        if (requestMessageId.isBlank()) {
            return false
        }

        val timeoutMs = sendInterceptImeActionTimeoutMs.coerceIn(100, 5000)
        val timeoutRunnable =
            Runnable {
                val pending = pendingImeActionSendIntercept
                if (pending?.requestMessageId != requestMessageId) {
                    return@Runnable
                }

                pendingImeActionSendIntercept = null
                pushHostState("发送拦截超时，已放行")
                pending.onDecision(true)
            }

        pendingImeActionSendIntercept =
            PendingImeActionSendIntercept(
                requestMessageId = requestMessageId,
                createdAtEpochMs = System.currentTimeMillis(),
                timeoutRunnable = timeoutRunnable,
                onDecision = onDecision
            )

        webView.postDelayed(timeoutRunnable, timeoutMs.toLong())
        pushHostState("发送动作等待功能件确认")
        return true
    }

    override fun commitText(
        text: String,
        cursor: Int
    ): Boolean {
        if (!isActive()) {
            return false
        }
        mutateComposerDraft("composer.target.commitText") {
            FunctionKitComposerDraftBuffer.commitText(it, text, cursor)
        }
        return true
    }

    override fun deleteSurrounding(
        before: Int,
        after: Int
    ): Boolean {
        if (!isActive()) {
            return false
        }
        mutateComposerDraft("composer.target.deleteSurrounding") {
            FunctionKitComposerDraftBuffer.deleteSurrounding(it, before, after)
        }
        return true
    }

    override fun handleBackspace(): Boolean {
        if (!isActive()) {
            return false
        }
        mutateComposerDraft("composer.target.backspace") {
            FunctionKitComposerDraftBuffer.backspace(it)
        }
        return true
    }

    override fun handleEnter(): Boolean {
        if (!isActive()) {
            return false
        }
        mutateComposerDraft("composer.target.enter") {
            FunctionKitComposerDraftBuffer.commitText(it, "\n", 1)
        }
        return true
    }

    override fun handleArrow(keyCode: Int): Boolean {
        if (!isActive()) {
            return false
        }
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                mutateComposerDraft("composer.target.arrowLeft") { draft ->
                    if (draft.selectionStart != draft.selectionEnd) {
                        FunctionKitComposerDraftBuffer.setSelection(
                            draft,
                            draft.selectionStart,
                            draft.selectionStart
                        )
                    } else {
                        FunctionKitComposerDraftBuffer.applySelectionOffset(draft, -1, -1)
                    }
                }

            android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                mutateComposerDraft("composer.target.arrowRight") { draft ->
                    if (draft.selectionStart != draft.selectionEnd) {
                        FunctionKitComposerDraftBuffer.setSelection(
                            draft,
                            draft.selectionEnd,
                            draft.selectionEnd
                        )
                    } else {
                        FunctionKitComposerDraftBuffer.applySelectionOffset(draft, 1, 1)
                    }
                }

            else -> return false
        }
        return true
    }

    override fun deleteSelection(): Boolean {
        if (!isActive()) {
            return false
        }
        val draft = buildComposerDraftState()
        if (draft.selectionStart == draft.selectionEnd) {
            return false
        }
        mutateComposerDraft("composer.target.deleteSelection") {
            FunctionKitComposerDraftBuffer.commitText(it, "", 0)
        }
        return true
    }

    override fun applySelectionOffset(
        offsetStart: Int,
        offsetEnd: Int
    ): Boolean {
        if (!isActive()) {
            return false
        }
        mutateComposerDraft("composer.target.selectionOffset") {
            FunctionKitComposerDraftBuffer.applySelectionOffset(it, offsetStart, offsetEnd)
        }
        return true
    }

    override fun cancelSelection(): Boolean {
        if (!isActive()) {
            return false
        }
        mutateComposerDraft("composer.target.cancelSelection") {
            FunctionKitComposerDraftBuffer.cancelSelection(it)
        }
        return true
    }

    private fun currentAiChatConfig(): HostAiChatConfig =
        FunctionKitAiChatBackend.fromPrefs(aiPrefs)

    private fun canUseLocalAiForCandidates(aiChatConfig: HostAiChatConfig): Boolean =
        aiChatConfig.isConfigured &&
            "ai.request" in grantedPermissions

    private fun buildAiChatMessages(payload: JSONObject): JSONArray {
        val messages = JSONArray()
        var hasConversationMessages = false
        val systemPrompt = payload.optString("systemPrompt").trim()
        if (systemPrompt.isNotBlank()) {
            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", systemPrompt)
            )
        }

        val explicitMessages = payload.optJSONArray("messages")
        if (explicitMessages != null && explicitMessages.length() > 0) {
            for (index in 0 until explicitMessages.length()) {
                val rawMessage = explicitMessages.optJSONObject(index) ?: continue
                val role = rawMessage.optString("role").ifBlank { "user" }
                val content =
                    rawMessage.opt("content")
                        ?.takeIf { it != JSONObject.NULL }
                        ?.toString()
                        .orEmpty()
                        .trim()
                if (content.isBlank()) {
                    continue
                }
                messages.put(
                    JSONObject()
                        .put("role", role)
                        .put("content", content)
                )
                hasConversationMessages = true
            }
        }

        if (hasConversationMessages) {
            return messages
        }

        val prompt = payload.optString("prompt").trim()
        val format = payload.optString("format").trim()
        val responseType = payload.optJSONObject("response")?.optString("type")?.trim().orEmpty()
        val wantsJson =
            format.equals("json", ignoreCase = true) ||
                responseType.equals("json", ignoreCase = true) ||
                responseType.equals("json-schema", ignoreCase = true)
        val inputPayload = payload.opt("input")
        val userContent =
            buildString {
                append(prompt.ifBlank { "Continue from the provided input and answer helpfully." })
                if (wantsJson) {
                    append("\n\nReturn strict JSON only.")
                }
                if (inputPayload != null && inputPayload != JSONObject.NULL) {
                    append("\n\nInput:\n")
                    append(inputPayload.toString().take(currentAiChatConfig().maxContextChars))
                }
            }.trim()

        return JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("content", userContent)
        )
    }

    private fun buildLocalAiCandidateMessages(
        requestContext: JSONObject,
        preferredTone: String,
        modifiers: List<String>,
        aiChatConfig: HostAiChatConfig
    ): JSONArray {
        val modifierText = modifiers.joinToString("；").ifBlank { "无" }
        val contextText = requestContext.toString().take(aiChatConfig.maxContextChars)
        return JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put(
                        "content",
                        """
                        你是 Android 输入法里的聊天自动回复引擎。
                        只返回一个 JSON 对象，不要输出 Markdown，不要解释，不要代码围栏。
                        JSON 结构必须是：
                        {"candidates":[{"text":"...","tone":"...","risk":"low|medium|high","rationale":"..."}]}
                        要求：
                        - 生成 ${RemoteCandidateCount} 条中文回复候选
                        - 每条候选尽量不超过 ${RemoteMaxCharsPerCandidate} 个字符
                        - 回复应当自然、可直接发送、避免空泛
                        - `tone` 写简短标签，`risk` 只能是 low / medium / high
                        - 如果上下文不足，也要给出稳妥候选
                        """.trimIndent()
                    )
            )
            .put(
                JSONObject()
                    .put("role", "user")
                    .put(
                        "content",
                        """
                        当前偏好语气：$preferredTone
                        附加要求：$modifierText
                        输入上下文：
                        $contextText
                        """.trimIndent()
                    )
            )
    }

    private fun requestLocalAiChatCompletion(
        aiChatConfig: HostAiChatConfig,
        messages: JSONArray,
        temperature: Double?,
        maxOutputTokens: Int?
    ): HostAiChatCompletion {
        val endpoint =
            aiChatConfig.endpoint
                ?: throw RemoteInferenceException(
                    code = "ai_chat_not_configured",
                    message = "Android AI chat base URL is blank.",
                    retryable = false
                )
        val connection =
            try {
                val url = URL(endpoint)
                require(url.protocol == "http" || url.protocol == "https") {
                    "Unsupported URL scheme: ${url.protocol}"
                }
                url.openConnection() as HttpURLConnection
            } catch (error: Exception) {
                throw RemoteInferenceException(
                    code = "ai_chat_invalid_base_url",
                    message = "Android AI chat base URL is invalid.",
                    retryable = false,
                    details = error.message
                )
            }

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = aiChatConfig.timeoutSeconds * 1000
            connection.readTimeout = aiChatConfig.timeoutSeconds * 1000
            connection.doInput = true
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            if (aiChatConfig.apiKeyConfigured) {
                connection.setRequestProperty("Authorization", "Bearer ${aiChatConfig.apiKey}")
            }

            val requestBody =
                FunctionKitAiChatBackend.buildChatCompletionRequest(
                    config = aiChatConfig,
                    messages = messages,
                    temperature = temperature,
                    maxOutputTokens = maxOutputTokens
                )
            connection.outputStream.use { output ->
                output.write(requestBody.toString().toByteArray(StandardCharsets.UTF_8))
            }

            val statusCode = connection.responseCode
            val responseBody = readResponseBody(connection, successful = statusCode in 200..299)
            if (statusCode !in 200..299) {
                throw buildLocalAiHttpException(statusCode, responseBody.body)
            }

            val response =
                try {
                    JSONObject(responseBody.body)
                } catch (error: Exception) {
                    throw RemoteInferenceException(
                        code = "ai_chat_invalid_json",
                        message = "Android AI chat returned invalid JSON: ${error.message}",
                        retryable = false,
                        details = responseBody.body
                    )
                }
            val text = FunctionKitAiChatBackend.extractAssistantText(response)
            if (text.isBlank()) {
                throw RemoteInferenceException(
                    code = "ai_chat_empty_response",
                    message = "Android AI chat returned an empty completion.",
                    retryable = false,
                    details = response.toString()
                )
            }

            return HostAiChatCompletion(
                text = text,
                structured = FunctionKitAiChatBackend.extractStructuredJson(text),
                usage = response.optJSONObject("usage")?.let { JSONObject(it.toString()) }
            )
        } catch (error: SocketTimeoutException) {
            throw RemoteInferenceException(
                code = "ai_chat_timeout",
                message = "Android AI chat timed out after ${aiChatConfig.timeoutSeconds}s.",
                retryable = true,
                details = endpoint
            )
        } catch (error: IOException) {
            throw RemoteInferenceException(
                code = "ai_chat_io_error",
                message = "Android AI chat request failed: ${error.message ?: "I/O error"}",
                retryable = true,
                details = endpoint
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun buildLocalAiHttpException(
        statusCode: Int,
        body: String
    ): RemoteInferenceException {
        val errorObject =
            try {
                JSONObject(body).optJSONObject("error")
            } catch (_: Exception) {
                null
            }
        val message =
            errorObject?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: "Android AI chat request failed with HTTP $statusCode."
        val code =
            errorObject?.optString("code")
                ?.takeIf { it.isNotBlank() }
                ?: when (statusCode) {
                    401, 403 -> "ai_chat_auth_failed"
                    408, 504 -> "ai_chat_timeout"
                    else -> "ai_chat_http_error"
                }
        return RemoteInferenceException(
            code = code,
            message = message,
            retryable = statusCode >= 500 || statusCode == 408 || statusCode == 429,
            statusCode = statusCode,
            details = if (body.isBlank()) null else body
        )
    }

    private fun buildLocalAiCandidatesRenderPayload(
        requestContext: JSONObject,
        completion: HostAiChatCompletion,
        executionConfig: ExecutionConfig,
        aiChatConfig: HostAiChatConfig,
        reason: String
    ): JSONObject =
        JSONObject()
            .put("requestContext", JSONObject(requestContext.toString()))
            .put(
                "result",
                JSONObject()
                    .put(
                        "candidates",
                        FunctionKitAiChatBackend.normalizeCandidates(
                            rawText = completion.text,
                            maxCandidates = RemoteCandidateCount,
                            maxCharsPerCandidate = RemoteMaxCharsPerCandidate
                        )
                    )
                    .put("missing_context", JSONArray())
            )
            .put("uiHints", JSONObject().put("allowRegenerate", true))
            .put("manifest", buildManifestSnapshot())
            .put("slash", buildSlashSnapshot())
            .put(
                "meta",
                JSONObject()
                    .put("backendClass", "direct-model")
                    .put("providerType", aiChatConfig.providerType)
                    .put("usage", completion.usage ?: JSONObject())
            )

    private fun dispatchLocalAiChatError(
        replyTo: String?,
        surface: String,
        reason: String,
        executionConfig: ExecutionConfig,
        aiChatConfig: HostAiChatConfig,
        error: RemoteInferenceException,
        taskId: String? = null,
        fallbackCandidatesPayload: JSONObject? = null
    ) {
        host.dispatchHostStateUpdate(
            kitId = functionKitId,
            surface = surface,
            label = "Android AI chat 失败",
            details =
                buildHostDetails(executionConfig)
                    .put("reason", reason)
                    .apply { taskId?.let { put("taskId", it) } }
                    .put("error", JSONObject().put("code", error.code).put("message", error.message))
        )
        fallbackCandidatesPayload?.let { payload ->
            host.dispatchCandidatesRender(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                payload = payload
            )
        }
        host.dispatchBridgeError(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = surface,
            code = error.code,
            message = error.message,
            retryable = error.retryable,
            details =
                JSONObject()
                    .apply { taskId?.let { put("taskId", it) } }
                    .put("reason", reason)
                    .put("statusCode", error.statusCode)
                    .put("details", JSONObject.wrap(error.details))
        )
    }

    private fun buildAiChatStatusPayload(_executionConfig: ExecutionConfig): JSONObject {
        val permissionGranted = "ai.request" in grantedPermissions
        if (!permissionGranted) {
            return JSONObject()
                .put("available", false)
                .put("status", "permission_denied")
                .put("reason", "permission_denied")
                .put("permissionGranted", false)
        }

        val aiChatConfig = currentAiChatConfig()
        val status =
            when {
                !aiChatConfig.enabled -> "not_configured"
                !aiChatConfig.isConfigured -> "not_configured"
                else -> "ready"
            }
        val reason =
            when {
                !aiChatConfig.enabled -> "disabled_by_user"
                !aiChatConfig.isConfigured -> "not_configured"
                else -> "ready"
            }

        return JSONObject()
            .put("available", status == "ready")
            .put("status", status)
            .put("reason", reason)
            .put("permissionGranted", true)
    }

    private fun buildAgentListPayload(executionConfig: ExecutionConfig): JSONObject {
        val agents = JSONArray()
        if (executionConfig.remoteEnabled && !executionConfig.renderEndpoint.isNullOrBlank()) {
            agents.put(
                JSONObject()
                    .put("id", executionConfig.preferredAdapter ?: "desktop-agent")
                    .put("name", executionConfig.preferredAdapter ?: "Configured Desktop Agent")
                    .put("backendClass", executionConfig.preferredBackendClass ?: "external-agent-adapter")
                    .put("transport", executionConfig.transport)
                    .put("availability", "available")
                    .put("riskLevel", "high")
                    .put("requiresConfirmation", true)
                    .put("intents", JSONArray(listOf("desktop.files", "desktop.messages", "workspace.automation")))
                    .put("skills", JSONArray())
                    .put("hostBound", true)
            )
        }
        return JSONObject()
            .put("agents", agents)
    }

    private fun executeKitInstall(source: JSONObject): FunctionKitPackageManager.InstallOutcome {
        val kind = source.optString("kind").trim().lowercase()
        return when (kind) {
            "file" -> {
                val fileId = source.optString("fileId").trim()
                if (fileId.isBlank()) {
                    throw RemoteInferenceException(
                        code = "kits_install_invalid_source",
                        message = "kits.install source.kind=file requires source.fileId.",
                        retryable = false
                    )
                }

                val entry =
                    fileStore.get(fileId)
                        ?: throw RemoteInferenceException(
                            code = "kits_install_file_not_found",
                            message = "kits.install fileId not found: $fileId",
                            retryable = false
                        )

                FunctionKitPackageManager.installFromZipUri(context, entry.uri)
            }
            "url" -> {
                val url = source.optString("url").trim()
                if (url.isBlank()) {
                    throw RemoteInferenceException(
                        code = "kits_install_invalid_source",
                        message = "kits.install source.kind=url requires source.url.",
                        retryable = false
                    )
                }

                val maxBytes =
                    source.optLong("maxBytes")
                        .takeIf { it > 0 }
                        ?: MaxStoreZipBytes

                val download = downloadToTempZip(urlString = url, maxBytes = maxBytes)
                val tempZip = download.file
                    ?: throw RemoteInferenceException(
                        code = "kits_install_download_failed",
                        message = "Failed to download kit zip: ${download.errorMessage ?: url}",
                        retryable = true,
                        details = JSONObject().put("url", url)
                    )

                try {
                    val installKey =
                        source.optString("installKey").trim().ifBlank {
                            "url:${sha256HexString(url)}"
                        }

                    val expectedSha = source.optString("sha256").trim()
                    if (expectedSha.isNotBlank()) {
                        val actual = sha256Hex(tempZip)
                        if (!equalsSha256(expectedSha, actual)) {
                            throw RemoteInferenceException(
                                code = "kits_install_sha256_mismatch",
                                message = "Kit zip SHA-256 mismatch.",
                                retryable = false,
                                details =
                                    JSONObject()
                                        .put("expectedSha256", expectedSha)
                                        .put("actualSha256", actual)
                            )
                        }
                    }

                    FunctionKitPackageManager.installFromZipFile(context, tempZip, installKey = installKey)
                } finally {
                    runCatching { tempZip.delete() }
                }
            }
            "catalog" -> {
                val catalogUrl = source.optString("catalogUrl").trim()
                val kitId = source.optString("kitId").trim()
                if (catalogUrl.isBlank() || kitId.isBlank()) {
                    throw RemoteInferenceException(
                        code = "kits_install_invalid_source",
                        message = "kits.install source.kind=catalog requires source.catalogUrl and source.kitId.",
                        retryable = false
                    )
                }

                val resolvedZipUrl =
                    resolveZipUrl(
                        catalogUrl = catalogUrl,
                        kitId = kitId,
                        zipUrl = source.optString("zipUrl").trim().takeIf { it.isNotBlank() }
                    ) ?: throw RemoteInferenceException(
                        code = "kits_install_invalid_source",
                        message = "kits.install failed to resolve catalog zipUrl.",
                        retryable = false,
                        details = JSONObject().put("catalogUrl", catalogUrl).put("kitId", kitId)
                    )

                val urlSource =
                    JSONObject()
                        .put("kind", "url")
                        .put("url", resolvedZipUrl)
                        .put("installKey", "catalog:${sha256HexString(catalogUrl)}:$kitId")
                        .apply {
                            source.optString("sha256").trim().takeIf { it.isNotBlank() }?.let { put("sha256", it) }
                            source.optLong("maxBytes").takeIf { it > 0 }?.let { put("maxBytes", it) }
                        }

                executeKitInstall(urlSource)
            }
            else ->
                throw RemoteInferenceException(
                    code = "kits_install_invalid_source",
                    message = "Unsupported kits.install source kind: $kind",
                    retryable = false,
                    details = JSONObject().put("kind", kind)
                )
        }
    }

    private fun executeCatalogRefresh(sources: List<FunctionKitCatalogStore.Source>): List<JSONObject> {
        val packages = mutableListOf<JSONObject>()
        val seen = LinkedHashSet<String>()

        sources.forEach { source ->
            fetchCatalogPackages(source.url).forEach { pkg ->
                val kitId = pkg.optString("kitId").trim()
                if (kitId.isBlank() || kitId in seen) {
                    return@forEach
                }
                seen.add(kitId)
                packages += pkg
            }
        }

        return packages
    }

    private fun fetchCatalogPackages(catalogUrl: String): List<JSONObject> {
        val connection =
            try {
                val url = URL(catalogUrl)
                require(url.protocol == "http" || url.protocol == "https") {
                    "Unsupported URL scheme: ${url.protocol}"
                }
                url.openConnection() as HttpURLConnection
            } catch (error: Exception) {
                throw RemoteInferenceException(
                    code = "catalog_invalid_url",
                    message = "catalog.refresh only supports absolute http/https URLs.",
                    retryable = false,
                    details = error.message ?: catalogUrl
                )
            }

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.connect()

            val status = connection.responseCode
            val body =
                if (status in 200..299) {
                    connection.inputStream.use { readUtf8Limited(it, MaxCatalogIndexBytes) }
                } else {
                    val errorBody =
                        runCatching {
                            connection.errorStream?.use { readUtf8Limited(it, MaxCatalogIndexBytes) }
                        }.getOrNull()
                    throw RemoteInferenceException(
                        code = "catalog_http_error",
                        message = "HTTP $status",
                        retryable = true,
                        statusCode = status,
                        details =
                            JSONObject()
                                .put("url", catalogUrl)
                                .put("body", errorBody)
                    )
                }

            val root =
                runCatching { JSONObject(body) }
                    .getOrElse { error ->
                        throw RemoteInferenceException(
                            code = "catalog_invalid_json",
                            message = "Invalid catalog JSON: ${error.message ?: "parse error"}",
                            retryable = false,
                            details = JSONObject().put("url", catalogUrl)
                        )
                    }

            val packagesNode = root.optJSONArray("packages")
                ?: throw RemoteInferenceException(
                    code = "catalog_missing_packages",
                    message = "Missing packages[]",
                    retryable = false,
                    details = JSONObject().put("url", catalogUrl)
                )

            val packages = mutableListOf<JSONObject>()
            for (index in 0 until packagesNode.length()) {
                val item = packagesNode.optJSONObject(index) ?: continue
                val kitId = item.optString("kitId").trim()
                if (kitId.isBlank()) {
                    continue
                }
                val normalized =
                    JSONObject(item.toString())
                        .put("kitId", kitId)
                        .put("catalogUrl", catalogUrl)

                val zipUrl = item.optString("zipUrl").trim().takeIf { it.isNotBlank() }
                resolveZipUrl(catalogUrl = catalogUrl, kitId = kitId, zipUrl = zipUrl)?.let { resolved ->
                    normalized.put("resolvedZipUrl", resolved)
                }

                packages += normalized
            }

            return packages
        } catch (error: RemoteInferenceException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw RemoteInferenceException(
                code = "catalog_timeout",
                message = "catalog.refresh timed out.",
                retryable = true,
                details = JSONObject().put("url", catalogUrl)
            )
        } catch (error: IOException) {
            throw RemoteInferenceException(
                code = "catalog_io_error",
                message = "catalog.refresh failed: ${error.message ?: "I/O error"}",
                retryable = true,
                details = JSONObject().put("url", catalogUrl)
            )
        } catch (error: Throwable) {
            throw RemoteInferenceException(
                code = "catalog_unexpected_error",
                message = "catalog.refresh crashed: ${error.message ?: "Unexpected error"}",
                retryable = false,
                details = JSONObject().put("url", catalogUrl).put("kind", error.javaClass.name)
            )
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun readUtf8Limited(
        input: java.io.InputStream,
        maxBytes: Long
    ): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) {
                break
            }
            total += read
            if (total > maxBytes) {
                throw RemoteInferenceException(
                    code = "payload_too_large",
                    message = "Payload is too large (${total} bytes).",
                    retryable = false,
                    details = JSONObject().put("maxBytes", maxBytes)
                )
            }
            output.write(buffer, 0, read)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun resolveZipUrl(
        catalogUrl: String,
        kitId: String,
        zipUrl: String?
    ): String? =
        runCatching {
            val base = URL(catalogUrl)
            val raw = zipUrl?.trim().orEmpty()
            val resolved =
                if (raw.isNotBlank()) {
                    URL(base, raw)
                } else {
                    URL(base, "./${kitId}.zip")
                }
            resolved.toString()
        }.getOrNull()

    private data class DownloadZipOutcome(
        val file: File?,
        val errorMessage: String?
    )

    private fun downloadToTempZip(
        urlString: String,
        maxBytes: Long
    ): DownloadZipOutcome {
        val ctx = FunctionKitPackageManager.storageContext(context)
        val tempDir = File(ctx.cacheDir, "function-kits-download").apply { mkdirs() }
        val target = File(tempDir, "kit-download-${UUID.randomUUID()}.zip")
        var success = false

        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/zip,application/octet-stream,*/*")

            connection.connect()
            val status = connection.responseCode
            if (status < 200 || status >= 300) {
                return DownloadZipOutcome(null, "HTTP $status")
            }

            val contentLength = connection.contentLengthLong.takeIf { it > 0 }
            if (contentLength != null && contentLength > maxBytes) {
                return DownloadZipOutcome(null, "Too large (${contentLength} bytes)")
            }

            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    var total = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                        total += read
                        if (total > maxBytes) {
                            return DownloadZipOutcome(null, "Too large (>${maxBytes} bytes)")
                        }
                    }
                }
            }

            success = true
            return DownloadZipOutcome(target, null)
        } catch (error: Throwable) {
            return DownloadZipOutcome(null, error.message ?: error::class.java.simpleName)
        } finally {
            if (!success) {
                runCatching { target.delete() }
            }
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        val bytes = digest.digest()
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                append(byte.toInt().and(0xff).toString(16).padStart(2, '0'))
            }
        }
    }

    private fun sha256HexString(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.trim().toByteArray(StandardCharsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                append(byte.toInt().and(0xff).toString(16).padStart(2, '0'))
            }
        }
    }

    private fun equalsSha256(expected: String, actual: String): Boolean {
        val trimmedExpected = expected.trim()
        val trimmedActual = actual.trim()
        if (trimmedExpected.isBlank() || trimmedActual.isBlank()) {
            return false
        }
        return trimmedExpected.equals(trimmedActual, ignoreCase = true)
    }

    private fun executeNetworkFetch(
        rawUrl: String,
        init: JSONObject,
        executionConfig: ExecutionConfig
    ): JSONObject {
        val connection =
            try {
                val url = URL(rawUrl)
                require(url.protocol == "http" || url.protocol == "https") {
                    "Unsupported URL scheme: ${url.protocol}"
                }
                url.openConnection() as HttpURLConnection
            } catch (error: Exception) {
                throw RemoteInferenceException(
                    code = "network_fetch_invalid_url",
                    message = "network.fetch only supports absolute http/https URLs.",
                    retryable = false,
                    details = error.message ?: rawUrl
                )
            }

        val requestMethod = init.optString("method").ifBlank { "GET" }.uppercase()
        val timeoutMs = init.optInt("timeoutMs").takeIf { it > 0 } ?: executionConfig.timeoutMs
        val body = init.opt("body")?.takeIf { it != JSONObject.NULL }?.toString()
        val bodyRef = init.optJSONObject("bodyRef")
        if (!body.isNullOrEmpty() && bodyRef != null) {
            throw RemoteInferenceException(
                code = "network_fetch_invalid_body",
                message = "network.fetch accepts either init.body or init.bodyRef, not both.",
                retryable = false,
                details = rawUrl
            )
        }

        try {
            connection.requestMethod = requestMethod
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.doInput = true
            connection.instanceFollowRedirects = init.optBoolean("followRedirects", true)
            connection.useCaches = false
            parseHeaderPairs(init.opt("headers")).forEach { (name, value) ->
                connection.setRequestProperty(name, value)
            }
            val requestPayload =
                JSONObject()
                    .put("url", rawUrl)
                    .put("method", requestMethod)
            if (!body.isNullOrEmpty()) {
                connection.doOutput = true
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(StandardCharsets.UTF_8))
                }
            } else if (bodyRef != null) {
                val refType = bodyRef.optString("type").trim().lowercase()
                if (refType != "file") {
                    throw RemoteInferenceException(
                        code = "network_fetch_invalid_body_ref",
                        message = "network.fetch only supports init.bodyRef.type=file.",
                        retryable = false,
                        details = JSONObject().put("type", refType)
                    )
                }

                val fileId = bodyRef.optString("fileId").trim()
                if (fileId.isBlank()) {
                    throw RemoteInferenceException(
                        code = "network_fetch_invalid_body_ref",
                        message = "network.fetch init.bodyRef.fileId must be a non-empty string.",
                        retryable = false
                    )
                }

                val entry =
                    fileStore.get(fileId)
                        ?: throw RemoteInferenceException(
                            code = "network_fetch_file_not_found",
                            message = "network.fetch init.bodyRef.fileId not found.",
                            retryable = false,
                            details = JSONObject().put("fileId", fileId)
                        )
                requestPayload.put(
                    "bodyRef",
                    JSONObject()
                        .put("type", "file")
                        .put("fileId", entry.fileId)
                        .put("name", entry.name)
                        .put("mimeType", entry.mimeType)
                        .put("sizeBytes", entry.sizeBytes)
                )

                if (connection.getRequestProperty("Content-Type").isNullOrBlank()) {
                    connection.setRequestProperty("Content-Type", entry.mimeType)
                }

                val maxUploadBytes = 25 * 1024 * 1024L
                entry.sizeBytes?.let { sizeBytes ->
                    if (sizeBytes > maxUploadBytes) {
                        throw RemoteInferenceException(
                            code = "network_fetch_body_too_large",
                            message = "network.fetch bodyRef is too large (${sizeBytes} bytes).",
                            retryable = false,
                            details =
                                JSONObject()
                                    .put("fileId", fileId)
                                    .put("sizeBytes", sizeBytes)
                                    .put("maxBytes", maxUploadBytes)
                        )
                    }
                    connection.setFixedLengthStreamingMode(sizeBytes)
                } ?: connection.setChunkedStreamingMode(0)

                val input =
                    fileStore.openInputStream(fileId)
                        ?: throw RemoteInferenceException(
                            code = "network_fetch_file_io_error",
                            message = "network.fetch failed to open bodyRef stream.",
                            retryable = true,
                            details = JSONObject().put("fileId", fileId)
                        )

                connection.doOutput = true
                input.use { inputStream ->
                    connection.outputStream.use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }

            val statusCode = connection.responseCode
            val responseBody = readResponseBody(connection, successful = statusCode in 200..299)
            return JSONObject()
                .put(
                    "request",
                    requestPayload
                )
                .put(
                    "response",
                    JSONObject()
                        .put("ok", statusCode in 200..299)
                        .put("status", statusCode)
                        .put("statusText", connection.responseMessage.orEmpty())
                        .put("url", connection.url?.toString())
                        .put("redirected", false)
                        .put("headers", buildResponseHeaders(connection))
                        .put("body", responseBody.body)
                        .put("bodyBytes", responseBody.bytes)
                        .put("bodyTruncated", responseBody.truncated)
                )
        } catch (error: SocketTimeoutException) {
            throw RemoteInferenceException(
                code = "network_fetch_timeout",
                message = "network.fetch timed out after ${timeoutMs} ms.",
                retryable = true,
                details = rawUrl
            )
        } catch (error: IOException) {
            throw RemoteInferenceException(
                code = "network_fetch_io_error",
                message = "network.fetch failed: ${error.message ?: "I/O error"}",
                retryable = true,
                details = rawUrl
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun parseHeaderPairs(value: Any?): List<Pair<String, String>> {
        if (value == null || value == JSONObject.NULL) {
            return emptyList()
        }

        if (value is JSONArray) {
            return buildList {
                for (index in 0 until value.length()) {
                    val entry = value.optJSONArray(index) ?: continue
                    val name = entry.optString(0).trim()
                    if (name.isBlank()) continue
                    add(name to entry.opt(1)?.toString().orEmpty())
                }
            }
        }

        if (value is JSONObject) {
            return buildList {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    add(key to value.opt(key)?.toString().orEmpty())
                }
            }
        }

        return emptyList()
    }

    private fun buildResponseHeaders(connection: HttpURLConnection): JSONObject {
        val headers = JSONObject()
        connection.headerFields.forEach { (name, values) ->
            if (name.isNullOrBlank() || values.isNullOrEmpty()) {
                return@forEach
            }
            headers.put(name, values.joinToString(", "))
        }
        return headers
    }

    private fun ensurePermission(replyTo: String?, permission: String): String? {
        refreshGrantedPermissions(notifyUi = panelInitialized)
        if (permission in grantedPermissions) {
            return permission
        }

        host.dispatchPermissionDenied(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = Surface,
            permission = permission
        )
        return null
    }

    private fun buildHostStateDetailsLite(): JSONObject =
        JSONObject()
            .put("sessionId", sessionId)
            .put("kitId", functionKitId)
            .put("packageName", currentPackageName)
            .put("selectionStart", currentSelectionStart)
            .put("selectionEnd", currentSelectionEnd)
            .put("inputType", currentInputType)
            .put("candidateCount", currentCandidateCount)
            .put("grantedPermissions", JSONArray(grantedPermissions))
            .put("build", buildDebugInfo())

    private fun pushHostState(label: String) {
        if (!panelInitialized) {
            return
        }

        host.dispatchHostStateUpdate(
            kitId = functionKitId,
            surface = Surface,
            label = label,
            details = buildHostStateDetailsLite()
        )
    }

    private fun pushHostStateThrottled(
        label: String,
        throttleMs: Int
    ) {
        if (!panelInitialized) {
            return
        }

        val now = System.currentTimeMillis()
        if (label == hostStateThrottledLastLabel &&
            now - hostStateThrottledLastSentAtEpochMs < throttleMs
        ) {
            return
        }

        hostStateThrottledLastLabel = label
        hostStateThrottledLastSentAtEpochMs = now
        pushHostState(label)
    }

    private fun syncCurrentInputState() {
        currentPackageName = service.currentInputEditorInfo.packageName.orEmpty()
        currentInputType = service.currentInputEditorInfo.inputType
        service.currentInputSelection.let {
            currentSelectionStart = it.start
            currentSelectionEnd = it.end
        }
        rememberCurrentInputSnapshot()
    }

    private fun rememberCurrentInputSnapshot() {
        FunctionKitInputSnapshotReader.capture(
            service = service,
            cursorContextChars = 256,
            selectionTextMaxChars = 8 * 1024
        )
    }

    private fun buildReloadEntryPath(hash: String): String {
        val baseEntryPath = functionKitManifest.entryHtmlAssetPath.substringBefore("#")
        val normalizedHash =
            hash.trim().let { value ->
                when {
                    value.isBlank() -> ""
                    value.startsWith("#") -> value
                    else -> "#$value"
                }
            }
        val separator = if ('?' in baseEntryPath) "&" else "?"
        return buildString {
            append(baseEntryPath)
            append(separator)
            append("fk_reload_session=")
            append(Uri.encode(sessionId))
            append(normalizedHash)
        }
    }

    private fun reloadPanel() {
        refreshGrantedPermissions()
        renderSeed = 0
        sessionId = newSessionId()
        bridgeReady = false
        val entryPath = buildReloadEntryPath(pendingEntryHash.orEmpty())
        pendingEntryHash = null
        host.initialize(entryPath)
    }

    private fun buildContextPayload(
        contextSnapshot: JSONObject,
        preferredTone: String,
        modifiers: List<String>,
        reason: String
    ): JSONObject {
        return JSONObject()
            .put("context", contextSnapshot)
            .put(
                "request",
                JSONObject()
                    .put("reason", reason.ifBlank { "ui-request" })
                    .put("preferredTone", preferredTone)
                    .put("modifiers", JSONArray(modifiers))
            )
            .put("manifest", buildManifestSnapshot())
            .put("slash", buildSlashSnapshot())
    }

    private fun buildCandidatesRenderPayload(
        requestContext: JSONObject,
        preferredTone: String,
        modifiers: List<String>
    ): JSONObject {
        return JSONObject()
            .put("requestContext", requestContext)
            .put(
                "result",
                JSONObject()
                    .put("candidates", buildCandidates(requestContext, preferredTone, modifiers))
                    .put("missing_context", JSONArray())
            )
            .put("uiHints", JSONObject().put("allowRegenerate", true))
            .put("manifest", buildManifestSnapshot())
            .put("slash", buildSlashSnapshot())
    }

    private fun currentExecutionConfig(): ExecutionConfig {
        val configuredBaseUrl = functionKitPrefs.remoteBaseUrl.getValue().trim()
        val backendHints = functionKitManifest.ai.backendHints
        return ExecutionConfig(
            remoteEnabled =
                functionKitPrefs.remoteInferenceEnabled.getValue() &&
                    functionKitManifest.ai.executionMode != "local-demo",
            configuredBaseUrl = configuredBaseUrl,
            normalizedBaseUrl = configuredBaseUrl.trimEnd('/'),
            remoteAuthToken = functionKitPrefs.remoteAuthToken.getValue().trim(),
            timeoutSeconds = functionKitPrefs.remoteTimeoutSeconds.getValue(),
            requestedExecutionMode = functionKitManifest.ai.executionMode,
            preferredBackendClass = backendHints.preferredBackendClass,
            preferredAdapter = backendHints.preferredAdapter,
            latencyBudgetMs =
                backendHints.latencyBudgetMs
                    ?: functionKitPrefs.remoteTimeoutSeconds.getValue().coerceAtLeast(1) * 1000
            ,
            latencyTier = backendHints.latencyTier,
            requireStructuredJson = backendHints.requireStructuredJson,
            requiredCapabilities = backendHints.requiredCapabilities,
            notes = backendHints.notes,
            remoteRenderPath = functionKitManifest.remoteRenderPath
        )
    }

    private fun renderCandidates(
        replyTo: String?,
        surface: String = Surface,
        reason: String,
        preferredTone: String,
        modifiers: List<String>,
        requestContext: JSONObject,
        executionConfig: ExecutionConfig
    ) {
        val aiChatConfig = currentAiChatConfig()
        if (!executionConfig.remoteEnabled && !canUseLocalAiForCandidates(aiChatConfig)) {
            host.dispatchCandidatesRender(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                payload = buildCandidatesRenderPayload(requestContext, preferredTone, modifiers)
            )
            return
        }

        if (!executionConfig.remoteEnabled && canUseLocalAiForCandidates(aiChatConfig)) {
            val requestSessionId = sessionId
            val messages =
                buildLocalAiCandidateMessages(
                    requestContext = requestContext,
                    preferredTone = preferredTone,
                    modifiers = modifiers,
                    aiChatConfig = aiChatConfig
                )

            host.dispatchHostStateUpdate(
                kitId = functionKitId,
                surface = surface,
                label = "Android AI 生成候选中",
                details =
                    buildHostDetails(executionConfig)
                        .put("reason", reason)
            )

            remoteExecutor.execute {
                try {
                    val completion =
                        requestLocalAiChatCompletion(
                            aiChatConfig = aiChatConfig,
                            messages = messages,
                            temperature = 0.4,
                            maxOutputTokens = 512
                        )
                    val renderPayload =
                        buildLocalAiCandidatesRenderPayload(
                            requestContext = requestContext,
                            completion = completion,
                            executionConfig = executionConfig,
                            aiChatConfig = aiChatConfig,
                            reason = reason
                        )
                    ContextCompat.getMainExecutor(service).execute {
                        if (requestSessionId != sessionId) {
                            return@execute
                        }

                        host.dispatchCandidatesRender(
                            replyTo = replyTo,
                            kitId = functionKitId,
                            surface = surface,
                            payload = renderPayload
                        )
                        host.dispatchHostStateUpdate(
                            kitId = functionKitId,
                            surface = surface,
                            label = "Android AI 候选已更新",
                            details =
                                buildHostDetails(executionConfig)
                                    .put("reason", reason)
                                    .put(
                                        "candidateCount",
                                        renderPayload.optJSONObject("result")
                                            ?.optJSONArray("candidates")
                                            ?.length() ?: 0
                                    )
                        )
                    }
                } catch (error: RemoteInferenceException) {
                    ContextCompat.getMainExecutor(service).execute {
                        if (requestSessionId != sessionId) {
                            return@execute
                        }

                        dispatchLocalAiChatError(
                            replyTo = replyTo,
                            surface = surface,
                            reason = reason,
                            executionConfig = executionConfig,
                            aiChatConfig = aiChatConfig,
                            error = error,
                            fallbackCandidatesPayload =
                                buildCandidatesRenderPayload(
                                    requestContext = requestContext,
                                    preferredTone = preferredTone,
                                    modifiers = modifiers
                                )
                        )
                    }
                }
            }
            return
        }

        val requestSessionId = sessionId
        val requestPayload =
            buildRemoteRenderRequest(
                reason = reason,
                preferredTone = preferredTone,
                modifiers = modifiers,
                requestContext = requestContext
            )

        host.dispatchHostStateUpdate(
            kitId = functionKitId,
            surface = surface,
            label = "Requesting remote inference",
            details = buildHostDetails(executionConfig).put("reason", reason)
        )

        remoteExecutor.execute {
            try {
                val renderPayload =
                    requestRemoteRender(
                        executionConfig = executionConfig,
                        requestPayload = requestPayload,
                        requestContext = requestContext
                    )
                val candidateCount = countRenderedCandidates(renderPayload)
                ContextCompat.getMainExecutor(service).execute {
                    if (requestSessionId != sessionId) {
                        return@execute
                    }

                    host.dispatchCandidatesRender(
                        replyTo = replyTo,
                        kitId = functionKitId,
                        surface = surface,
                        payload = renderPayload
                    )
                    val remoteMeta = renderPayload.optJSONObject("meta")
                    host.dispatchHostStateUpdate(
                        kitId = functionKitId,
                        surface = surface,
                        label = "Remote candidates updated",
                        details =
                            buildHostDetails(executionConfig)
                                .put("reason", reason)
                                .put("remoteCandidateCount", candidateCount)
                                .put(
                                    "executionMode",
                                    remoteMeta?.optString("resolvedExecutionMode")
                                        ?.takeIf { it.isNotBlank() }
                                        ?: executionConfig.executionMode
                                )
                                .put(
                                    "requestedExecutionMode",
                                    remoteMeta?.optString("executionModeRequested")
                                        ?.takeIf { it.isNotBlank() }
                                        ?: executionConfig.requestedExecutionMode
                                )
                    )
                }
            } catch (error: RemoteInferenceException) {
                ContextCompat.getMainExecutor(service).execute {
                    if (requestSessionId != sessionId) {
                        return@execute
                    }

                    dispatchRemoteInferenceError(
                        replyTo = replyTo,
                        surface = surface,
                        reason = reason,
                        executionConfig = executionConfig,
                        error = error
                    )
                }
            } catch (error: Exception) {
                ContextCompat.getMainExecutor(service).execute {
                    if (requestSessionId != sessionId) {
                        return@execute
                    }

                    dispatchRemoteInferenceError(
                        replyTo = replyTo,
                        surface = surface,
                        reason = reason,
                        executionConfig = executionConfig,
                        error =
                            RemoteInferenceException(
                                code = "remote_inference_failed",
                                message = error.message ?: "Remote inference failed.",
                                retryable = false
                            )
                    )
                }
            }
        }
    }

    private fun buildRemoteRenderRequest(
        reason: String,
        preferredTone: String,
        modifiers: List<String>,
        requestContext: JSONObject
    ): JSONObject =
        JSONObject()
            .put("reason", reason)
            .put("preferredTone", preferredTone)
            .put("modifiers", JSONArray(modifiers))
            .put("context", JSONObject(requestContext.toString()))
            .put("manifest", buildManifestSnapshot())
            .put(
                "ai",
                JSONObject()
                    .put("executionMode", functionKitManifest.ai.executionMode)
                    .put("backendHints", functionKitManifest.ai.backendHints.toJson())
                    .put("allowedPermissions", JSONArray(grantedPermissions))
                    .put("preferredBackendClass", functionKitManifest.ai.backendHints.preferredBackendClass)
                    .put("preferredAdapter", functionKitManifest.ai.backendHints.preferredAdapter)
                    .put("latencyBudgetMs", currentExecutionConfig().latencyBudgetMs)
            )
            .put("slash", buildSlashSnapshot())
            .put(
                "constraints",
                JSONObject()
                    .put("candidateCount", RemoteCandidateCount)
                    .put("maxCharsPerCandidate", RemoteMaxCharsPerCandidate)
            )

    private fun requestRemoteRender(
        executionConfig: ExecutionConfig,
        requestPayload: JSONObject,
        requestContext: JSONObject
    ): JSONObject {
        val endpoint =
            executionConfig.renderEndpoint
                ?: throw RemoteInferenceException(
                    code = "remote_base_url_missing",
                    message = "Remote host service base URL is blank.",
                    retryable = false
                )

        val connection =
            try {
                val url = URL(endpoint)
                require(url.protocol == "http" || url.protocol == "https") {
                    "Unsupported URL scheme: ${url.protocol}"
                }
                url.openConnection() as HttpURLConnection
            } catch (error: RemoteInferenceException) {
                throw error
            } catch (error: Exception) {
                throw RemoteInferenceException(
                    code = "remote_base_url_invalid",
                    message = "Invalid remote host service base URL.",
                    retryable = false,
                    details = error.message
                )
            }

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = executionConfig.timeoutMs
            connection.readTimeout = executionConfig.timeoutMs
            connection.doInput = true
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            if (executionConfig.remoteAuthConfigured) {
                connection.setRequestProperty("Authorization", "Bearer ${executionConfig.remoteAuthToken}")
            }

            connection.outputStream.use { output ->
                output.write(requestPayload.toString().toByteArray(StandardCharsets.UTF_8))
            }

            val statusCode = connection.responseCode
            val body = readResponseBody(connection, successful = statusCode in 200..299)
            if (statusCode !in 200..299) {
                throw buildRemoteHttpException(statusCode, body.body)
            }

            val response =
                try {
                    JSONObject(body.body)
                } catch (error: Exception) {
                    throw RemoteInferenceException(
                        code = "remote_invalid_json",
                        message = "Remote service returned invalid JSON: ${error.message}",
                        retryable = false,
                        statusCode = statusCode,
                        details = body.body
                    )
                }

            return normalizeRemoteRenderPayload(response, requestContext)
        } catch (error: SocketTimeoutException) {
            throw RemoteInferenceException(
                code = "remote_timeout",
                message = "Remote inference timed out after ${executionConfig.timeoutSeconds}s.",
                retryable = true,
                details = endpoint
            )
        } catch (error: IOException) {
            throw RemoteInferenceException(
                code = "remote_io_error",
                message = "Remote inference request failed: ${error.message ?: "I/O error"}",
                retryable = true,
                details = endpoint
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeRemoteRenderPayload(
        response: JSONObject,
        requestContext: JSONObject
    ): JSONObject {
        val result = response.optJSONObject("result")
        if (result?.optJSONArray("candidates") != null) {
            return JSONObject(response.toString()).apply {
                if (optJSONObject("requestContext") == null) {
                    put("requestContext", JSONObject(requestContext.toString()))
                }
                if (optJSONObject("uiHints") == null) {
                    put("uiHints", JSONObject().put("allowRegenerate", true))
                }
            }
        }

        val candidates =
            response.optJSONArray("candidates")
                ?: throw RemoteInferenceException(
                    code = "remote_response_invalid",
                    message = "Remote service returned an unexpected payload.",
                    retryable = false,
                    details = response.toString()
                )

        return JSONObject()
            .put("requestContext", JSONObject(requestContext.toString()))
            .put(
                "result",
                JSONObject()
                    .put("candidates", JSONArray(candidates.toString()))
                    .put(
                        "missing_context",
                        response.optJSONArray("missing_context")?.let { JSONArray(it.toString()) }
                            ?: JSONArray()
                    )
            )
            .put("uiHints", JSONObject().put("allowRegenerate", true))
            .put("meta", response.optJSONObject("meta")?.let { JSONObject(it.toString()) } ?: JSONObject())
    }

    private fun dispatchRemoteInferenceError(
        replyTo: String?,
        surface: String = Surface,
        reason: String,
        executionConfig: ExecutionConfig,
        error: RemoteInferenceException
    ) {
        val errorDetails =
            JSONObject()
                .put("reason", reason)
                .apply {
                    error.statusCode?.let { put("statusCode", it) }
                    error.details?.let { put("remoteDetails", JSONObject.wrap(it)) }
                }

        host.dispatchBridgeError(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = surface,
            code = error.code,
            message = error.message,
            retryable = error.retryable,
            details = errorDetails
        )
        host.dispatchHostStateUpdate(
            kitId = functionKitId,
            surface = surface,
            label = "Remote inference failed",
            details =
                buildHostDetails(executionConfig)
                    .put("reason", reason)
                    .put(
                        "error",
                        JSONObject()
                            .put("code", error.code)
                            .put("message", error.message)
                            .put("retryable", error.retryable)
                            .put("statusCode", error.statusCode)
                    )
        )
    }

    private fun buildRemoteHttpException(
        statusCode: Int,
        body: String
    ): RemoteInferenceException {
        try {
            val errorObject = JSONObject(body).optJSONObject("error")
            if (errorObject != null) {
                return RemoteInferenceException(
                    code = errorObject.optString("code").ifBlank { "remote_http_status" },
                    message =
                        errorObject.optString("message").ifBlank {
                            "Remote service returned HTTP $statusCode."
                        },
                    retryable = errorObject.optBoolean("retryable", statusCode >= 500),
                    statusCode = statusCode,
                    details = errorObject.opt("details") ?: body
                )
            }
        } catch (_: Exception) {
            // Fall back to the raw body below.
        }

        return RemoteInferenceException(
            code = "remote_http_status",
            message = "Remote service returned HTTP $statusCode.",
            retryable = statusCode >= 500,
            statusCode = statusCode,
            details = body.takeIf { it.isNotBlank() }
        )
    }

    private data class ReadResponseBodyResult(
        val body: String,
        val bytes: Int,
        val truncated: Boolean
    )

    private fun readResponseBody(
        connection: HttpURLConnection,
        successful: Boolean
    ): ReadResponseBodyResult {
        val stream =
            if (successful) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

        if (stream == null) {
            return ReadResponseBodyResult(body = "", bytes = 0, truncated = false)
        }

        // Keep bridge payloads bounded. Some upload endpoints echo the uploaded body in the response,
        // which can easily exceed what WebView bridge/evaluateJavascript can reliably handle.
        val maxBytes = 512 * 1024
        val probeLimit = maxBytes + 1
        val buffer = ByteArray(8 * 1024)
        val output = java.io.ByteArrayOutputStream()
        var total = 0

        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                val remaining = probeLimit - total
                if (remaining <= 0) {
                    break
                }
                val toWrite = minOf(read, remaining)
                output.write(buffer, 0, toWrite)
                total += toWrite
                if (total >= probeLimit) {
                    break
                }
            }
        }

        val bytes = output.toByteArray()
        val truncated = bytes.size > maxBytes
        val bodyBytes = if (truncated) bytes.copyOf(maxBytes) else bytes
        val text = String(bodyBytes, StandardCharsets.UTF_8)
        return ReadResponseBodyResult(body = text, bytes = bodyBytes.size, truncated = truncated)
    }

    private fun countRenderedCandidates(renderPayload: JSONObject): Int =
        renderPayload.optJSONObject("result")?.optJSONArray("candidates")?.length() ?: 0

    private fun buildContextSnapshot(
        preferredTone: String,
        modifiers: List<String>,
        maxChars: Int = 64
    ): JSONObject {
        val inputSnapshot =
            FunctionKitInputSnapshotReader.capture(
                service = service,
                cursorContextChars = maxChars,
                selectionTextMaxChars = 8 * 1024
            )
        val beforeCursor = inputSnapshot.beforeCursor
        val afterCursor = inputSnapshot.afterCursor
        val selectedText = inputSnapshot.selectedText
        val sourceMessage =
            when {
                selectedText.isNotBlank() -> "选中文本：${selectedText.trim().take(80)}"
                currentPreeditText.isNotBlank() -> "预编辑文本：${currentPreeditText.trim().take(80)}"
                beforeCursor.isNotBlank() || afterCursor.isNotBlank() -> {
                    "光标上下文：${beforeCursor.takeLast(32)}│${afterCursor.take(32)}".trim()
                }
                else -> "当前输入框没有可读文本，宿主按通用聊天场景生成示例候选。"
            }

        val personaChips = mutableListOf("Android IME", "Function Kit")
        if (currentPackageName.isNotBlank()) {
            personaChips += "App:$currentPackageName"
        }
        personaChips += "Selection:${currentSelectionStart}-${currentSelectionEnd}"
        personaChips += "Tone:$preferredTone"
        if (modifiers.isNotEmpty()) {
            personaChips += "Modifiers:${modifiers.size}"
        }
        if (currentPreeditText.isNotBlank()) {
            personaChips += "Preedit:${currentPreeditText.take(16)}"
        }

        val summary = buildList {
            if (currentPackageName.isNotBlank()) {
                add("目标应用 `$currentPackageName`")
            }
            add("选择区 ${currentSelectionStart}-${currentSelectionEnd}")
            if (modifiers.isNotEmpty()) {
                add("即时指令：${modifiers.joinToString("；")}")
            }
            if (currentCandidateCount > 0) {
                add("fcitx 当前候选数：$currentCandidateCount")
            }
        }.joinToString("；")

        return JSONObject()
            .put("sourceMessage", sourceMessage)
            .put("sourcePackage", currentPackageName)
            .put("selectionStart", currentSelectionStart)
            .put("selectionEnd", currentSelectionEnd)
            .put("selectedText", selectedText.trim())
            .put("beforeCursor", beforeCursor)
            .put("afterCursor", afterCursor)
            .put("preeditText", currentPreeditText)
            .put("inputType", currentInputType)
            .put("candidateCount", currentCandidateCount)
            .put("personaChips", JSONArray(personaChips))
            .put("conversationSummary", summary)
    }

    private fun buildCandidates(
        requestContext: JSONObject,
        preferredTone: String,
        modifiers: List<String>
    ): JSONArray {
        val toneSuffix =
            when (preferredTone) {
                "direct" -> "（偏直接）"
                "warm" -> "（偏温和）"
                else -> "（平衡）"
            }
        val sourceText = requestContext.optString("sourceMessage").removePrefix("选中文本：").take(48)
        val modifierText = modifiers.joinToString("；")

        return JSONArray().apply {
            CandidateVariants[renderSeed % CandidateVariants.size].forEach { candidate ->
                val rationale = buildList {
                    add(candidate.rationale)
                    if (sourceText.isNotBlank()) add("已参考输入上下文：$sourceText")
                    if (modifierText.isNotBlank()) add("附加要求：$modifierText")
                }.joinToString(" ")

                put(
                    JSONObject()
                        .put("id", candidate.id)
                        .put("text", candidate.text)
                        .put("tone", candidate.tone + toneSuffix)
                        .put("risk", candidate.risk)
                        .put("rationale", rationale)
                        .put(
                            "actions",
                            JSONArray()
                                .put(JSONObject().put("type", "insert").put("label", "插入"))
                                .put(JSONObject().put("type", "replace").put("label", "替换"))
                        )
                )
            }
        }
    }

    private fun buildHostInfo(executionConfig: ExecutionConfig = currentExecutionConfig()): JSONObject {
        val aiChatConfig = currentAiChatConfig()
        val resolvedMode =
            resolveFunctionKitEffectiveMode(
                remoteEnabled = executionConfig.remoteEnabled,
                executionMode = executionConfig.executionMode,
                transport = executionConfig.transport,
                modeMessage = executionConfig.modeMessage,
                localAiEligible = canUseLocalAiForCandidates(aiChatConfig),
                localAiEndpointForMessage = aiChatConfig.endpoint ?: aiChatConfig.configuredBaseUrl
            )
        return JSONObject()
            .put("platform", "android")
            .put("runtime", "fcitx5-android-webview")
            .put("protocol", FunctionKitWebViewHost.protocolInfo())
            .put(
                "hostSupportedRuntimePermissions",
                JSONArray(FunctionKitDefaults.supportedPermissions.toList())
            )
            .put("kitDeclaredRuntimePermissions", JSONArray(functionKitManifest.runtimePermissions))
            .put("effectiveRuntimePermissions", JSONArray(supportedRuntimePermissions))
            .put("grantedPermissions", JSONArray(grantedPermissions))
            .put("executionMode", resolvedMode.executionMode)
            .put("requestedExecutionMode", executionConfig.requestedExecutionMode)
            .put("transport", resolvedMode.transport)
            .put(
                "modeMessage",
                resolvedMode.modeMessage
            )
            .put("build", buildDebugInfo())
            .put(
                "discovery",
                functionKitManifest.discovery.toJson()
            )
            .put("manifest", buildManifestSnapshot())
            .put("slash", buildSlashSnapshot())
            .apply {
                if (executionConfig.remoteEnabled) {
                    put("timeoutMs", executionConfig.timeoutMs)
                }
            }
    }

    private fun buildHostDetails(executionConfig: ExecutionConfig = currentExecutionConfig()): JSONObject {
        val aiChatConfig = currentAiChatConfig()
        val resolvedMode =
            resolveFunctionKitEffectiveMode(
                remoteEnabled = executionConfig.remoteEnabled,
                executionMode = executionConfig.executionMode,
                transport = executionConfig.transport,
                modeMessage = executionConfig.modeMessage,
                localAiEligible = canUseLocalAiForCandidates(aiChatConfig),
                localAiEndpointForMessage = aiChatConfig.endpoint ?: aiChatConfig.configuredBaseUrl
            )

        return JSONObject()
            .put("sessionId", sessionId)
            .put("packageName", currentPackageName)
            .put("selectionStart", currentSelectionStart)
            .put("selectionEnd", currentSelectionEnd)
            .put("inputType", currentInputType)
            .put("candidateCount", currentCandidateCount)
            .put("kitId", functionKitId)
            .put("platform", "android")
            .put("runtime", "fcitx5-android-webview")
            .put("protocol", FunctionKitWebViewHost.protocolInfo())
            .put(
                "hostSupportedRuntimePermissions",
                JSONArray(FunctionKitDefaults.supportedPermissions.toList())
            )
            .put("kitDeclaredRuntimePermissions", JSONArray(functionKitManifest.runtimePermissions))
            .put("effectiveRuntimePermissions", JSONArray(supportedRuntimePermissions))
            .put("grantedPermissions", JSONArray(grantedPermissions))
            .put("executionMode", resolvedMode.executionMode)
            .put("requestedExecutionMode", executionConfig.requestedExecutionMode)
            .put("transport", resolvedMode.transport)
            .put("modeMessage", resolvedMode.modeMessage)
            .put("build", buildDebugInfo())
            .put("manifest", buildManifestSnapshot())
            .put("slash", buildSlashSnapshot())
            .put(
                "discovery",
                functionKitManifest.discovery.toJson()
            )
            .apply {
                if (executionConfig.remoteEnabled) {
                    put("timeoutMs", executionConfig.timeoutMs)
                }
            }
    }

    private fun buildManifestSnapshot(): JSONObject =
        JSONObject(functionKitManifest.toJson().toString())

    private fun buildSlashSnapshot(): JSONObject? =
        functionKitManifest.discovery.resolveSlashQuery(resolveSlashSourceText())?.toJson()

    private fun resolveSlashSourceText(): String {
        val inputSnapshot =
            FunctionKitInputSnapshotReader.capture(
                service = service,
                cursorContextChars = 64,
                selectionTextMaxChars = 8 * 1024
            )
        val beforeCursor = inputSnapshot.beforeCursor
        val afterCursor = inputSnapshot.afterCursor
        val selectedText = inputSnapshot.selectedText

        return when {
            currentPreeditText.isNotBlank() -> currentPreeditText
            selectedText.isNotBlank() -> selectedText
            beforeCursor.isNotBlank() || afterCursor.isNotBlank() -> beforeCursor + afterCursor
            else -> ""
        }
    }

    private fun handleHostEvent(message: String) {
        debugLog { "[$functionKitId] $message" }
        if (!panelInitialized) {
            return
        }
        if (!FunctionKitHostDiagnostics.shouldSurfaceHostEventToUi(message)) {
            return
        }

        host.dispatchHostStateUpdate(
            kitId = functionKitId,
            surface = Surface,
            label = message,
            details = buildHostStateDetailsLite()
        )
    }

    private fun readStorageValue(key: String): Any? =
        storage.all[namespacedStorageKey(key)]

    private fun readAllStorageValues(): JSONObject =
        JSONObject().apply {
            storage.all.forEach { (rawKey, value) ->
                if (rawKey.startsWith("$functionKitId:")) {
                    put(rawKey.removePrefix("$functionKitId:"), JSONObject.wrap(value))
                }
            }
        }

    private fun writeStorageValue(
        editor: SharedPreferences.Editor,
        key: String,
        value: Any?
    ) {
        val namespacedKey = namespacedStorageKey(key)
        when (value) {
            null, JSONObject.NULL -> editor.remove(namespacedKey)
            is Boolean -> editor.putBoolean(namespacedKey, value)
            is Int -> editor.putInt(namespacedKey, value)
            is Long -> editor.putLong(namespacedKey, value)
            is Float -> editor.putFloat(namespacedKey, value)
            is Double -> editor.putString(namespacedKey, value.toString())
            is Number -> editor.putString(namespacedKey, value.toString())
            else -> editor.putString(namespacedKey, value.toString())
        }
    }

    private fun namespacedStorageKey(key: String): String = "$functionKitId:$key"

    private fun refreshGrantedPermissions(notifyUi: Boolean = false) {
        grantedPermissions =
            FunctionKitPermissionPolicy.grantedPermissions(
                requestedPermissions = requestedPermissions,
                prefs = functionKitPrefs,
                kitId = functionKitId
            )
        if (notifyUi) {
            host.dispatchPermissionsSync(
                kitId = functionKitId,
                surface = Surface,
                grantedPermissions = grantedPermissions
            )
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun newSessionId(): String = "android-function-kit-${UUID.randomUUID()}"

    private fun buildDebugInfo(): JSONObject =
        JSONObject()
            .put("applicationId", BuildConfig.APPLICATION_ID)
            .put("buildType", BuildConfig.BUILD_TYPE)
            .put("versionName", Const.versionName)
            .put("gitHash", BuildConfig.BUILD_GIT_HASH)
            .put("shortGitHash", FunctionKitHostDiagnostics.shortGitHash(BuildConfig.BUILD_GIT_HASH))
            .put("buildTime", BuildConfig.BUILD_TIME)
            .put(
                "displayName",
                FunctionKitHostDiagnostics.buildDisplayName(Const.versionName, BuildConfig.BUILD_GIT_HASH)
            )

    companion object {
        private const val Surface = FunctionKitDefaults.surface
        private const val DefaultContextRequestReason = "ui-context-request"
        private const val RemoteRegenerateReason = "ui-regenerate"
        private const val RemoteCandidateCount = 3
        private const val RemoteMaxCharsPerCandidate = 120
        private const val MaxBridgeTextChars = 64 * 1024
        private const val MaxInlineImageBytes = 2 * 1024 * 1024
        private const val MaxStoreZipBytes = 96L * 1024L * 1024L
        private const val MaxCatalogIndexBytes = 2L * 1024L * 1024L
        private const val LocalDemoAgentId = "android-local-demo"
        private const val RemoteHostAgentId = "android-remote-host"
        private const val BindingClipboardTextMaxChars = 8 * 1024

        private val BindingSelectionPayloads =
            setOf(
                "selection.text",
                "selection.beforeCursor",
                "selection.afterCursor"
            )

        private val DefaultBindingRequestedPayloadsManual = BindingSelectionPayloads
        private val DefaultBindingRequestedPayloadsSelection = BindingSelectionPayloads
        private val DefaultBindingRequestedPayloadsClipboard = setOf("clipboard.text")

        private val CandidateVariants =
            listOf(
                listOf(
                    CandidateDraft("candidate-1", "收到，我先把第一版整理出来，晚点发你确认。", "稳妥", "low", "先确认动作与交付边界，适合工作沟通。"),
                    CandidateDraft("candidate-2", "明白，我先把重点收一下，整理完就同步给你。", "中性", "low", "语气克制，适合信息还没完全收口时。"),
                    CandidateDraft("candidate-3", "可以，我先出个版本，你看完我们再定下一步。", "配合", "medium", "更口语，但保留后续协作空间。")
                ),
                listOf(
                    CandidateDraft("candidate-4", "好，我先把框架收敛一下，今晚给你过一遍。", "直接", "low", "节奏更快，强调会先给出框架。"),
                    CandidateDraft("candidate-5", "收到，我先整理到可 review 的程度，再同步你确认。", "平衡", "low", "突出 review 节点，适合团队协作。"),
                    CandidateDraft("candidate-6", "明白，我先把要点收口，稍后发你看。", "轻量", "medium", "更短更轻，但时间边界更松。")
                ),
                listOf(
                    CandidateDraft("candidate-7", "可以，我先整理一版，确认完细节就发你。", "温和", "low", "语气更柔和，适合熟悉对象。"),
                    CandidateDraft("candidate-8", "收到，我先把结构搭好，之后发你看是否要补充。", "协作", "low", "强调共同 review，方便继续迭代。"),
                    CandidateDraft("candidate-9", "行，我先出一版，你看完我们再决定怎么推进。", "推进", "medium", "更强调推进，但即时承诺更弱。")
                )
            )
    }
}
