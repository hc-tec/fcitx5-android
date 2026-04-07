/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Message
import android.util.Base64
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
import org.fcitx.fcitx5.android.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
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
private const val VerboseFunctionKitHostLogs = false
private const val DefaultContentSecurityPolicy =
    "default-src 'self'; " +
        "base-uri 'none'; " +
        "object-src 'none'; " +
        "frame-src 'none'; " +
        "form-action 'none'; " +
        "script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
        "style-src 'self' https: http: 'unsafe-inline'; " +
        "img-src 'self' https: http: data: blob:; " +
        "media-src 'self' https: http: data: blob:; " +
        "font-src 'self' https: http: data:;"

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
        "files.download",
        "files.getUrl",
        "ai.request",
        "ai.agent.list",
        "ai.agent.run",
        "runtime.message.send",
        "tasks.sync.request",
        "task.cancel",
        "kits.sync.request",
        "kits.open",
        "kits.install",
        "kits.uninstall",
        "kits.settings.update",
        "catalog.sources.get",
        "catalog.sources.set",
        "catalog.refresh",
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
        "files.download.result",
        "files.getUrl.result",
        "ai.response",
        "ai.response.delta",
        "ai.agent.list.result",
        "ai.agent.run.result",
        "task.update",
        "tasks.sync",
        "task.cancel.ack",
        "kits.sync",
        "kits.open.result",
        "kits.install.result",
        "kits.uninstall.result",
        "kits.settings.update.result",
        "catalog.sources.sync",
        "catalog.sync",
        "composer.state.sync",
    )

private inline fun debugLog(message: () -> String) {
    if (BuildConfig.DEBUG && VerboseFunctionKitHostLogs) {
        Log.d(LogTag, message())
    }
}

class FunctionKitWebViewHost(
    private val webView: WebView,
    private val assetLoader: WebViewAssetLoader,
    private val onUiEnvelope: (JSONObject) -> Unit,
    private val onHostEnvelope: (JSONObject) -> Unit = {},
    private val onHostEvent: (String) -> Unit = {},
    private val config: Config = Config()
) {
    enum class KitAssetResolution {
        InstalledFirst,
        BundledOnly
    }

    data class Config(
        val localDomain: String = DefaultLocalDomain,
        val assetPathPrefix: String = DefaultAssetPathPrefix,
        val bridgeName: String = DefaultBridgeName,
        val legacyBridgeName: String = DefaultLegacyBridgeName,
        val expectedKitId: String? = null,
        val expectedSurface: String? = "panel",
        val enableDevTools: Boolean = false,
        val kitAssetResolution: KitAssetResolution = KitAssetResolution.InstalledFirst,
        val allowExternalResources: Boolean = true,
        val contentSecurityPolicy: String? = DefaultContentSecurityPolicy
    ) {
        val normalizedAssetPathPrefix: String =
            assetPathPrefix
                .trim()
                .let { if (it.startsWith("/")) it else "/$it" }
                .let { if (it.endsWith("/")) it else "$it/" }

        val localOrigin: String = "https://$localDomain"
    }

    private var bridgeInstalled = false
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
            val kitsHandler: WebViewAssetLoader.PathHandler =
                when (config.kitAssetResolution) {
                    KitAssetResolution.BundledOnly ->
                        FunctionKitBundledOnlyPathHandler(
                            fallback = assetsHandler
                        )
                    else ->
                        FunctionKitInstalledFirstPathHandler(
                            context = context,
                            installRootDir = installRootDir,
                            fallback = assetsHandler
                        )
                }
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
        private val context: Context,
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
                    ?: return fallback.handle("/function-kits/")

            val segments = normalized.split('/')
            if (segments.any { it.isBlank() || it == "." || it == ".." }) {
                return null
            }

            val target = File(installRootDir, normalized)
            if (target.isFile) {
                return internalStorageHandler.handle("/$normalized")
            }

            // Support "install key"-based packages stored under `function-kits/_packages/...`
            // while keeping the virtual asset path stable: `function-kits/<kitId>/...`.
            if (segments.size >= 2) {
                val kitId = segments.firstOrNull().orEmpty().trim()
                val relative = segments.drop(1).joinToString("/").trim()
                if (kitId.isNotBlank() && relative.isNotBlank()) {
                    val resolved = FunctionKitPackageManager.resolveUserInstalledFile(context, kitId, relative)
                    if (resolved != null && resolved.isFile) {
                        val relativeToRoot =
                            runCatching { resolved.relativeTo(installRootDir).path.replace('\\', '/') }.getOrNull()
                        if (!relativeToRoot.isNullOrBlank()) {
                            return internalStorageHandler.handle("/$relativeToRoot")
                        }
                    }
                }
            }

            return fallback.handle("/function-kits/$normalized")
        }
    }

    private class FunctionKitBundledOnlyPathHandler(
        private val fallback: WebViewAssetLoader.PathHandler
    ) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val normalized =
                path.replace("\\", "/")
                    .trimStart('/')
                    .takeUnless(String::isBlank)
                    ?: return fallback.handle("/function-kits/")

            val segments = normalized.split('/')
            if (segments.any { it.isBlank() || it == "." || it == ".." }) {
                return null
            }

            return fallback.handle("/function-kits/$normalized")
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
        webView.isLongClickable = true
        webView.setDownloadListener { url, _, _, _, _ ->
            onHostEvent("Blocked download request: $url")
        }
        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                    if (BuildConfig.DEBUG && VerboseFunctionKitHostLogs) {
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
                    }
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

                    val scheme = uri.scheme?.lowercase()
                    if (request.isForMainFrame && (scheme == "http" || scheme == "https")) {
                        runCatching {
                            view.context.startActivity(
                                Intent(Intent.ACTION_VIEW, uri)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onSuccess {
                            onHostEvent("Opened external link: $uri")
                        }.onFailure { error ->
                            onHostEvent("Failed to open external link: $uri error=${error.message}")
                        }
                        return true
                    }

                    onHostEvent("Blocked navigation: $uri")
                    return true
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val uri = request?.url
                        ?: return createTextResponse(
                            statusCode = 404,
                            reasonPhrase = "Not Found",
                            body = "missing-resource"
                        )
                    if (isAllowedLocalUri(uri)) {
                        interceptExternalResourceProxy(uri, request)?.let { response ->
                            return response
                        }
                        return assetLoader.shouldInterceptRequest(uri)
                            ?.let { response -> applyHtmlSecurityHeaders(uri, response) }
                            ?: createTextResponse(
                                statusCode = 404,
                                reasonPhrase = "Not Found",
                                body = "missing-local-resource"
                            )
                    }
                    if (isAllowedOrigin(uri)) {
                        onHostEvent("Blocked local resource request: $uri")
                        return createTextResponse(
                            statusCode = 404,
                            reasonPhrase = "Not Found",
                            body = "missing-local-resource"
                        )
                    }
                    if (!config.allowExternalResources || request.isForMainFrame) {
                        onHostEvent("Blocked external resource request: $uri")
                        return createTextResponse(
                            statusCode = 403,
                            reasonPhrase = "Forbidden",
                            body = "blocked"
                        )
                    }

                    return null
                }
            }

        WebView.setWebContentsDebuggingEnabled(config.enableDevTools)
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
            debugLog { "Dispatching envelope type=$messageType via webmessage" }
            dispatchEnvelopeViaWebMessage(serializedEnvelope)
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
            debugLog {
                "Dispatched envelope type=${envelope.optString("type")} via replyProxy replyTo=$replyTo"
            }
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
            rememberReplyProxy(envelope, replyProxy)
            debugLog {
                "Inbound envelope type=${envelope.optString("type")} kitId=${envelope.optString("kitId")} surface=${envelope.optString("surface")}"
            }
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

    fun dispatchFilesDownloadResult(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "files.download.result",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchFilesGetUrlResult(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "files.getUrl.result",
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

    fun dispatchKitsSync(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "kits.sync",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchKitsOpenResult(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "kits.open.result",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchKitsInstallResult(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "kits.install.result",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchKitsUninstallResult(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "kits.uninstall.result",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchKitsSettingsUpdateResult(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "kits.settings.update.result",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchCatalogSourcesSync(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "catalog.sources.sync",
            replyTo = replyTo,
            kitId = kitId,
            surface = surface,
            payload = payload
        )
    }

    fun dispatchCatalogSync(
        replyTo: String?,
        kitId: String,
        surface: String,
        payload: JSONObject
    ) {
        dispatchTypedPayload(
            type = "catalog.sync",
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

    private fun interceptExternalResourceProxy(
        uri: Uri,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val path = uri.encodedPath ?: return null
        val prefix = "${config.normalizedAssetPathPrefix}__external__/"
        if (!path.startsWith(prefix)) {
            return null
        }
        if (!config.allowExternalResources || request.isForMainFrame) {
            return createTextResponse(
                statusCode = 403,
                reasonPhrase = "Forbidden",
                body = "blocked"
            )
        }
        val method = request.method.trim().uppercase()
        if (method != "GET") {
            return createTextResponse(
                statusCode = 405,
                reasonPhrase = "Method Not Allowed",
                body = "method-not-allowed"
            )
        }

        val token = path.removePrefix(prefix).trim()
        if (token.isBlank()) {
            return createTextResponse(
                statusCode = 404,
                reasonPhrase = "Not Found",
                body = "missing-resource"
            )
        }

        val targetUrl =
            decodeBase64Url(token)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return createTextResponse(
                    statusCode = 400,
                    reasonPhrase = "Bad Request",
                    body = "invalid-external-resource-token"
                )

        val scheme = Uri.parse(targetUrl).scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") {
            return createTextResponse(
                statusCode = 400,
                reasonPhrase = "Bad Request",
                body = "invalid-external-resource-url"
            )
        }

        return fetchExternalResource(targetUrl)
    }

    private fun decodeBase64Url(value: String): String? {
        val normalized = value.trim().takeIf { it.isNotBlank() } ?: return null
        val padded =
            when (normalized.length % 4) {
                0 -> normalized
                2 -> "$normalized=="
                3 -> "$normalized="
                else -> return null
            }
        return runCatching {
            val bytes = Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
            String(bytes, StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun fetchExternalResource(url: String): WebResourceResponse {
        val maxBytes = 4 * 1024 * 1024
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection)
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 7_000
                connection.readTimeout = 7_000
                connection.useCaches = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "*/*")
                connection.setRequestProperty("Accept-Encoding", "identity")
                runCatching {
                    connection.setRequestProperty("User-Agent", webView.settings.userAgentString)
                }

                val statusCode = connection.responseCode
                val reasonPhrase = connection.responseMessage ?: "OK"
                val mimeType =
                    connection.contentType
                        ?.substringBefore(';')
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: URLConnection.guessContentTypeFromName(url)
                        ?: "application/octet-stream"

                val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
                val bodyBytes = readStreamUpTo(stream, maxBytes)

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    WebResourceResponse(
                        mimeType,
                        StandardCharsets.UTF_8.name(),
                        ByteArrayInputStream(bodyBytes)
                    )
                } else {
                    WebResourceResponse(
                        mimeType,
                        StandardCharsets.UTF_8.name(),
                        statusCode,
                        reasonPhrase,
                        mapOf(
                            "Cache-Control" to "no-store",
                            "X-Content-Type-Options" to "nosniff"
                        ),
                        ByteArrayInputStream(bodyBytes)
                    )
                }
            } finally {
                connection.disconnect()
            }
        } catch (error: ExternalResourceTooLargeException) {
            createTextResponse(
                statusCode = 413,
                reasonPhrase = "Payload Too Large",
                body = "external-resource-too-large"
            )
        } catch (error: Throwable) {
            onHostEvent("External resource proxy failed url=$url error=${error.message}")
            createTextResponse(
                statusCode = 502,
                reasonPhrase = "Bad Gateway",
                body = "external-resource-proxy-failed"
            )
        }
    }

    private class ExternalResourceTooLargeException(
        message: String
    ) : RuntimeException(message)

    private fun readStreamUpTo(
        stream: java.io.InputStream?,
        maxBytes: Int
    ): ByteArray {
        if (stream == null) {
            return ByteArray(0)
        }
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                total += read
                if (total > maxBytes) {
                    throw ExternalResourceTooLargeException("Resource is too large (${total} bytes)")
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
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

    private fun applyHtmlSecurityHeaders(
        uri: Uri,
        response: WebResourceResponse
    ): WebResourceResponse {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return response
        }
        val mimeType = response.mimeType?.trim()?.lowercase().orEmpty()
        val path = uri.encodedPath?.trim()?.lowercase().orEmpty()
        val isHtml =
            mimeType.contains("text/html") ||
                path.endsWith(".html") ||
                path.endsWith(".htm")
        if (!isHtml) {
            return response
        }

        val headers = LinkedHashMap<String, String>()
        response.responseHeaders?.let { existing -> headers.putAll(existing) }
        config.contentSecurityPolicy?.trim()?.takeIf { it.isNotBlank() }?.let { value ->
            headers["Content-Security-Policy"] = value
        }
        if (!headers.containsKey("Cache-Control")) {
            headers["Cache-Control"] = "no-store"
        }
        if (!headers.containsKey("X-Content-Type-Options")) {
            headers["X-Content-Type-Options"] = "nosniff"
        }
        response.responseHeaders = headers
        return response
    }

    private fun requireFeature(feature: String) {
        check(WebViewFeature.isFeatureSupported(feature)) {
            "Required WebView feature is missing: $feature"
        }
    }
}
