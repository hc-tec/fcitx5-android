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
        val token = prefs.kitStudioAttachToken.getValue().trim()
        val origin = DefaultOrigin
        val clientId = getOrCreateClientId(context)
        return Config(
            endpoint = endpoint,
            token = token,
            origin = origin,
            clientId = clientId
        )
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
            "KitStudio Remote Attach enabled. endpoint=${config.endpoint} channelId=$channelId origin=${config.origin} clientId=${config.clientId}"
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
