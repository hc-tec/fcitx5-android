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

    private data class ComposerState(
        val open: Boolean = false,
        val focused: Boolean = false,
        val text: String = "",
        val selectionStart: Int = 0,
        val selectionEnd: Int = 0,
        val revision: Int = 0,
        val mode: String = "detached",
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

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val theme: Theme by manager.theme()
    private val aiPrefs = AppPrefs.getInstance().ai
    private val functionKitPrefs = AppPrefs.getInstance().functionKit
    private val functionKitManifest by lazy {
        FunctionKitManifest.loadFromAssets(
            context = context,
            assetPath = FunctionKitDefaults.manifestAssetPath,
            fallbackId = FunctionKitDefaults.kitId,
            fallbackEntryHtmlAssetPath = FunctionKitDefaults.entryAssetPath,
            fallbackRuntimePermissions = FunctionKitDefaults.supportedPermissions
        )
    }

    private val hostConfig by lazy {
        FunctionKitWebViewHost.Config(
            expectedKitId = functionKitId,
            expectedSurface = null
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
    private var requestedPermissions = emptyList<String>()
    private var grantedPermissions = emptyList<String>()
    private var currentPackageName = ""
    private var currentSelectionStart = 0
    private var currentSelectionEnd = 0
    private var currentInputType = 0
    private var currentCandidateCount = 0
    private var currentPreeditText = ""
    private var composerState = ComposerState(updatedAtEpochMs = System.currentTimeMillis())
    private val functionKitId: String
        get() = functionKitManifest.id
    private val supportedRuntimePermissions: List<String>
        get() = (functionKitManifest.runtimePermissions + FunctionKitDefaults.supportedPermissions).distinct()

    override fun onCreateView(): View {
        ensureManifestStateInitialized()
        return rootView
    }

    override fun onAttached() {
        ensureManifestStateInitialized()
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

    private fun ensureManifestStateInitialized() {
        if (requestedPermissions.isNotEmpty() && grantedPermissions.isNotEmpty()) {
            return
        }
        val manifestPermissions = supportedRuntimePermissions
        if (requestedPermissions.isEmpty()) {
            requestedPermissions = manifestPermissions
        }
        if (grantedPermissions.isEmpty()) {
            grantedPermissions = manifestPermissions
        }
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
        val surface = envelope.optString("surface").ifBlank { Surface }
        val payload = envelope.optJSONObject("payload") ?: JSONObject()

        when (type) {
            "bridge.ready" -> handleBridgeReady(replyTo, surface, payload)
            "context.request" -> handleContextRequest(replyTo, payload)
            "candidates.regenerate" -> handleRegenerate(replyTo, payload)
            "candidate.insert" -> handleCommit(replyTo, payload, "input.insert", replace = false)
            "candidate.replace" -> handleCommit(replyTo, payload, "input.replace", replace = true)
            "storage.get" -> handleStorageGet(replyTo, payload)
            "storage.set" -> handleStorageSet(replyTo, payload)
            "panel.state.update" -> handlePanelStateUpdate(replyTo, payload)
            "network.fetch" -> handleNetworkFetch(replyTo, surface, payload)
            "ai.chat.status.request" -> handleAiChatStatusRequest(replyTo, surface)
            "ai.chat" -> handleAiChat(replyTo, surface, payload)
            "ai.agent.list" -> handleAiAgentList(replyTo, surface)
            "ai.agent.run" -> handleAiAgentRun(replyTo, surface, payload)
            "composer.open" -> handleComposerOpen(replyTo, surface, payload)
            "composer.focus" -> handleComposerFocus(replyTo, surface, payload)
            "composer.update" -> handleComposerUpdate(replyTo, surface, payload)
            "composer.close" -> handleComposerClose(replyTo, surface, payload)
            "composer.apply.insert" -> handleComposerApply(replyTo, surface, payload, replace = false)
            "composer.apply.replace" -> handleComposerApply(replyTo, surface, payload, replace = true)
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

    private fun handleBridgeReady(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        val executionConfig = currentExecutionConfig()
        requestedPermissions = payload.optJSONArray("requestedPermissions")
            .toStringList()
            .filter { it in supportedRuntimePermissions }
            .distinct()
            .ifEmpty { supportedRuntimePermissions }
        refreshGrantedPermissions()
        renderSeed = 0
        sessionId = newSessionId()

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
        host.dispatchAiChatStatusSync(
            replyTo = null,
            kitId = functionKitId,
            surface = surface,
            payload = buildAiChatStatusPayload(executionConfig)
        )
        dispatchComposerStateSync(replyTo = null, surface = surface, reason = "bridge.ready")
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

    private fun handleSettingsOpen(
        replyTo: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "settings.open") ?: return

        val section = payload.optString("section").trim().lowercase()
        if (section == "ai") {
            AppUtil.launchMainToAiSettings(context)
        } else {
            AppUtil.launchMainToFunctionKitSettings(context)
        }
        host.dispatchHostStateUpdate(
            kitId = functionKitId,
            surface = Surface,
            label = if (section == "ai") "已打开 AI 设置" else "已打开功能件设置"
        )
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

        remoteExecutor.execute {
            try {
                val responsePayload = executeNetworkFetch(rawUrl, init, executionConfig)
                ContextCompat.getMainExecutor(service).execute {
                    if (requestSessionId != sessionId) {
                        return@execute
                    }

                    host.dispatchNetworkFetchResult(
                        replyTo = replyTo,
                        kitId = functionKitId,
                        surface = surface,
                        payload = responsePayload
                    )
                    host.dispatchHostStateUpdate(
                        kitId = functionKitId,
                        surface = surface,
                        label = "network.fetch 已完成",
                        details =
                            buildHostDetails(executionConfig)
                                .put("url", rawUrl)
                                .put("status", responsePayload.optJSONObject("response")?.optInt("status"))
                    )
                }
            } catch (error: RemoteInferenceException) {
                ContextCompat.getMainExecutor(service).execute {
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
                                .put("url", rawUrl)
                                .put("statusCode", error.statusCode)
                                .put("details", JSONObject.wrap(error.details))
                    )
                }
            }
        }
    }

    private fun handleAiChatStatusRequest(
        replyTo: String,
        surface: String
    ) {
        val executionConfig = currentExecutionConfig()
        host.dispatchAiChatStatusSync(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = surface,
            payload = buildAiChatStatusPayload(executionConfig)
        )
    }

    private fun handleAiChat(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "ai.chat") ?: return

        val executionConfig = currentExecutionConfig()
        val aiChatConfig = currentAiChatConfig()
        val statusPayload = buildAiChatStatusPayload(executionConfig)
        if (statusPayload.optString("status") != "ready") {
            host.dispatchBridgeError(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                code = "ai_chat_not_ready",
                message = "Android chat backend is not ready yet.",
                retryable = false,
                details = statusPayload
            )
            return
        }

        val requestSessionId = sessionId
        val messages = buildAiChatMessages(payload)
        val temperature = payload.optDouble("temperature").takeIf { payload.has("temperature") }
        val maxOutputTokens = payload.optInt("maxTokens").takeIf { payload.has("maxTokens") }

        host.dispatchHostStateUpdate(
            kitId = functionKitId,
            surface = surface,
            label = "Android AI chat 请求中",
            details =
                buildHostDetails(executionConfig)
                    .put("reason", "ai.chat")
                    .put("model", aiChatConfig.model)
        )

        remoteExecutor.execute {
            try {
                val completion =
                    requestLocalAiChatCompletion(
                        aiChatConfig = aiChatConfig,
                        messages = messages,
                        temperature = temperature,
                        maxOutputTokens = maxOutputTokens
                    )
                ContextCompat.getMainExecutor(service).execute {
                    if (requestSessionId != sessionId) {
                        return@execute
                    }

                    host.dispatchAiChatResult(
                        replyTo = replyTo,
                        kitId = functionKitId,
                        surface = surface,
                        payload =
                            JSONObject()
                                .put("text", completion.text)
                                .put(
                                    "message",
                                    JSONObject()
                                        .put("role", "assistant")
                                        .put("content", completion.text)
                                )
                                .put(
                                    "structured",
                                    completion.structured?.let { JSONObject(it.toString()) } ?: JSONObject.NULL
                                )
                                .put(
                                    "candidates",
                                    FunctionKitAiChatBackend.normalizeCandidates(
                                        rawText = completion.text,
                                        maxCandidates = RemoteCandidateCount,
                                        maxCharsPerCandidate = RemoteMaxCharsPerCandidate
                                    )
                                )
                                .put(
                                    "usage",
                                    completion.usage?.let { JSONObject(it.toString()) } ?: JSONObject()
                                )
                                .put("routing", buildLocalAiRoutingSnapshot(executionConfig, aiChatConfig, "ai.chat"))
                                .put("hostInfo", buildHostInfo(executionConfig))
                    )
                    host.dispatchHostStateUpdate(
                        kitId = functionKitId,
                        surface = surface,
                        label = "Android AI chat 已完成",
                        details =
                            buildHostDetails(executionConfig)
                                .put("reason", "ai.chat")
                                .put("model", aiChatConfig.model)
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
                        reason = "ai.chat",
                        executionConfig = executionConfig,
                        aiChatConfig = aiChatConfig,
                        error = error
                    )
                }
            }
        }
    }

    private fun handleAiAgentList(
        replyTo: String,
        surface: String
    ) {
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
                    .put("routing", buildRoutingSnapshot(executionConfig, "ai.agent.run"))
                    .put("request", JSONObject(payload.toString()))
        )
    }

    private fun handleComposerOpen(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "composer.control") ?: return

        composerState =
            composerState.copy(
                open = true,
                focused = payload.optBoolean("focused", true),
                text = payload.optString("text").takeIf { payload.has("text") } ?: composerState.text,
                selectionStart =
                    payload.optInt("selectionStart").takeIf { payload.has("selectionStart") }
                        ?: composerState.selectionStart,
                selectionEnd =
                    payload.optInt("selectionEnd").takeIf { payload.has("selectionEnd") }
                        ?: composerState.selectionEnd,
                revision = composerState.revision + 1,
                mode = payload.optString("mode").ifBlank { composerState.mode },
                source = "kit",
                lastAction = "open",
                updatedAtEpochMs = System.currentTimeMillis()
            )
        dispatchComposerState(replyTo, surface)
    }

    private fun handleComposerFocus(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "composer.control") ?: return

        composerState =
            composerState.copy(
                open = true,
                focused = payload.optBoolean("focused", true),
                revision = composerState.revision + 1,
                source = "kit",
                lastAction = "focus",
                updatedAtEpochMs = System.currentTimeMillis()
            )
        dispatchComposerState(replyTo, surface)
    }

    private fun handleComposerUpdate(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "composer.control") ?: return

        composerState =
            composerState.copy(
                open = true,
                focused = payload.optBoolean("focused", composerState.focused),
                text = payload.optString("text").takeIf { payload.has("text") } ?: composerState.text,
                selectionStart =
                    payload.optInt("selectionStart").takeIf { payload.has("selectionStart") }
                        ?: composerState.selectionStart,
                selectionEnd =
                    payload.optInt("selectionEnd").takeIf { payload.has("selectionEnd") }
                        ?: composerState.selectionEnd,
                revision = composerState.revision + 1,
                source = "kit",
                lastAction = "update",
                updatedAtEpochMs = System.currentTimeMillis()
            )
        dispatchComposerState(replyTo, surface)
    }

    private fun handleComposerClose(
        replyTo: String,
        surface: String,
        payload: JSONObject
    ) {
        ensurePermission(replyTo, "composer.control") ?: return

        composerState =
            composerState.copy(
                open = payload.optBoolean("open", false),
                focused = false,
                revision = composerState.revision + 1,
                source = "kit",
                lastAction = "close",
                updatedAtEpochMs = System.currentTimeMillis()
            )
        dispatchComposerState(replyTo, surface)
    }

    private fun handleComposerApply(
        replyTo: String,
        surface: String,
        payload: JSONObject,
        replace: Boolean
    ) {
        ensurePermission(replyTo, if (replace) "input.replace" else "input.insert") ?: return

        val text =
            payload.optString("text")
                .takeIf { payload.has("text") }
                ?.trim()
                .orEmpty()
                .ifBlank { composerState.text.trim() }
        if (text.isBlank()) {
            host.dispatchBridgeError(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                code = "composer_apply_empty",
                message = "Composer draft is empty.",
                retryable = false
            )
            return
        }

        composerState =
            composerState.copy(
                text = text,
                selectionStart = text.length,
                selectionEnd = text.length,
                revision = composerState.revision + 1,
                source = "kit",
                lastAction = if (replace) "apply-replace" else "apply-insert",
                open = !payload.optBoolean("closeAfterApply", true),
                focused = false,
                updatedAtEpochMs = System.currentTimeMillis()
            )

        ContextCompat.getMainExecutor(service).execute {
            service.commitText(text)
            host.dispatchComposerApplyResult(
                replyTo = replyTo,
                kitId = functionKitId,
                surface = surface,
                payload =
                    JSONObject()
                        .put("applied", true)
                        .put("mode", if (replace) "replace" else "insert")
                        .put("text", text)
                        .put("composer", composerState.toJson())
            )
            host.dispatchComposerStateSync(
                replyTo = null,
                kitId = functionKitId,
                surface = surface,
                payload = composerState.toJson()
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
            )

    private fun normalizeComposerState(state: ComposerState): ComposerState {
        val clampedStart = state.selectionStart.coerceIn(0, state.text.length)
        val clampedEnd = state.selectionEnd.coerceIn(0, state.text.length)
        return state.copy(
            selectionStart = minOf(clampedStart, clampedEnd),
            selectionEnd = maxOf(clampedStart, clampedEnd),
            revision = state.revision + 1,
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }

    private fun resolveComposerText(
        payload: JSONObject,
        fallback: String
    ): String =
        payload.optString("text")
            .ifBlank { payload.optString("initialText") }
            .ifBlank { fallback }

    private fun applyComposerSelection(
        payload: JSONObject,
        text: String,
        fallbackStart: Int,
        fallbackEnd: Int
    ): Pair<Int, Int> {
        val rawStart =
            if (payload.has("selectionStart")) {
                payload.optInt("selectionStart", fallbackStart)
            } else {
                fallbackStart
            }
        val rawEnd =
            if (payload.has("selectionEnd")) {
                payload.optInt("selectionEnd", fallbackEnd)
            } else {
                fallbackEnd
            }
        val start = rawStart.coerceIn(0, text.length)
        val end = rawEnd.coerceIn(0, text.length)
        return minOf(start, end) to maxOf(start, end)
    }

    private fun currentSelectedText(): String =
        service.currentInputConnection?.getSelectedText(0)?.toString().orEmpty()

    private fun dispatchComposerState(
        replyTo: String?,
        surface: String
    ) {
        dispatchComposerStateSync(replyTo = replyTo, surface = surface, reason = "composer.sync")
    }

    private fun currentAiChatConfig(): HostAiChatConfig =
        FunctionKitAiChatBackend.fromPrefs(aiPrefs)

    private fun canUseLocalAiForCandidates(aiChatConfig: HostAiChatConfig): Boolean =
        aiChatConfig.isConfigured &&
            "ai.chat" in grantedPermissions &&
            functionKitManifest.ai.executionMode == "direct-model"

    private fun buildLocalAiRoutingSnapshot(
        executionConfig: ExecutionConfig,
        aiChatConfig: HostAiChatConfig,
        reason: String
    ): JSONObject =
        JSONObject(buildRoutingSnapshot(executionConfig, reason).toString())
            .put("effectiveExecutionMode", "direct-model")
            .put("effectiveBackendClass", "direct-model")
            .put("transport", "android-direct-http")
            .put("providerType", aiChatConfig.providerType)
            .put("model", aiChatConfig.model)
            .put("baseUrl", aiChatConfig.configuredBaseUrl)

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
        val inputPayload = payload.opt("input")
        val userContent =
            buildString {
                append(prompt.ifBlank { "Continue from the provided input and answer helpfully." })
                if (format.equals("json", ignoreCase = true)) {
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
                    details = error.message ?: aiChatConfig.configuredBaseUrl
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
                throw buildLocalAiHttpException(statusCode, responseBody)
            }

            val response =
                try {
                    JSONObject(responseBody)
                } catch (error: Exception) {
                    throw RemoteInferenceException(
                        code = "ai_chat_invalid_json",
                        message = "Android AI chat returned invalid JSON: ${error.message}",
                        retryable = false,
                        details = responseBody
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
            .put("routing", buildLocalAiRoutingSnapshot(executionConfig, aiChatConfig, reason))
            .put("slash", buildSlashSnapshot())
            .put(
                "meta",
                JSONObject()
                    .put("backendClass", "direct-model")
                    .put("providerType", aiChatConfig.providerType)
                    .put("model", aiChatConfig.model)
                    .put("usage", completion.usage ?: JSONObject())
            )

    private fun dispatchLocalAiChatError(
        replyTo: String?,
        surface: String,
        reason: String,
        executionConfig: ExecutionConfig,
        aiChatConfig: HostAiChatConfig,
        error: RemoteInferenceException
    ) {
        host.dispatchBridgeError(
            replyTo = replyTo,
            kitId = functionKitId,
            surface = surface,
            code = error.code,
            message = error.message,
            retryable = error.retryable,
            details =
                JSONObject()
                    .put("reason", reason)
                    .put("baseUrl", aiChatConfig.configuredBaseUrl)
                    .put("model", aiChatConfig.model)
                    .put("statusCode", error.statusCode)
                    .put("details", JSONObject.wrap(error.details))
        )
        host.dispatchHostStateUpdate(
            kitId = functionKitId,
            surface = surface,
            label = "Android AI chat 失败",
            details =
                buildHostDetails(executionConfig)
                    .put("reason", reason)
                    .put("model", aiChatConfig.model)
                    .put("error", JSONObject().put("code", error.code).put("message", error.message))
        )
    }

    private fun buildAiChatStatusPayload(executionConfig: ExecutionConfig): JSONObject {
        val aiChatConfig = currentAiChatConfig()
        val permissionGranted = "ai.chat" in grantedPermissions
        val status =
            when {
                !aiChatConfig.enabled -> "not_configured"
                !aiChatConfig.isConfigured -> "not_configured"
                !permissionGranted -> "disabled_by_user"
                else -> "ready"
            }
        val reason =
            when {
                !aiChatConfig.enabled -> "disabled_by_user"
                !aiChatConfig.isConfigured -> "not_configured"
                !permissionGranted -> "permission_denied"
                else -> "ready"
            }
        return JSONObject()
            .put("available", status == "ready")
            .put("status", status)
            .put("reason", reason)
            .put("permissionGranted", permissionGranted)
            .put("providerType", aiChatConfig.providerType)
            .put("baseUrl", aiChatConfig.configuredBaseUrl)
            .put("model", aiChatConfig.model)
            .put("routing", buildLocalAiRoutingSnapshot(executionConfig, aiChatConfig, "ai.chat.status"))
            .put("hostInfo", buildHostInfo(executionConfig))
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
            .put("routing", buildRoutingSnapshot(executionConfig, "ai.agent.list"))
            .put("hostInfo", buildHostInfo(executionConfig))
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
            if (!body.isNullOrEmpty()) {
                connection.doOutput = true
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(StandardCharsets.UTF_8))
                }
            }

            val statusCode = connection.responseCode
            val responseBody = readResponseBody(connection, successful = statusCode in 200..299)
            return JSONObject()
                .put(
                    "request",
                    JSONObject()
                        .put("url", rawUrl)
                        .put("method", requestMethod)
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
                        .put("body", responseBody)
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
                        .put("model", aiChatConfig.model)
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
                                    .put("model", aiChatConfig.model)
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
                            error = error
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
            if (executionConfig.remoteAuthConfigured) {
                connection.setRequestProperty("Authorization", "Bearer ${executionConfig.remoteAuthToken}")
            }

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
        surface: String = Surface,
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

    private fun buildHostInfo(executionConfig: ExecutionConfig = currentExecutionConfig()): JSONObject {
        val aiChatConfig = currentAiChatConfig()
        val localAiActive = !executionConfig.remoteEnabled && canUseLocalAiForCandidates(aiChatConfig)
        return JSONObject()
            .put("platform", "android")
            .put("runtime", "fcitx5-android-webview")
            .put("protocol", FunctionKitWebViewHost.protocolInfo())
            .put("supportedRuntimePermissions", JSONArray(supportedRuntimePermissions))
            .put("grantedPermissions", JSONArray(grantedPermissions))
            .put("executionMode", if (localAiActive) "direct-model" else executionConfig.executionMode)
            .put("requestedExecutionMode", executionConfig.requestedExecutionMode)
            .put("transport", if (localAiActive) "android-direct-http" else executionConfig.transport)
            .put(
                "modeMessage",
                when {
                    localAiActive ->
                        "Using Android shared AI chat via ${aiChatConfig.endpoint ?: aiChatConfig.configuredBaseUrl}"
                    else -> executionConfig.modeMessage
                }
            )
            .put("baseUrl", executionConfig.configuredBaseUrl)
            .put("remoteAuthConfigured", executionConfig.remoteAuthConfigured)
            .put("preferredBackendClass", executionConfig.preferredBackendClass)
            .put("preferredAdapter", executionConfig.preferredAdapter)
            .put("latencyBudgetMs", executionConfig.latencyBudgetMs)
            .put("latencyTier", executionConfig.latencyTier)
            .put("requireStructuredJson", executionConfig.requireStructuredJson)
            .put("requiredCapabilities", JSONArray(executionConfig.requiredCapabilities))
            .put(
                "ai",
                JSONObject()
                    .put("available", aiChatConfig.isConfigured)
                    .put("permissionGranted", "ai.chat" in grantedPermissions)
                    .put("providerType", aiChatConfig.providerType)
                    .put("model", aiChatConfig.model)
                    .put("baseUrl", aiChatConfig.configuredBaseUrl)
            )
            .put(
                "discovery",
                functionKitManifest.discovery.toJson()
            )
            .put("composer", composerState.toJson())
            .put("manifest", buildManifestSnapshot())
            .put("slash", buildSlashSnapshot())
            .apply {
                executionConfig.renderEndpoint?.let { put("endpoint", it) }
                if (executionConfig.remoteEnabled) {
                    put("timeoutMs", executionConfig.timeoutMs)
                }
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
            .put("protocol", FunctionKitWebViewHost.protocolInfo())
            .put("supportedRuntimePermissions", JSONArray(supportedRuntimePermissions))
            .put("grantedPermissions", JSONArray(grantedPermissions))
            .put("executionMode", executionConfig.executionMode)
            .put("requestedExecutionMode", executionConfig.requestedExecutionMode)
            .put("transport", executionConfig.transport)
            .put("modeMessage", executionConfig.modeMessage)
            .put("baseUrl", executionConfig.configuredBaseUrl)
            .put("remoteAuthConfigured", executionConfig.remoteAuthConfigured)
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
            .put("composer", composerState.toJson())
            .apply {
                executionConfig.renderEndpoint?.let { put("endpoint", it) }
                if (executionConfig.remoteEnabled) {
                    put("timeoutMs", executionConfig.timeoutMs)
                }
            }

    private fun buildManifestSnapshot(): JSONObject =
        JSONObject(functionKitManifest.toJson().toString())
            .put("runtimePermissions", JSONArray(supportedRuntimePermissions))

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
        grantedPermissions =
            FunctionKitPermissionPolicy.grantedPermissions(
                requestedPermissions = requestedPermissions,
                prefs = functionKitPrefs
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

    companion object {
        private const val Surface = FunctionKitDefaults.surface
        private const val DefaultContextRequestReason = "ui-context-request"
        private const val RemoteRegenerateReason = "ui-regenerate"
        private const val RemoteCandidateCount = 3
        private const val RemoteMaxCharsPerCandidate = 120
        private const val MaxBridgeTextChars = 64 * 1024
        private const val LocalDemoAgentId = "android-local-demo"
        private const val RemoteHostAgentId = "android-remote-host"

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
