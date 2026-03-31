/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Message
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.LinkedHashMap
import java.util.UUID

private const val ProtocolVersion = "1.0.0"
private const val DefaultLocalDomain = "function-kit.local"
private const val DefaultAssetPathPrefix = "/assets/"
private const val DefaultBridgeName = "AndroidFunctionKitHost"
private const val DefaultLegacyBridgeName = "AndroidFunctionKitLegacyHost"
private const val LogTag = "FunctionKitWebViewHost"
private const val RecentReplyProxyLimit = 64

private val AllowedSurfaces = setOf("inline", "panel", "editor")
private val AllowedInboundTypes =
    setOf(
        "bridge.ready",
        "context.request",
        "candidate.insert",
        "candidate.replace",
        "input.commitImage",
        "input.observe.best_effort.start",
        "input.observe.best_effort.stop",
        "candidates.regenerate",
        "network.fetch",
        "files.pick",
        "ai.request",
        "ai.agent.list",
        "ai.agent.run",
        "runtime.message.send",
        "tasks.sync.request",
        "task.cancel",
        "send.intercept.ime_action.register",
        "send.intercept.ime_action.unregister",
        "send.intercept.ime_action.result",
        "composer.open",
        "composer.focus",
        "composer.update",
        "composer.close",
        "settings.open",
        "storage.get",
        "storage.set",
        "panel.state.update"
    )
private val AllowedOutboundTypes =
    setOf(
        "bridge.ready.ack",
        "binding.invoke",
        "permissions.sync",
        "context.sync",
        "candidates.render",
        "storage.sync",
        "panel.state.ack",
        "input.observe.best_effort.ack",
        "send.intercept.ime_action.ack",
        "send.intercept.ime_action.intent",
        "host.state.update",
        "runtime.message.send.ack",
        "runtime.message",
        "permission.denied",
        "bridge.error",
        "network.fetch.result",
        "files.pick.result",
        "ai.response",
        "ai.response.delta",
        "ai.agent.list.result",
        "ai.agent.run.result",
        "task.update",
        "tasks.sync",
        "task.cancel.ack",
        "composer.state.sync",
    )

class FunctionKitWebViewHost(
    private val webView: WebView,
    private val assetLoader: WebViewAssetLoader,
    private val onUiEnvelope: (JSONObject) -> Unit,
    private val onHostEnvelope: (JSONObject) -> Unit = {},
    private val onHostEvent: (String) -> Unit = {},
    private val config: Config = Config()
) {
    data class Config(
        val localDomain: String = DefaultLocalDomain,
        val assetPathPrefix: String = DefaultAssetPathPrefix,
        val bridgeName: String = DefaultBridgeName,
        val legacyBridgeName: String = DefaultLegacyBridgeName,
        val expectedKitId: String? = null,
        val expectedSurface: String? = "panel",
        val enableDevTools: Boolean = false
    ) {
        val normalizedAssetPathPrefix: String =
            assetPathPrefix
                .trim()
                .let { if (it.startsWith("/")) it else "/$it" }
                .let { if (it.endsWith("/")) it else "$it/" }

        val localOrigin: String = "https://$localDomain"
    }

    private var bridgeInstalled = false
    private var hasReceivedUiEnvelope = false
    private val replyProxyByUiMessageId =
        object : LinkedHashMap<String, JavaScriptReplyProxy>(RecentReplyProxyLimit, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JavaScriptReplyProxy>?): Boolean =
                size > RecentReplyProxyLimit
        }
    private val legacyBridge =
        object {
            @JavascriptInterface
            fun postMessage(rawEnvelope: String?) {
                if (rawEnvelope.isNullOrBlank()) {
                    onHostEvent("Dropped empty envelope from legacy bridge")
                    return
                }

                webView.post {
                    dispatchInboundEnvelope(rawEnvelope)
                }
            }
        }

    companion object {
        fun createDefaultAssetLoader(
            context: Context,
            config: Config = Config()
        ): WebViewAssetLoader =
            createAssetLoader(
                context = FunctionKitPackageManager.storageContext(context),
                config = config,
                installRootDir = FunctionKitPackageManager.kitsRootDir(context).apply { mkdirs() }
            )

        private fun createAssetLoader(
            context: Context,
            config: Config,
            installRootDir: File
        ): WebViewAssetLoader {
            val assetsHandler = WebViewAssetLoader.AssetsPathHandler(context)
            val kitsHandler =
                FunctionKitInstalledFirstPathHandler(
                    context = context,
                    installRootDir = installRootDir,
                    fallback = assetsHandler
                )
            return WebViewAssetLoader.Builder()
                .setDomain(config.localDomain)
                .addPathHandler(
                    "${config.normalizedAssetPathPrefix}function-kits/",
                    kitsHandler
                )
                .addPathHandler(
                    config.normalizedAssetPathPrefix,
                    assetsHandler
                )
                .build()
        }

        fun supportedInboundTypes(): List<String> = AllowedInboundTypes.toList().sorted()

        fun supportedOutboundTypes(): List<String> = AllowedOutboundTypes.toList().sorted()

        fun protocolInfo(): JSONObject =
            JSONObject()
                .put("version", ProtocolVersion)
                .put("surfaces", JSONArray(AllowedSurfaces.toList().sorted()))
                .put("inboundTypes", JSONArray(supportedInboundTypes()))
                .put("outboundTypes", JSONArray(supportedOutboundTypes()))
    }

    private class FunctionKitInstalledFirstPathHandler(
        context: Context,
        private val installRootDir: File,
        private val fallback: WebViewAssetLoader.PathHandler
    ) : WebViewAssetLoader.PathHandler {
        private val internalStorageHandler =
            WebViewAssetLoader.InternalStoragePathHandler(context, installRootDir)

        override fun handle(path: String): WebResourceResponse? {
            val normalized =
                path.replace("\\", "/")
                    .trimStart('/')
                    .takeUnless(String::isBlank)
                    ?: return fallback.handle(path)

            val segments = normalized.split('/')
            if (segments.any { it.isBlank() || it == "." || it == ".." }) {
                return fallback.handle(path)
            }

            val target = File(installRootDir, normalized)
            if (target.isFile) {
                return internalStorageHandler.handle(path)
            }

            return fallback.handle(path)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun initialize(entryRelativePath: String) {
        requireFeature(WebViewFeature.WEB_MESSAGE_LISTENER)
        requireFeature(WebViewFeature.POST_WEB_MESSAGE)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        // Keep all kits on a single origin for now, so DOM storage would break kit isolation.
        // Persisted state must go through `storage.*` which is namespaced per kit.
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mediaPlaybackRequiresUserGesture = true
        settings.setSupportMultipleWindows(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = true
        }

        CookieManager.getInstance().setAcceptCookie(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        }

        webView.removeJavascriptInterface("searchBoxJavaBridge_")
        webView.removeJavascriptInterface("accessibility")
        webView.removeJavascriptInterface("accessibilityTraversal")
        webView.removeJavascriptInterface(config.legacyBridgeName)
        webView.addJavascriptInterface(legacyBridge, config.legacyBridgeName)
        webView.isLongClickable = false
        webView.setDownloadListener { url, _, _, _, _ ->
            onHostEvent("Blocked download request: $url")
        }
        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                    onHostEvent(
                        buildString {
                            append("WebView console[")
                            append(consoleMessage.messageLevel())
                            append("] ")
                            append(consoleMessage.message())
                            append(" @")
                            append(consoleMessage.sourceId())
                            append(':')
                            append(consoleMessage.lineNumber())
                        }
                    )
                    return super.onConsoleMessage(consoleMessage)
                }

                override fun onPermissionRequest(request: PermissionRequest) {
                    request.deny()
                    onHostEvent(
                        "Denied WebView permission request: ${request.resources.joinToString()}"
                    )
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean = false
            }
        webView.webViewClient =
            object : WebViewClientCompat() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val uri = request.url
                    if (isAllowedLocalUri(uri)) {
                        return false
                    }

                    onHostEvent("Blocked navigation: $uri")
                    return true
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse {
                    val uri = request?.url
                    if (!isAllowedLocalUri(uri)) {
                        onHostEvent("Blocked resource request: $uri")
                        return createTextResponse(
                            statusCode = 403,
                            reasonPhrase = "Forbidden",
                            body = "blocked"
                        )
                    }

                    return uri?.let(assetLoader::shouldInterceptRequest)
                        ?: createTextResponse(
                            statusCode = 404,
                            reasonPhrase = "Not Found",
                            body = "missing-local-resource"
                        )
                }
            }

        WebView.setWebContentsDebuggingEnabled(config.enableDevTools)
        hasReceivedUiEnvelope = false
        synchronized(replyProxyByUiMessageId) {
            replyProxyByUiMessageId.clear()
        }
        installBridgeIfNeeded()
        webView.loadUrl(buildEntryUrl(entryRelativePath))
    }

    fun dispatchEnvelope(envelope: JSONObject) {
        FunctionKitEnvelopeProbe.recordOutbound(envelope)
        runCatching { onHostEnvelope(envelope) }
            .onFailure { error -> Log.w(LogTag, "Host envelope hook failed", error) }
        val serializedEnvelope = envelope.toString()
        val messageType = envelope.optString("type")
        webView.post {
            if (hasReceivedUiEnvelope) {
                Log.d(LogTag, "Dispatching envelope type=$messageType via javascript")
                dispatchEnvelopeViaJavascript(serializedEnvelope)
            } else {
                Log.d(LogTag, "Dispatching envelope type=$messageType via webmessage")
                dispatchEnvelopeViaWebMessage(serializedEnvelope)
            }
        }
    }

    private fun dispatchTypedPayload(
        type: String,
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject,
        error: JSONObject? = null
    ) {
        check(type in AllowedOutboundTypes) { "Unsupported outbound type: $type" }
        dispatchEnvelope(
            buildEnvelope(
                type = type,
                replyTo = replyTo,
                kitId = kitId,
                surface = surface,
                payload = payload,
                error = error
            )
        )
    }

    private fun dispatchEnvelopeViaWebMessage(serializedEnvelope: String) {
        try {
            WebViewCompat.postWebMessage(
                webView,
                WebMessageCompat(serializedEnvelope),
                Uri.parse(config.localOrigin)
            )
        } catch (error: Throwable) {
            Log.w(LogTag, "postWebMessage failed, falling back to JavaScript dispatch", error)
            dispatchEnvelopeViaJavascript(serializedEnvelope)
        }
    }

    private fun dispatchEnvelopeViaReplyProxy(
        replyTo: String,
        serializedEnvelope: String,
        envelope: JSONObject
    ): Boolean {
        val replyProxy =
            synchronized(replyProxyByUiMessageId) {
                replyProxyByUiMessageId[replyTo]
            } ?: return false

        return try {
            replyProxy.postMessage(serializedEnvelope)
            Log.d(
                LogTag,
                "Dispatched envelope type=${envelope.optString("type")} via replyProxy replyTo=$replyTo"
            )
            true
        } catch (error: Throwable) {
            synchronized(replyProxyByUiMessageId) {
                replyProxyByUiMessageId.remove(replyTo)
            }
            Log.w(
                LogTag,
                "replyProxy dispatch failed for type=${envelope.optString("type")} replyTo=$replyTo",
                error
            )
            false
        }
    }

    private fun dispatchEnvelopeViaJavascript(serializedEnvelope: String) {
        val quotedEnvelope = JSONObject.quote(serializedEnvelope)
        val script =
            """
            (() => {
              const envelope = $quotedEnvelope;
              const bridge = window.__FUNCTION_KIT_HOST_BRIDGE__;
              if (bridge && typeof bridge.dispatchEnvelope === "function") {
                bridge.dispatchEnvelope(envelope);
                return "dispatched";
              }
              window.__FUNCTION_KIT_PENDING_HOST_ENVELOPES__ = window.__FUNCTION_KIT_PENDING_HOST_ENVELOPES__ || [];
              window.__FUNCTION_KIT_PENDING_HOST_ENVELOPES__.push(envelope);
              return "queued";
            })();
            """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun dispatchInboundEnvelope(
        rawEnvelope: String,
        replyProxy: JavaScriptReplyProxy? = null
    ) {
        parseInboundEnvelope(rawEnvelope)?.let { envelope ->
            hasReceivedUiEnvelope = true
            rememberReplyProxy(envelope, replyProxy)
            Log.d(
                LogTag,
                "Inbound envelope type=${envelope.optString("type")} kitId=${envelope.optString("kitId")} surface=${envelope.optString("surface")}"
            )
            onUiEnvelope(envelope)
        }
    }

    private fun rememberReplyProxy(
        envelope: JSONObject,
        replyProxy: JavaScriptReplyProxy?
    ) {
        if (replyProxy == null) {
            return
        }
        val messageId = envelope.optString("messageId")
        if (messageId.isBlank()) {
            return
        }
        synchronized(replyProxyByUiMessageId) {
            replyProxyByUiMessageId[messageId] = replyProxy
        }
    }

    fun dispatchReadyAck(
        replyTo: String?,
        kitId: String,
        surface: String,
        sessionId: String,
        grantedPermissions: List<String>,
        hostInfo: JSONObject
    ) {
        dispatchTypedPayload(
            type = "bridge.ready.ack",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload =
                JSONObject()
                    .put("sessionId", sessionId)
                    // org.json does not reliably serialize Kotlin/Java lists into JSON arrays across API levels.
                    // Force a JSONArray so the WebView runtime sees an array instead of a string like "[a, b]".
                    .put("grantedPermissions", JSONArray(grantedPermissions))
                    .put("hostInfo", hostInfo)
        )
    }

    fun dispatchPermissionsSync(
        kitId: String,
        surface: String,
        grantedPermissions: List<String>
    ) {
        dispatchTypedPayload(
            type = "permissions.sync",
            replyTo = null,
            kitId = kitId,
            surface = surface,
            payload = JSONObject().put("grantedPermissions", JSONArray(grantedPermissions))
        )
    }

    fun dispatchContextSync(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "context.sync",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchCandidatesRender(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "candidates.render",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchStorageSync(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "storage.sync",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchPanelStateAck(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "panel.state.ack",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchInputObserveBestEffortAck(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "input.observe.best_effort.ack",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchSendInterceptImeActionAck(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "send.intercept.ime_action.ack",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchSendInterceptImeActionIntent(
        kitId: String,
        surface: String,
        payload: JSONObject
    ): String {
        check("send.intercept.ime_action.intent" in AllowedOutboundTypes) {
            "Unsupported outbound type: send.intercept.ime_action.intent"
        }

        val envelope =
            buildEnvelope(
                type = "send.intercept.ime_action.intent",
                replyTo = null,
                kitId = kitId,
                surface = surface,
                payload = payload
            )
        dispatchEnvelope(envelope)
        return envelope.optString("messageId")
    }

    fun dispatchHostStateUpdate(
        kitId: String,
        surface: String,
        label: String,
        details: JSONObject = JSONObject()
    ) {
        dispatchTypedPayload(
            type = "host.state.update",
            replyTo = null,
            kitId = kitId,
            surface = surface,
            payload =
                JSONObject()
                    .put("label", label)
                    .put("details", details)
        )
    }

    fun dispatchRuntimeMessageSendAck(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "runtime.message.send.ack",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchRuntimeMessage(
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "runtime.message",
            replyTo = null,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchBindingInvoke(
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "binding.invoke",
            replyTo = null,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchPermissionDenied(
        replyTo: String?,
        kitId: String,
        surface: String,
        permission: String
    ) {
        dispatchTypedPayload(
            type = "permission.denied",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = JSONObject(),
            error =
                JSONObject()
                    .put("code", "permission_denied")
                    .put("message", "Permission not granted: $permission")
                    .put("retryable", false)
                    .put("details", JSONObject().put("permission", permission))
        )
    }

    fun dispatchBridgeError(
        replyTo: String?,
        kitId: String,
        surface: String,
        code: String,
        message: String,
        retryable: Boolean,
        details: JSONObject = JSONObject()
    ) {
        dispatchTypedPayload(
            type = "bridge.error",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = JSONObject(),
            error =
                JSONObject()
                    .put("code", code)
                    .put("message", message)
                    .put("retryable", retryable)
                    .put("details", details)
        )
    }

    fun dispatchNetworkFetchResult(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "network.fetch.result",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchFilesPickResult(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "files.pick.result",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchAiResponse(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "ai.response",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchAiAgentListResult(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "ai.agent.list.result",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchAiAgentRunResult(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "ai.agent.run.result",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchComposerStateSync(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "composer.state.sync",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchTaskUpdate(
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        try {
            FunctionKitTaskHub.recordTaskUpdate(kitId = kitId, surface = surface, payload = payload)
        } catch (error: Throwable) {
            Log.w(LogTag, "TaskHub recordTaskUpdate failed kitId=$kitId surface=$surface", error)
        }
        dispatchTypedPayload(
            type = "task.update",
            replyTo = null,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchTasksSync(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "tasks.sync",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchTaskCancelAck(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "task.cancel.ack",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    private fun installBridgeIfNeeded() {
        if (bridgeInstalled) {
            return
        }

        WebViewCompat.addWebMessageListener(
            webView,
            config.bridgeName,
            setOf(config.localOrigin),
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: JavaScriptReplyProxy
                ) {
                    if (!isMainFrame) {
                        onHostEvent("Dropped non-main-frame message from $sourceOrigin")
                        return
                    }

                    if (!isAllowedOrigin(sourceOrigin)) {
                        onHostEvent("Dropped message from unexpected origin: $sourceOrigin")
                        return
                    }

                    val rawEnvelope = message.data
                    if (rawEnvelope.isNullOrBlank()) {
                        onHostEvent("Dropped empty envelope from UI")
                        return
                    }

                    dispatchInboundEnvelope(rawEnvelope, replyProxy)
                }
            }
        )

        bridgeInstalled = true
    }

    private fun buildEntryUrl(entryRelativePath: String): String {
        val normalizedEntryPath =
            entryRelativePath
                .replace("\\", "/")
                .trim('/')
                .also {
                    require(it.isNotEmpty()) { "entryRelativePath must not be blank" }
                    require(!it.split('/').any { segment -> segment.isBlank() || segment == ".." }) {
                        "entryRelativePath must stay inside the local asset root"
                    }
                }

        return "${config.localOrigin}${config.normalizedAssetPathPrefix}$normalizedEntryPath"
    }

    private fun buildEnvelope(
        type: String,
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject,
        error: JSONObject? = null
    ): JSONObject {
        val envelope =
            JSONObject()
                .put("version", ProtocolVersion)
                .put("messageId", "host-$type-${UUID.randomUUID()}")
                .put("timestamp", OffsetDateTime.now().toString())
                .put("kitId", kitId)
                .put("surface", surface)
                .put("source", "host-adapter")
                .put("target", "function-kit-ui")
                .put("type", type)
                .put("payload", payload)

        if (!replyTo.isNullOrBlank()) {
            envelope.put("replyTo", replyTo)
        }

        if (error != null) {
            envelope.put("error", error)
        }

        return envelope
    }

    private fun parseInboundEnvelope(rawEnvelope: String): JSONObject? =
        try {
            val envelope = JSONObject(rawEnvelope)

            if (envelope.optString("version") != ProtocolVersion) {
                onHostEvent("Dropped envelope with unsupported version")
                return null
            }

            if (envelope.optString("source") != "function-kit-ui") {
                onHostEvent("Dropped envelope with unexpected source")
                return null
            }

            val target = envelope.optString("target")
            if (target.isNotBlank() && target != "host-adapter") {
                onHostEvent("Dropped envelope with unexpected target")
                return null
            }

            if (envelope.optString("messageId").isBlank()) {
                onHostEvent("Dropped envelope without messageId")
                return null
            }

            if (envelope.optString("timestamp").isBlank()) {
                onHostEvent("Dropped envelope without timestamp")
                return null
            }

            val kitId = envelope.optString("kitId")
            if (kitId.isBlank()) {
                onHostEvent("Dropped envelope without kitId")
                return null
            }

            if (!config.expectedKitId.isNullOrBlank() && config.expectedKitId != kitId) {
                onHostEvent("Dropped envelope for unexpected kitId: $kitId")
                return null
            }

            val surface = envelope.optString("surface")
            if (surface !in AllowedSurfaces) {
                onHostEvent("Dropped envelope with unsupported surface: $surface")
                return null
            }

            if (!config.expectedSurface.isNullOrBlank() && config.expectedSurface != surface) {
                onHostEvent("Dropped envelope for unexpected surface: $surface")
                return null
            }

            val type = envelope.optString("type")
            if (type !in AllowedInboundTypes) {
                onHostEvent("Dropped envelope with unsupported type: $type")
                return null
            }

            if (envelope.opt("payload") !is JSONObject) {
                onHostEvent("Dropped envelope without object payload")
                return null
            }

            if (envelope.has("replyTo") && envelope.optString("replyTo").isBlank()) {
                onHostEvent("Dropped envelope with blank replyTo")
                return null
            }

            envelope
        } catch (error: Exception) {
            onHostEvent("Dropped malformed envelope: ${error.message}")
            null
        }

    private fun createTextResponse(
        statusCode: Int,
        reasonPhrase: String,
        body: String
    ): WebResourceResponse {
        val data = ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return WebResourceResponse(
                "text/plain",
                StandardCharsets.UTF_8.name(),
                data
            )
        }

        return WebResourceResponse(
            "text/plain",
            StandardCharsets.UTF_8.name(),
            statusCode,
            reasonPhrase,
            mapOf("Cache-Control" to "no-store"),
            data
        )
    }

    private fun isAllowedLocalUri(uri: Uri?): Boolean {
        if (uri == null) {
            return false
        }

        if (uri.scheme != "https") {
            return false
        }

        if (!uri.host.equals(config.localDomain, ignoreCase = true)) {
            return false
        }

        return uri.encodedPath?.startsWith(config.normalizedAssetPathPrefix) == true
    }

    private fun isAllowedOrigin(uri: Uri?): Boolean {
        if (uri == null) {
            return false
        }

        if (uri.scheme != "https") {
            return false
        }

        return uri.host.equals(config.localDomain, ignoreCase = true)
    }

    private fun requireFeature(feature: String) {
        check(WebViewFeature.isFeatureSupported(feature)) {
            "Required WebView feature is missing: $feature"
        }
    }
}
