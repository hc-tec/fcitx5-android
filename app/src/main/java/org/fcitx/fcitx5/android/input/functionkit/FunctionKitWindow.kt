/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.utils.AppUtil
import org.json.JSONArray
import org.json.JSONObject
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

class FunctionKitWindow : org.fcitx.fcitx5.android.input.wm.InputWindow.ExtendedInputWindow<FunctionKitWindow>(),
    InputBroadcastReceiver {

    private data class CandidateDraft(
        val id: String,
        val text: String,
        val tone: String,
        val risk: String,
        val rationale: String
    )

    private data class ExecutionConfig(
        val remoteEnabled: Boolean,
        val configuredBaseUrl: String,
        val normalizedBaseUrl: String,
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
        val renderEndpoint: String? =
            normalizedBaseUrl.takeIf { it.isNotBlank() }?.let { "$it$remoteRenderPath" }
        val executionMode: String = if (remoteEnabled) requestedExecutionMode else "local-demo"
        val transport: String = if (remoteEnabled) "remote-http" else "local-webview"
        val modeMessage: String =
            if (remoteEnabled) {
                renderEndpoint?.let { "Remote inference enabled via $it ($requestedExecutionMode)" }
                    ?: "Remote inference is enabled, but the host service base URL is blank."
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

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val theme: Theme by manager.theme()
    private val functionKitPrefs = AppPrefs.getInstance().functionKit
    private val functionKitManifest by lazy {
        FunctionKitManifest.loadFromAssets(
            context = context,
            assetPath = ManifestAssetPath,
            fallbackId = KitId,
            fallbackEntryHtmlAssetPath = EntryAssetPath,
            fallbackRuntimePermissions = SupportedPermissions
        )
    }

    private val hostConfig by lazy { FunctionKitWebViewHost.Config(expectedKitId = functionKitId) }
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
            onHostEvent = ::handleHostEvent,
            config = hostConfig
        )
    }
    private val rootView by lazy {
        context.frameLayout {
            backgroundColor = theme.barColor
            add(webView, lParams(matchParent, matchParent))
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
            setOnClickListener { AppUtil.launchMainToFunctionKitSettings(context) }
        }
    }
    private val barExtension by lazy {
        context.horizontalLayout {
            add(settingsButton, lParams(dp(40), dp(40)))
            add(refreshButton, lParams(dp(40), dp(40)))
        }
    }
    private val storage by lazy {
        context.getSharedPreferences("function_kit_storage", Context.MODE_PRIVATE)
    }
    private val remoteExecutor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "FunctionKitRemoteInference").apply {
                isDaemon = true
            }
        }
    }

    private var panelInitialized = false
    private var renderSeed = 0
    private var sessionId = newSessionId()
    private var requestedPermissions = functionKitManifest.runtimePermissions
    private var grantedPermissions = functionKitManifest.runtimePermissions
    private var currentPackageName = ""
    private var currentSelectionStart = 0
    private var currentSelectionEnd = 0
    private var currentInputType = 0
    private var currentCandidateCount = 0
    private var currentPreeditText = ""
    private val functionKitId: String
        get() = functionKitManifest.id

    override fun onCreateView(): View = rootView

    override fun onAttached() {
        syncCurrentInputState()
        refreshGrantedPermissions(notifyUi = panelInitialized)

        if (!panelInitialized) {
            host.initialize(functionKitManifest.entryHtmlAssetPath)
            panelInitialized = true
        }

        webView.onResume()
        host.dispatchHostStateUpdate(
            kitId = functionKitId,
            surface = Surface,
            label = "Android Function Kit 面板已打开",
            details = buildHostDetails()
        )
    }

    override fun onDetached() {
        webView.onPause()
        webView.stopLoading()
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        currentPackageName = info.packageName.orEmpty()
        currentInputType = info.inputType
        pushHostState("输入上下文已切换")
    }

    override fun onSelectionUpdate(start: Int, end: Int) {
        currentSelectionStart = start
        currentSelectionEnd = end
        pushHostState("光标或选择区已更新")
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        currentCandidateCount = data.candidates.size
    }

    override fun onClientPreeditUpdate(data: FormattedText) {
        currentPreeditText = data.toString()
    }

    override val title: String by lazy { context.getString(R.string.function_kit_auto_reply) }

    override fun onCreateBarExtension(): View = barExtension

    private fun handleUiEnvelope(envelope: JSONObject) {
        val type = envelope.optString("type")
        val replyTo = envelope.optString("messageId")
        val payload = envelope.optJSONObject("payload") ?: JSONObject()

        when (type) {
            "bridge.ready" -> handleBridgeReady(replyTo, payload)
            "context.request" -> handleContextRequest(replyTo, payload)
            "candidates.regenerate" -> handleRegenerate(replyTo, payload)
            "candidate.insert" -> handleCommit(replyTo, payload, "input.insert", replace = false)
            "candidate.replace" -> handleCommit(replyTo, payload, "input.replace", replace = true)
            "storage.get" -> handleStorageGet(replyTo, payload)
            "storage.set" -> handleStorageSet(replyTo, payload)
            "panel.state.update" -> handlePanelStateUpdate(replyTo, payload)
            "settings.open" -> handleSettingsOpen(replyTo)
            else -> {
                host.dispatchBridgeError(
                    replyTo = replyTo,
                    kitId = functionKitId,
                    surface = Surface,
                    code = "unsupported_message_type",
                    message = "Unsupported message type: $type",
                    retryable = false,
                    details = JSONObject().put("type", type)
                )
            }
        }
    }

    private fun handleBridgeReady(replyTo: String, payload: JSONObject) {
        requestedPermissions = payload.optJSONArray("requestedPermissions")
            .toStringList()
            .filter { it in functionKitManifest.runtimePermissions }
            .distinct()
            .ifEmpty { functionKitManifest.runtimePermissions }
        refreshGrantedPermissions()
        renderSeed = 0
        sessionId = newSessionId()

        host.dispatchReadyAck(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = Surface,
            sessionId = sessionId,
            grantedPermissions = grantedPermissions,
            hostInfo = buildHostInfo()
        )
        host.dispatchPermissionsSync(
            kitId = functionKitId,
            surface = Surface,
            grantedPermissions = grantedPermissions
        )
        pushHostState("宿主握手完成")
    }

    private fun handleContextRequest(replyTo: String, payload: JSONObject) {
        ensurePermission(replyTo, "context.read") ?: return

        val executionConfig = currentExecutionConfig()
        val preferredTone = payload.optString("preferredTone").ifBlank { "balanced" }
        val modifiers = payload.optJSONArray("modifiers").toStringList()
        val reason = payload.optString("reason").ifBlank { DefaultContextRequestReason }
        val contextSnapshot = buildContextSnapshot(preferredTone, modifiers)
        val contextPayload = buildContextPayload(contextSnapshot, preferredTone, modifiers, reason)

        host.dispatchContextSync(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = Surface,
            payload = contextPayload
        )
        renderCandidates(
            replyTo = replyTo,
            reason = reason,
            preferredTone = preferredTone,
            modifiers = modifiers,
            requestContext = contextSnapshot,
            executionConfig = executionConfig
        )
    }

    private fun handleRegenerate(replyTo: String, payload: JSONObject) {
        ensurePermission(replyTo, "candidates.regenerate") ?: return

        val executionConfig = currentExecutionConfig()
        if (!executionConfig.remoteEnabled) {
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
            service.commitText(text)
            host.dispatchHostStateUpdate(
                kitId = functionKitId,
                surface = Surface,
                label = if (replace) "候选已写回输入框（replace）" else "候选已写回输入框（insert）",
                details = buildHostDetails().put("candidateId", payload.optString("candidateId"))
            )
        }
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

    private fun handleSettingsOpen(replyTo: String) {
        ensurePermission(replyTo, "settings.open") ?: return

        AppUtil.launchMainToFunctionKitSettings(context)
        host.dispatchHostStateUpdate(
            kitId = functionKitId,
            surface = Surface,
            label = "已打开功能件设置"
        )
    }

    private fun ensurePermission(replyTo: String, permission: String): String? {
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

    private fun pushHostState(label: String) {
        if (!panelInitialized) {
            return
        }

        host.dispatchHostStateUpdate(
            kitId = functionKitId,
            surface = Surface,
            label = label,
            details = buildHostDetails()
        )
    }

    private fun syncCurrentInputState() {
        currentPackageName = service.currentInputEditorInfo.packageName.orEmpty()
        currentInputType = service.currentInputEditorInfo.inputType
        service.currentInputSelection.let {
            currentSelectionStart = it.start
            currentSelectionEnd = it.end
        }
    }

    private fun reloadPanel() {
        refreshGrantedPermissions()
        renderSeed = 0
        sessionId = newSessionId()
        host.initialize(functionKitManifest.entryHtmlAssetPath)
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
            .put("routing", buildRoutingSnapshot(currentExecutionConfig(), reason))
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
            .put("routing", buildRoutingSnapshot(currentExecutionConfig(), "render"))
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
        reason: String,
        preferredTone: String,
        modifiers: List<String>,
        requestContext: JSONObject,
        executionConfig: ExecutionConfig
    ) {
        if (!executionConfig.remoteEnabled) {
            host.dispatchCandidatesRender(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = Surface,
                payload = buildCandidatesRenderPayload(requestContext, preferredTone, modifiers)
            )
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
            surface = Surface,
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
                        surface = Surface,
                        payload = renderPayload
                    )
                    val remoteMeta = renderPayload.optJSONObject("meta")
                    host.dispatchHostStateUpdate(
                        kitId = functionKitId,
                        surface = Surface,
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
                    details = error.message ?: executionConfig.configuredBaseUrl
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

            connection.outputStream.use { output ->
                output.write(requestPayload.toString().toByteArray(StandardCharsets.UTF_8))
            }

            val statusCode = connection.responseCode
            val body = readResponseBody(connection, successful = statusCode in 200..299)
            if (statusCode !in 200..299) {
                throw buildRemoteHttpException(statusCode, body)
            }

            val response =
                try {
                    JSONObject(body)
                } catch (error: Exception) {
                    throw RemoteInferenceException(
                        code = "remote_invalid_json",
                        message = "Remote service returned invalid JSON: ${error.message}",
                        retryable = false,
                        statusCode = statusCode,
                        details = body
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
        reason: String,
        executionConfig: ExecutionConfig,
        error: RemoteInferenceException
    ) {
        val errorDetails =
            JSONObject()
                .put("reason", reason)
                .put("baseUrl", executionConfig.configuredBaseUrl)
                .apply {
                    executionConfig.renderEndpoint?.let { put("endpoint", it) }
                    error.statusCode?.let { put("statusCode", it) }
                    error.details?.let { put("remoteDetails", JSONObject.wrap(it)) }
                }

        host.dispatchBridgeError(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = Surface,
            code = error.code,
            message = error.message,
            retryable = error.retryable,
            details = errorDetails
        )
        host.dispatchHostStateUpdate(
            kitId = functionKitId,
            surface = Surface,
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

    private fun readResponseBody(
        connection: HttpURLConnection,
        successful: Boolean
    ): String {
        val stream =
            if (successful) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

        return stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private fun countRenderedCandidates(renderPayload: JSONObject): Int =
        renderPayload.optJSONObject("result")?.optJSONArray("candidates")?.length() ?: 0

    private fun buildContextSnapshot(
        preferredTone: String,
        modifiers: List<String>
    ): JSONObject {
        val inputConnection = service.currentInputConnection
        val beforeCursor = inputConnection?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        val afterCursor = inputConnection?.getTextAfterCursor(64, 0)?.toString().orEmpty()
        val selectedText = inputConnection?.getSelectedText(0)?.toString().orEmpty()
        val sourceMessage =
            when {
                selectedText.isNotBlank() -> "选中文本：${selectedText.trim().take(80)}"
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

    private fun buildHostInfo(executionConfig: ExecutionConfig = currentExecutionConfig()): JSONObject =
        JSONObject()
            .put("platform", "android")
            .put("runtime", "fcitx5-android-webview")
            .put("executionMode", executionConfig.executionMode)
            .put("requestedExecutionMode", executionConfig.requestedExecutionMode)
            .put("transport", executionConfig.transport)
            .put("modeMessage", executionConfig.modeMessage)
            .put("baseUrl", executionConfig.configuredBaseUrl)
            .put("preferredBackendClass", executionConfig.preferredBackendClass)
            .put("preferredAdapter", executionConfig.preferredAdapter)
            .put("latencyBudgetMs", executionConfig.latencyBudgetMs)
            .put("latencyTier", executionConfig.latencyTier)
            .put("requireStructuredJson", executionConfig.requireStructuredJson)
            .put("requiredCapabilities", JSONArray(executionConfig.requiredCapabilities))
            .put(
                "discovery",
                functionKitManifest.discovery.toJson()
            )
            .put("manifest", buildManifestSnapshot())
            .put("slash", buildSlashSnapshot())
            .apply {
                executionConfig.renderEndpoint?.let { put("endpoint", it) }
                if (executionConfig.remoteEnabled) {
                    put("timeoutMs", executionConfig.timeoutMs)
                }
            }

    private fun buildHostDetails(executionConfig: ExecutionConfig = currentExecutionConfig()): JSONObject =
        JSONObject()
            .put("sessionId", sessionId)
            .put("packageName", currentPackageName)
            .put("selectionStart", currentSelectionStart)
            .put("selectionEnd", currentSelectionEnd)
            .put("inputType", currentInputType)
            .put("candidateCount", currentCandidateCount)
            .put("kitId", functionKitId)
            .put("platform", "android")
            .put("runtime", "fcitx5-android-webview")
            .put("executionMode", executionConfig.executionMode)
            .put("requestedExecutionMode", executionConfig.requestedExecutionMode)
            .put("transport", executionConfig.transport)
            .put("modeMessage", executionConfig.modeMessage)
            .put("baseUrl", executionConfig.configuredBaseUrl)
            .put("preferredBackendClass", executionConfig.preferredBackendClass)
            .put("preferredAdapter", executionConfig.preferredAdapter)
            .put("latencyBudgetMs", executionConfig.latencyBudgetMs)
            .put("latencyTier", executionConfig.latencyTier)
            .put("requireStructuredJson", executionConfig.requireStructuredJson)
            .put("requiredCapabilities", JSONArray(executionConfig.requiredCapabilities))
            .put("manifest", buildManifestSnapshot())
            .put("routing", buildRoutingSnapshot(executionConfig, "host-state"))
            .put("slash", buildSlashSnapshot())
            .put(
                "discovery",
                functionKitManifest.discovery.toJson()
            )
            .apply {
                executionConfig.renderEndpoint?.let { put("endpoint", it) }
                if (executionConfig.remoteEnabled) {
                    put("timeoutMs", executionConfig.timeoutMs)
                }
            }

    private fun buildManifestSnapshot(): JSONObject = functionKitManifest.toJson()

    private fun buildRoutingSnapshot(
        executionConfig: ExecutionConfig,
        reason: String
    ): JSONObject =
        JSONObject()
            .put("requestedExecutionMode", executionConfig.requestedExecutionMode)
            .put("effectiveExecutionMode", executionConfig.executionMode)
            .put("preferredBackendClass", executionConfig.preferredBackendClass)
            .put("preferredAdapter", executionConfig.preferredAdapter)
            .put("latencyTier", executionConfig.latencyTier)
            .put("latencyBudgetMs", executionConfig.latencyBudgetMs)
            .put("requireStructuredJson", executionConfig.requireStructuredJson)
            .put("requiredCapabilities", JSONArray(executionConfig.requiredCapabilities))
            .put("notes", JSONArray(executionConfig.notes))
            .put("renderPath", functionKitManifest.remoteRenderPath)
            .put("reason", reason)

    private fun buildSlashSnapshot(): JSONObject? =
        functionKitManifest.discovery.resolveSlashQuery(resolveSlashSourceText())?.toJson()

    private fun resolveSlashSourceText(): String {
        val inputConnection = service.currentInputConnection
        val beforeCursor = inputConnection?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        val afterCursor = inputConnection?.getTextAfterCursor(64, 0)?.toString().orEmpty()
        val selectedText = inputConnection?.getSelectedText(0)?.toString().orEmpty()

        return when {
            currentPreeditText.isNotBlank() -> currentPreeditText
            selectedText.isNotBlank() -> selectedText
            beforeCursor.isNotBlank() || afterCursor.isNotBlank() -> beforeCursor + afterCursor
            else -> ""
        }
    }

    private fun handleHostEvent(message: String) {
        if (!panelInitialized) {
            return
        }

        host.dispatchHostStateUpdate(
            kitId = functionKitId,
            surface = Surface,
            label = message,
            details = buildHostDetails()
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
        grantedPermissions = requestedPermissions.filter(::isPermissionEnabled)
        if (notifyUi) {
            host.dispatchPermissionsSync(
                kitId = functionKitId,
                surface = Surface,
                grantedPermissions = grantedPermissions
            )
        }
    }

    private fun isPermissionEnabled(permission: String): Boolean =
        when (permission) {
            "context.read" -> functionKitPrefs.allowContextRead.getValue()
            "input.insert" -> functionKitPrefs.allowInputInsert.getValue()
            "input.replace" -> functionKitPrefs.allowInputReplace.getValue()
            "candidates.regenerate" -> functionKitPrefs.allowCandidatesRegenerate.getValue()
            "settings.open" -> functionKitPrefs.allowSettingsOpen.getValue()
            "storage.read" -> functionKitPrefs.allowStorageRead.getValue()
            "storage.write" -> functionKitPrefs.allowStorageWrite.getValue()
            "panel.state.write" -> functionKitPrefs.allowPanelStateWrite.getValue()
            else -> false
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

    companion object {
        private const val KitId = "chat-auto-reply"
        private const val Surface = "panel"
        private const val ManifestAssetPath = "function-kits/chat-auto-reply/manifest.json"
        private const val EntryAssetPath = "function-kits/chat-auto-reply/ui/app/index.html"
        private const val DefaultContextRequestReason = "ui-context-request"
        private const val RemoteRegenerateReason = "ui-regenerate"
        private const val RemoteCandidateCount = 3
        private const val RemoteMaxCharsPerCandidate = 120

        private val SupportedPermissions =
            linkedSetOf(
                "context.read",
                "input.insert",
                "input.replace",
                "candidates.regenerate",
                "settings.open",
                "storage.read",
                "storage.write",
                "panel.state.write"
            )

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
