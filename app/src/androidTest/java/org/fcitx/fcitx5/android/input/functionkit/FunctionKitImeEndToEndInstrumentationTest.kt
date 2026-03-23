/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONTokener
import org.json.JSONObject
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

class FunctionKitImeEndToEndInstrumentationTest {
    private lateinit var device: UiDevice
    private val targetPackageName: String
        get() = InstrumentationRegistry.getInstrumentation().targetContext.packageName

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.waitForIdle()
        FunctionKitEnvelopeProbe.clear()
        FunctionKitTestRegistry.clear()
    }

    @After
    fun tearDown() {
        device.waitForIdle()
    }

    @Test
    fun quickPhrases_detachedComposer_allowsTypingAndInsert() {
        val scenario = ActivityScenario.launch(FunctionKitImeE2EPlaygroundActivity::class.java)
        val activityRef = AtomicReference<FunctionKitImeE2EPlaygroundActivity>()
        scenario.onActivity { activityRef.set(it) }
        val activity = checkNotNull(activityRef.get()) { "E2E playground activity did not launch." }

        try {
            activity.focusInputAndShowIme()
            waitForImeToolbarButton("Quick Phrases", timeoutMs = 12_000)?.click()
                ?: throw AssertionError("Timed out waiting for the Function Kit toolbar button: Quick Phrases")

            // The kit must initiate the handshake and request context via the runtime.
            FunctionKitEnvelopeProbe.await(
                kitId = "quick-phrases",
                direction = FunctionKitEnvelopeProbe.Direction.Inbound,
                type = "bridge.ready",
                timeoutMs = 20_000
            )
            FunctionKitEnvelopeProbe.await(
                kitId = "quick-phrases",
                direction = FunctionKitEnvelopeProbe.Direction.Outbound,
                type = "bridge.ready.ack",
                timeoutMs = 12_000
            )
            FunctionKitEnvelopeProbe.await(
                kitId = "quick-phrases",
                direction = FunctionKitEnvelopeProbe.Direction.Inbound,
                type = "context.request",
                timeoutMs = 15_000
            )
            FunctionKitEnvelopeProbe.await(
                kitId = "quick-phrases",
                direction = FunctionKitEnvelopeProbe.Direction.Outbound,
                type = "context.sync",
                timeoutMs = 15_000
            )

            // Focus the textarea in WebView via JS to trigger the detached-composer bridge.
            evalJsString(
                kitId = "quick-phrases",
                script =
                    """
                    (() => {
                      const el = document.getElementById("draftInput");
                      if (!el) return "missing";
                      el.focus();
                      return document.activeElement === el ? "focused" : "not-focused";
                    })()
                    """.trimIndent()
            )

            FunctionKitEnvelopeProbe.await(
                kitId = "quick-phrases",
                direction = FunctionKitEnvelopeProbe.Direction.Inbound,
                type = "composer.open",
                timeoutMs = 12_000
            )

            val composerEditText =
                device.wait(
                    Until.findObject(By.res(targetPackageName, "function_kit_detached_composer_editor")),
                    12_000
                ) ?: throw AssertionError("Timed out waiting for detached composer editor view.")

            val draftText = "E2E_DRAFT_${System.currentTimeMillis()}"
            composerEditText.click()
            composerEditText.setText(draftText)

            // Wait until the runtime syncs the host composer text back into the WebView textarea.
            Assert.assertTrue(
                "Expected WebView draftInput value to reflect detached composer text.",
                waitUntil(10_000) {
                    val value =
                        evalJsString(
                            kitId = "quick-phrases",
                            script =
                                """
                                (() => {
                                  const el = document.getElementById("draftInput");
                                  return el ? el.value : "";
                                })()
                                """.trimIndent()
                        )
                    value.contains(draftText)
                }
            )

            evalJsString(
                kitId = "quick-phrases",
                script =
                    """
                    (() => {
                      const btn = document.getElementById("insertDraftButton");
                      if (!btn) return "missing";
                      btn.click();
                      return "clicked";
                    })()
                    """.trimIndent()
            )
            FunctionKitEnvelopeProbe.await(
                kitId = "quick-phrases",
                direction = FunctionKitEnvelopeProbe.Direction.Inbound,
                type = "candidate.insert",
                timeoutMs = 10_000
            )

            Assert.assertTrue(
                "Expected inserted draft text to appear in the external editor.",
                waitUntil(8_000) { activity.currentText().contains(draftText) }
            )
        } finally {
            scenario.close()
        }
    }

    @Test
    fun chatAutoReply_directModelAi_rendersCandidatesAndInsert() {
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (!path.endsWith("/chat/completions")) {
                        return MockResponse().setResponseCode(404)
                    }

                    val assistantContent =
                        """
                        {"candidates":[
                          {"text":"E2E_STUB_1","tone":"AI","risk":"low","rationale":"stub"},
                          {"text":"E2E_STUB_2","tone":"AI","risk":"low","rationale":"stub"},
                          {"text":"E2E_STUB_3","tone":"AI","risk":"low","rationale":"stub"}
                        ]}
                        """.trimIndent()
                    val body =
                        """
                        {
                          "id": "chatcmpl-e2e",
                          "object": "chat.completion",
                          "created": 0,
                          "model": "e2e-stub",
                          "choices": [
                            { "index": 0, "message": { "role": "assistant", "content": ${jsonQuote(assistantContent)} }, "finish_reason": "stop" }
                          ]
                        }
                        """.trimIndent()
                    return MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Type", "application/json")
                        .setBody(body)
                }
            }

        server.start()
        try {
            configureLocalAiPrefs(server)

            val scenario = ActivityScenario.launch(FunctionKitImeE2EPlaygroundActivity::class.java)
            val activityRef = AtomicReference<FunctionKitImeE2EPlaygroundActivity>()
            scenario.onActivity { activityRef.set(it) }
            val activity = checkNotNull(activityRef.get()) { "E2E playground activity did not launch." }

            try {
                activity.focusInputAndShowIme()
                waitForImeToolbarButton("Chat Auto Reply", timeoutMs = 12_000)?.click()
                    ?: throw AssertionError("Timed out waiting for the Function Kit toolbar button: Chat Auto Reply")

                FunctionKitEnvelopeProbe.await(
                    kitId = "chat-auto-reply",
                    direction = FunctionKitEnvelopeProbe.Direction.Inbound,
                    type = "bridge.ready",
                    timeoutMs = 25_000
                )
                FunctionKitEnvelopeProbe.await(
                    kitId = "chat-auto-reply",
                    direction = FunctionKitEnvelopeProbe.Direction.Outbound,
                    type = "bridge.ready.ack",
                    timeoutMs = 12_000
                )

                // Trigger a fresh context request to drive candidate generation deterministically.
                evalJsString(
                    kitId = "chat-auto-reply",
                    script =
                        """
                        (() => {
                          const btn = document.querySelector('button[data-command="requestContext"]');
                          if (!btn) return "missing";
                          btn.click();
                          return "clicked";
                        })()
                        """.trimIndent()
                )

                FunctionKitEnvelopeProbe.await(
                    kitId = "chat-auto-reply",
                    direction = FunctionKitEnvelopeProbe.Direction.Inbound,
                    type = "context.request",
                    timeoutMs = 15_000
                )

                val renderEvent =
                    FunctionKitEnvelopeProbe.await(
                        kitId = "chat-auto-reply",
                        direction = FunctionKitEnvelopeProbe.Direction.Outbound,
                        type = "candidates.render",
                        timeoutMs = 30_000
                    )
                val renderEnvelope = JSONObject(renderEvent.envelope)
                val candidates =
                    renderEnvelope.optJSONObject("payload")
                        ?.optJSONObject("result")
                        ?.optJSONArray("candidates")
                val candidateTexts =
                    buildList {
                        if (candidates != null) {
                            for (index in 0 until candidates.length()) {
                                val candidate = candidates.optJSONObject(index) ?: continue
                                add(candidate.optString("text"))
                            }
                        }
                    }
                Assert.assertTrue(
                    "Expected AI stub candidate to be present in candidates.render payload. candidates=$candidateTexts",
                    candidateTexts.any { it.contains("E2E_STUB_1") }
                )
                Assert.assertTrue(
                    "Expected MockWebServer to receive at least one chat completion request.",
                    server.requestCount > 0
                )

                Assert.assertTrue(
                    "Timed out waiting for candidate DOM nodes to render after candidates.render.",
                    waitUntil(10_000) {
                        evalJsString(
                            kitId = "chat-auto-reply",
                            script =
                                """
                                (() => (document.querySelector(".candidate-card") ? "ready" : "missing"))()
                                """.trimIndent()
                        ) == "ready"
                    }
                )

                val clickResult =
                    evalJsString(
                        kitId = "chat-auto-reply",
                        script =
                            """
                            (() => {
                              const card = document.querySelector(".candidate-card");
                              if (!card) return "no-card";
                              const buttons = Array.from(card.querySelectorAll("button"));
                              const insertBtn = buttons.find((btn) => (btn.textContent || "").includes("插入"));
                              if (!insertBtn) return "no-insert";
                              insertBtn.click();
                              return "clicked";
                            })()
                            """.trimIndent()
                    )
                Assert.assertEquals("clicked", clickResult)
                FunctionKitEnvelopeProbe.await(
                    kitId = "chat-auto-reply",
                    direction = FunctionKitEnvelopeProbe.Direction.Inbound,
                    type = "candidate.insert",
                    timeoutMs = 15_000
                )

                Assert.assertTrue(
                    "Expected inserted candidate to appear in the external editor.",
                    waitUntil(8_000) { activity.currentText().contains("E2E_STUB_1") }
                )
            } finally {
                scenario.close()
            }
        } finally {
            server.shutdown()
        }
    }

    private fun waitForImeToolbarButton(label: String, timeoutMs: Long): UiObject2? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // The IME toolbar button sets contentDescription to the Function Kit label.
            device.waitForIdle()
            device.findObject(By.descContains(label))?.let { return it }
            // Retry after nudging focus back to the editor.
            device.findObject(By.clazz("android.widget.EditText"))?.click()
            Thread.sleep(250)
        }
        return null
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) {
                return true
            }
            Thread.sleep(120)
        }
        return false
    }

    private fun evalJsString(
        kitId: String,
        script: String,
        timeoutMs: Long = 8_000
    ): String {
        val value = evalJs(kitId = kitId, script = script, timeoutMs = timeoutMs)
        return when (value) {
            null, JSONObject.NULL -> ""
            is String -> value
            else -> value.toString()
        }
    }

    private fun evalJs(
        kitId: String,
        script: String,
        timeoutMs: Long
    ): Any? {
        val webView = FunctionKitTestRegistry.awaitWebView(kitId, timeoutMs = 12_000)
        val latch = CountDownLatch(1)
        val rawResult = AtomicReference<String?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.evaluateJavascript(script) {
                rawResult.set(it)
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw AssertionError("Timed out evaluating JS for kitId=$kitId")
        }
        val raw = rawResult.get()
        if (raw.isNullOrBlank()) {
            return null
        }
        return try {
            JSONTokener(raw).nextValue()
        } catch (_: Exception) {
            raw
        }
    }

    private fun configureLocalAiPrefs(server: MockWebServer) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences(DefaultSharedPrefsName(context), Context.MODE_PRIVATE)
        val baseUrl = server.url("/v1").toString().trimEnd('/')
        prefs.edit()
            .putBoolean("ai_chat_enabled", true)
            .putString("ai_chat_base_url", baseUrl)
            .putString("ai_chat_api_key", "e2e-test")
            .putString("ai_chat_model", "e2e-stub")
            .putInt("ai_chat_timeout_seconds", 10)
            .putBoolean("function_kit_remote_inference_enabled", false)
            .putBoolean("function_kit_permission_ai_chat", true)
            .apply()
    }

    private fun DefaultSharedPrefsName(context: Context): String =
        "${context.packageName}_preferences"

    private fun jsonQuote(raw: String): String {
        val escaped =
            raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
        return "\"$escaped\""
    }
}
