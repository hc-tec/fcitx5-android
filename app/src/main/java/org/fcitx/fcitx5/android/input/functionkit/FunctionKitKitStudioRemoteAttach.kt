/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

internal class FunctionKitKitStudioRemoteAttach(
    private val context: Context,
    private val prefs: AppPrefs.FunctionKit
) {
    private data class PendingEvent(
        val direction: String,
        val kitId: String,
        val serializedEnvelope: String
    )

    private data class Config(
        val endpoint: String,
        val token: String,
        val origin: String,
        val clientId: String
    ) {
        val authHeader: String? = token.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
    }

    private val executor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "FunctionKitKitStudioAttach").apply { isDaemon = true }
        }
    }

    private val lock = Any()
    private val pending = ArrayDeque<PendingEvent>()
    private var drainScheduled = false
    private var lastLoggedConfigKey: String? = null
    private var lastErrorLoggedAtEpochMs: Long = 0L
    private var lastPairClaimAttemptAtEpochMs: Long = 0L
    private var lastPairClaimCode: String? = null

    fun recordInbound(envelope: JSONObject) {
        enqueue("ui->host", envelope)
    }

    fun recordOutbound(envelope: JSONObject) {
        enqueue("host->ui", envelope)
    }

    private fun enqueue(
        direction: String,
        envelope: JSONObject
    ) {
        if (!BuildConfig.DEBUG) {
            return
        }
        if (!prefs.kitStudioAttachEnabled.getValue()) {
            return
        }

        val kitId = envelope.optString("kitId").trim()
        if (kitId.isBlank()) {
            return
        }

        val serializedEnvelope =
            runCatching { envelope.toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return

        synchronized(lock) {
            while (pending.size >= MaxQueueSize) {
                pending.removeFirst()
            }
            pending.addLast(
                PendingEvent(
                    direction = direction,
                    kitId = kitId,
                    serializedEnvelope = serializedEnvelope
                )
            )
            if (!drainScheduled) {
                drainScheduled = true
                executor.execute(::drain)
            }
        }
    }

    private fun drain() {
        while (true) {
            val event =
                synchronized(lock) {
                    if (pending.isEmpty()) {
                        drainScheduled = false
                        return
                    }
                    pending.removeFirst()
                }

            val config = resolveConfig() ?: continue
            maybeLogConfig(config, event.kitId)
            postEnvelope(config, event)
        }
    }

    private fun resolveConfig(): Config? {
        if (!BuildConfig.DEBUG) {
            return null
        }
        if (!prefs.kitStudioAttachEnabled.getValue()) {
            return null
        }

        val baseUrl = prefs.kitStudioAttachBaseUrl.getValue().trim()
        if (baseUrl.isBlank()) {
            return null
        }

        val endpoint = normalizeEndpoint(baseUrl) ?: return null
        val origin = DefaultOrigin
        val clientId = getOrCreateClientId(context)
        val token = resolveAuthToken(endpoint, prefs.kitStudioAttachToken.getValue().trim(), origin, clientId)
        return Config(
            endpoint = endpoint,
            token = token,
            origin = origin,
            clientId = clientId
        )
    }

    private fun resolveAuthToken(
        endpoint: String,
        rawToken: String,
        origin: String,
        clientId: String
    ): String {
        val pairCode = normalizePairCode(rawToken) ?: return rawToken

        val now = System.currentTimeMillis()
        if (pairCode == lastPairClaimCode && now - lastPairClaimAttemptAtEpochMs < PairClaimMinIntervalMs) {
            return rawToken
        }
        lastPairClaimCode = pairCode
        lastPairClaimAttemptAtEpochMs = now

        val claimed = claimPairToken(endpoint, pairCode, origin, clientId) ?: return rawToken
        prefs.kitStudioAttachToken.setValue(claimed)
        return claimed
    }

    private fun claimPairToken(
        endpoint: String,
        code: String,
        origin: String,
        clientId: String
    ): String? {
        val claimEndpoint = pairClaimEndpoint(endpoint) ?: return null
        val bodyBytes =
            JSONObject()
                .put("code", code)
                .put("origin", origin)
                .put("clientId", clientId)
                .toString()
                .toByteArray(StandardCharsets.UTF_8)

        try {
            val connection = URL(claimEndpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = ConnectTimeoutMs
            connection.readTimeout = ReadTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")

            connection.outputStream.use { it.write(bodyBytes) }

            val statusCode = connection.responseCode
            val stream =
                if (statusCode in 200..299) connection.inputStream
                else connection.errorStream
            val responseText =
                stream
                    ?.bufferedReader(StandardCharsets.UTF_8)
                    ?.use { it.readText() }
                    ?.trim()
                    .orEmpty()
            if (statusCode !in 200..299) {
                maybeLogPostError("kitstudio_pair_claim_http_$statusCode")
                return null
            }
            if (responseText.isBlank()) {
                maybeLogPostError("kitstudio_pair_claim_empty")
                return null
            }
            val json = runCatching { JSONObject(responseText) }.getOrNull() ?: return null
            if (!json.optBoolean("ok", false)) {
                maybeLogPostError("kitstudio_pair_claim_not_ok")
                return null
            }
            val token = json.optString("token").trim()
            if (token.isBlank()) {
                maybeLogPostError("kitstudio_pair_claim_no_token")
                return null
            }
            Log.i(LogTag, "KitStudio pairing claimed. scope=${json.optString("scope")} expiresAt=${json.optString("expiresAt")}")
            return token
        } catch (error: Throwable) {
            maybeLogPostError(error::class.java.simpleName.ifBlank { "kitstudio_pair_claim_failed" })
            return null
        }
    }

    private fun maybeLogConfig(
        config: Config,
        kitId: String
    ) {
        val configKey = "${config.endpoint}|${config.authHeader != null}|${config.clientId}"
        if (configKey == lastLoggedConfigKey) {
            return
        }
        lastLoggedConfigKey = configKey

        val channelId = buildChannelId(kitId, config.clientId)
        Log.i(
            LogTag,
            "KitStudio Remote Attach enabled. endpoint=${config.endpoint} channelId=$channelId origin=${config.origin} clientId=${config.clientId} (Tip: KitStudio 里 channel 可留空直接点连接)"
        )
    }

    private fun postEnvelope(
        config: Config,
        event: PendingEvent
    ) {
        val channelId = buildChannelId(event.kitId, config.clientId)
        val bodyBytes =
            JSONObject()
                .put("channelId", channelId)
                .put("direction", event.direction)
                .put("origin", config.origin)
                .put("clientId", config.clientId)
                .put("envelope", JSONObject(event.serializedEnvelope))
                .toString()
                .toByteArray(StandardCharsets.UTF_8)

        try {
            val connection = URL(config.endpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = ConnectTimeoutMs
            connection.readTimeout = ReadTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            config.authHeader?.let { connection.setRequestProperty("Authorization", it) }

            connection.outputStream.use { it.write(bodyBytes) }

            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                maybeLogPostError("kitstudio_attach_http_$statusCode")
            }
        } catch (error: Throwable) {
            maybeLogPostError(error::class.java.simpleName.ifBlank { "kitstudio_attach_failed" })
        }
    }

    private fun maybeLogPostError(code: String) {
        val now = System.currentTimeMillis()
        if (now - lastErrorLoggedAtEpochMs < ErrorLogThrottleMs) {
            return
        }
        lastErrorLoggedAtEpochMs = now
        Log.w(LogTag, "KitStudio Remote Attach failed: $code")
    }

    companion object {
        private const val LogTag = "FunctionKitKitStudioAttach"
        private const val DefaultOrigin = "fcitx5-android"
        private const val MaxQueueSize = 256
        private const val ConnectTimeoutMs = 1_200
        private const val ReadTimeoutMs = 1_200
        private const val ErrorLogThrottleMs = 10_000L
        private const val PairClaimMinIntervalMs = 5_000L

        private const val PrefName = "function_kit_kitstudio_attach"
        private const val ClientIdKey = "client_id"

        fun channelIdForKit(
            context: Context,
            kitId: String
        ): String =
            buildChannelId(
                kitId = kitId,
                clientId = getOrCreateClientId(context)
            )

        private fun normalizeEndpoint(baseUrl: String): String? {
            val raw = baseUrl.trim()
            if (raw.isBlank()) {
                return null
            }
            val normalized =
                raw
                    .trimEnd('/')
                    .let { value ->
                        when {
                            value.startsWith("http://") || value.startsWith("https://") -> value
                            value.contains("://") -> return null
                            else -> "http://$value"
                        }
                    }

            return if (normalized.endsWith("/api/attach/envelope")) {
                normalized
            } else {
                "$normalized/api/attach/envelope"
            }
        }

        private fun pairClaimEndpoint(envelopeEndpoint: String): String? {
            val raw = envelopeEndpoint.trim()
            if (raw.isBlank()) {
                return null
            }
            return if (raw.endsWith("/api/attach/envelope")) {
                raw.removeSuffix("/api/attach/envelope") + "/api/attach/pair/claim"
            } else {
                raw.trimEnd('/') + "/api/attach/pair/claim"
            }
        }

        private fun normalizePairCode(raw: String): String? {
            val cleaned =
                raw
                    .trim()
                    .replace("\\s+".toRegex(), "")
                    .uppercase()
            if (cleaned.isBlank()) {
                return null
            }
            val match = "^KS-?([A-Z2-7]{8})$".toRegex().find(cleaned) ?: return null
            val code = match.groupValues.getOrNull(1).orEmpty().trim()
            return if (code.isBlank()) null else "KS-$code"
        }

        private fun buildChannelId(
            kitId: String,
            clientId: String
        ): String {
            val normalizedKitId = kitId.trim().ifBlank { "unknown-kit" }
            val shortClientId = clientId.replace("-", "").take(8).ifBlank { "unknown" }
            return "android:$normalizedKitId@$shortClientId"
        }

        private fun getOrCreateClientId(context: Context): String {
            val prefs = attachSharedPrefs(context)
            val existing = prefs.getString(ClientIdKey, null)?.trim()
            if (!existing.isNullOrBlank()) {
                return existing
            }
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(ClientIdKey, generated).apply()
            return generated
        }

        private fun attachSharedPrefs(context: Context): SharedPreferences {
            val ctx = context.applicationContext.createDeviceProtectedStorageContext()
            return ctx.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
        }
    }
}
