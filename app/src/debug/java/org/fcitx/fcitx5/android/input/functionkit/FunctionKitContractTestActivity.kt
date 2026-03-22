/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.WebView
import android.widget.FrameLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

class FunctionKitContractTestActivity : Activity() {
    private val json = Json { ignoreUnknownKeys = true }
    private val manifest by lazy {
        FunctionKitManifest.loadFromAssets(
            context = this,
            assetPath = ManifestAssetPath,
            fallbackId = KitId,
            fallbackEntryHtmlAssetPath = EntryAssetPath,
            fallbackRuntimePermissions = SupportedPermissions
        )
    }
    private val hostConfig by lazy { FunctionKitWebViewHost.Config(expectedKitId = manifest.id) }

    private lateinit var webView: WebView
    private lateinit var host: FunctionKitWebViewHost

    @Volatile
    private var lastUiMessageType: String = ""

    @Volatile
    private var commitTargetText: String = ""

    private val bridgeReadyLatch = CountDownLatch(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(
            FrameLayout(this).apply {
                addView(
                    webView,
                    FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                )
            }
        )

        host =
            FunctionKitWebViewHost(
                webView = webView,
                assetLoader = FunctionKitWebViewHost.createDefaultAssetLoader(this, hostConfig),
                onUiEnvelope = ::handleUiEnvelope,
                config = hostConfig
            )
        host.initialize(manifest.entryHtmlAssetPath)
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }

    fun waitForBridgeReady(timeoutMs: Long): Boolean =
        bridgeReadyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun dispatchEnvelopeJson(jsonEnvelope: String, timeoutMs: Long = DefaultTimeoutMs) {
        runOnWebViewThread(timeoutMs) {
            host.dispatchEnvelope(JSONObject(jsonEnvelope))
        }
    }

    fun captureUiSnapshot(timeoutMs: Long = DefaultTimeoutMs): FunctionKitUiSnapshot? {
        val latch = CountDownLatch(1)
        val snapshotRef = AtomicReference<FunctionKitUiSnapshot?>()
        val errorRef = AtomicReference<Throwable?>()

        webView.post {
            webView.evaluateJavascript(SnapshotScript) { raw ->
                try {
                    if (!raw.isNullOrBlank() && raw != "null") {
                        val payload = JSONArray("[$raw]").getString(0)
                        snapshotRef.set(json.decodeFromString<FunctionKitUiSnapshot>(payload))
                    }
                } catch (error: Throwable) {
                    errorRef.set(error)
                } finally {
                    latch.countDown()
                }
            }
        }

        check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            "Timed out while capturing Function Kit UI snapshot."
        }
        errorRef.get()?.let { throw it }
        return snapshotRef.get()
    }

    fun clickFirstInsertAction(timeoutMs: Long = DefaultTimeoutMs): Boolean {
        val latch = CountDownLatch(1)
        val clickResult = AtomicReference(false)
        val errorRef = AtomicReference<Throwable?>()

        webView.post {
            webView.evaluateJavascript(ClickFirstInsertScript) { raw ->
                try {
                    clickResult.set(raw == "true")
                } catch (error: Throwable) {
                    errorRef.set(error)
                } finally {
                    latch.countDown()
                }
            }
        }

        check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            "Timed out while clicking the first Function Kit insert action."
        }
        errorRef.get()?.let { throw it }
        return clickResult.get()
    }

    fun getLastUiMessageType(): String = lastUiMessageType

    fun getCommitTargetText(): String = commitTargetText

    private fun handleUiEnvelope(envelope: JSONObject) {
        lastUiMessageType = envelope.optString("type")
        val payload = envelope.optJSONObject("payload") ?: JSONObject()

        when (lastUiMessageType) {
            "bridge.ready" -> {
                host.dispatchReadyAck(
                    replyTo = envelope.optString("messageId").takeIf { it.isNotBlank() },
                    kitId = manifest.id,
                    surface = Surface,
                    sessionId = "android-function-kit-contract-session",
                    grantedPermissions = manifest.runtimePermissions,
                    hostInfo =
                        JSONObject()
                            .put("platform", "android")
                            .put("runtime", "fcitx5-android-webview")
                            .put("hostVersion", "contract-test")
                )
                bridgeReadyLatch.countDown()
            }

            "candidate.insert" -> {
                commitTargetText += payload.optString("text")
            }

            "candidate.replace" -> {
                commitTargetText = payload.optString("text")
            }
        }
    }

    private fun runOnWebViewThread(timeoutMs: Long, action: () -> Unit) {
        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>()

        webView.post {
            try {
                action()
            } catch (error: Throwable) {
                errorRef.set(error)
            } finally {
                latch.countDown()
            }
        }

        check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            "Timed out while executing WebView host action."
        }
        errorRef.get()?.let { throw it }
    }

    companion object {
        private const val KitId = "chat-auto-reply"
        private const val Surface = "panel"
        private const val ManifestAssetPath = "function-kits/chat-auto-reply/manifest.json"
        private const val EntryAssetPath = "function-kits/chat-auto-reply/ui/app/index.html"
        private const val DefaultTimeoutMs = 5_000L
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

        private const val SnapshotScript =
            """
            JSON.stringify((() => {
              const statusBar = document.getElementById("statusBar");
              const candidateCards = Array.from(document.querySelectorAll("#candidateList .candidate-card"));
              const firstCandidate = candidateCards[0] ?? null;
              const firstCandidateActions = firstCandidate
                ? Array.from(firstCandidate.querySelectorAll("[data-action]"))
                    .map((button) => ({
                      type: button.dataset.action ?? "",
                      label: button.textContent?.trim() ?? ""
                    }))
                : [];

              const commandSet = [];
              const addCommand = (value) => {
                if (value && !commandSet.includes(value)) {
                  commandSet.push(value);
                }
              };

              const mapButtonToCommand = (button) => {
                if (button.id === "refreshButton") {
                  return "candidates.regenerate";
                }
                if (button.dataset.command === "requestContext") {
                  return "context.request";
                }
                if (button.dataset.command === "openSettings") {
                  return "settings.open";
                }
                if (button.dataset.action === "insert") {
                  return "candidate.insert";
                }
                if (button.dataset.action === "replace") {
                  return "candidate.replace";
                }
                if (button.dataset.action === "regenerate") {
                  return "candidates.regenerate";
                }
                return null;
              };

              Array.from(document.querySelectorAll("button"))
                .filter((button) => !button.disabled)
                .forEach((button) => addCommand(mapButtonToCommand(button)));

              return {
                surface: "panel",
                status: {
                  state: statusBar?.dataset.state ?? "unknown",
                  text: document.getElementById("statusText")?.textContent?.trim() ?? ""
                },
                sourceMessage: document.getElementById("sourceMessage")?.textContent?.trim() ?? "",
                personaChips: Array.from(document.querySelectorAll("#personaChips .chip"))
                  .map((chip) => chip.textContent?.trim() ?? "")
                  .filter(Boolean),
                candidateCount: candidateCards.length,
                firstCandidate: firstCandidate
                  ? {
                      id: firstCandidate.dataset.id ?? "",
                      text: firstCandidate.querySelector(".candidate-card__text")?.textContent?.trim() ?? "",
                      risk: firstCandidate.querySelector(".risk")?.textContent?.trim() ?? "",
                      actions: firstCandidateActions
                    }
                  : null,
                availableCommands: commandSet.slice().sort()
              };
            })())
            """

        private const val ClickFirstInsertScript =
            """
            (() => {
              const button = document.querySelector('#candidateList .candidate-card [data-action="insert"]:not([disabled])');
              if (!button) {
                return false;
              }
              button.click();
              return true;
            })();
            """
    }
}
