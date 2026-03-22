/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

class FunctionKitContractInstrumentationTest {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private lateinit var scenario: ActivityScenario<FunctionKitContractTestActivity>
    private lateinit var activity: FunctionKitContractTestActivity

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(FunctionKitContractTestActivity::class.java)
        val activityRef = AtomicReference<FunctionKitContractTestActivity>()
        scenario.onActivity { activityRef.set(it) }
        activity = checkNotNull(activityRef.get()) { "Function Kit contract activity did not launch." }
    }

    @After
    fun tearDown() {
        if (this::scenario.isInitialized) {
            scenario.close()
        }
    }

    @Test
    fun chatAutoReplyPanelContractReplay() {
        val result =
            try {
                runContract()
            } catch (error: Throwable) {
                FunctionKitUiContractResult(
                    generatedAt = OffsetDateTime.now().toString(),
                    kitId = KitId,
                    renderSnapshotMatched = false,
                    candidateInsertObserved = false,
                    permissionDeniedHandled = false,
                    bridgeErrorHandled = false,
                    failureReason = "exception:${error.message}"
                )
            }

        val outputPath = persistResult(result)
        Assert.assertNull(result.copy(resultPath = outputPath).failureReason)
    }

    private fun runContract(): FunctionKitUiContractResult {
        check(activity.waitForBridgeReady(5_000)) {
            "Timed out waiting for Function Kit bridge.ready handshake."
        }

        dispatchFixture(PermissionsFixture)
        Thread.sleep(150)

        dispatchFixture(StorageFixture)
        Thread.sleep(150)

        val expectedRender = readFixture<FunctionKitUiSnapshot>(ExpectedSnapshotFixture)

        dispatchFixture(RenderFixture)
        val afterRender =
            waitForSnapshot(3_000) { snapshot ->
                snapshot.candidateCount == expectedRender.candidateCount &&
                    snapshot.sourceMessage == expectedRender.sourceMessage
            }
        val renderSnapshotMatched = afterRender == expectedRender

        var commitTargetAfterInsert = activity.getCommitTargetText()
        val candidateInsertObserved =
            if (afterRender?.firstCandidate != null && activity.clickFirstInsertAction()) {
                waitUntil(3_000) {
                    activity.getLastUiMessageType() == "candidate.insert" &&
                        activity.getCommitTargetText().contains(afterRender.firstCandidate.text)
                }.also {
                    commitTargetAfterInsert = activity.getCommitTargetText()
                }
            } else {
                false
            }

        val permissionDeniedMessage = loadFixtureErrorMessage(PermissionDeniedFixture)
        dispatchFixture(PermissionDeniedFixture)
        val afterPermissionDenied =
            waitForSnapshot(3_000) { snapshot ->
                snapshot.status.text == permissionDeniedMessage
            }
        val permissionDeniedHandled =
            afterPermissionDenied != null &&
                afterPermissionDenied.status.state == "error" &&
                afterPermissionDenied.status.text == permissionDeniedMessage

        val bridgeErrorMessage = loadFixtureErrorMessage(BridgeErrorFixture)
        dispatchFixture(BridgeErrorFixture)
        val afterBridgeError =
            waitForSnapshot(3_000) { snapshot ->
                snapshot.status.text == bridgeErrorMessage
            }
        val bridgeErrorHandled =
            afterBridgeError != null &&
                afterBridgeError.status.state == "error" &&
                afterBridgeError.status.text == bridgeErrorMessage

        val failureReason =
            when {
                !renderSnapshotMatched -> "render_snapshot_mismatch"
                !candidateInsertObserved -> "candidate_insert_not_observed"
                !permissionDeniedHandled -> "permission_denied_not_rendered"
                !bridgeErrorHandled -> "bridge_error_not_rendered"
                else -> null
            }

        return FunctionKitUiContractResult(
            generatedAt = OffsetDateTime.now().toString(),
            kitId = KitId,
            renderFixturePath = assetPath(RenderFixture),
            expectedSnapshotPath = assetPath(ExpectedSnapshotFixture),
            renderSnapshotMatched = renderSnapshotMatched,
            candidateInsertObserved = candidateInsertObserved,
            permissionDeniedHandled = permissionDeniedHandled,
            bridgeErrorHandled = bridgeErrorHandled,
            failureReason = failureReason,
            afterRender = afterRender,
            expectedRender = expectedRender,
            afterPermissionDenied = afterPermissionDenied,
            afterBridgeError = afterBridgeError,
            commitTargetAfterInsert = commitTargetAfterInsert
        )
    }

    private fun dispatchFixture(fileName: String) {
        activity.dispatchEnvelopeJson(readAssetText(fileName))
    }

    private fun loadFixtureErrorMessage(fileName: String): String =
        json
            .parseToJsonElement(readAssetText(fileName))
            .jsonObject["error"]
            ?.jsonObject
            ?.get("message")
            ?.toString()
            ?.trim('"')
            .orEmpty()

    private fun waitForSnapshot(
        timeoutMs: Long,
        predicate: (FunctionKitUiSnapshot) -> Boolean
    ): FunctionKitUiSnapshot? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSnapshot: FunctionKitUiSnapshot? = null
        while (System.currentTimeMillis() < deadline) {
            lastSnapshot = activity.captureUiSnapshot()
            if (lastSnapshot != null && predicate(lastSnapshot)) {
                return lastSnapshot
            }
            Thread.sleep(120)
        }
        return lastSnapshot
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

    private inline fun <reified T> readFixture(fileName: String): T =
        json.decodeFromString(readAssetText(fileName))

    private fun readAssetText(fileName: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(assetPath(fileName))
            .bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText() }

    private fun persistResult(result: FunctionKitUiContractResult): String {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val outputDir =
            targetContext.getExternalFilesDir("function-kit-contract")
                ?: File(targetContext.filesDir, "function-kit-contract").also(File::mkdirs)
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val outputFile = File(outputDir, "${KitId}-contract-result.json")
        val enriched = result.copy(resultPath = outputFile.absolutePath)
        outputFile.writeText(json.encodeToString(enriched), StandardCharsets.UTF_8)
        return outputFile.absolutePath
    }

    private fun assetPath(fileName: String): String = "$FixtureAssetRoot/$fileName"

    companion object {
        private const val KitId = "chat-auto-reply"
        private const val FixtureAssetRoot = "function-kits/chat-auto-reply/tests/fixtures"
        private const val RenderFixture = "bridge.host-to-ui.render.basic.json"
        private const val PermissionsFixture = "bridge.host-to-ui.permissions.basic.json"
        private const val StorageFixture = "bridge.host-to-ui.storage-sync.basic.json"
        private const val PermissionDeniedFixture = "bridge.host-to-ui.permission-denied.basic.json"
        private const val BridgeErrorFixture = "bridge.host-to-ui.error.basic.json"
        private const val ExpectedSnapshotFixture = "runtime.snapshot.panel.basic.json"
    }
}
