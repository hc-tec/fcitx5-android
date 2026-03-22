/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FunctionKitUiStatusSnapshot(
    @SerialName("state")
    val state: String,
    @SerialName("text")
    val text: String
)

@Serializable
data class FunctionKitUiActionSnapshot(
    @SerialName("type")
    val type: String,
    @SerialName("label")
    val label: String
)

@Serializable
data class FunctionKitUiCandidateSnapshot(
    @SerialName("id")
    val id: String,
    @SerialName("text")
    val text: String,
    @SerialName("risk")
    val risk: String,
    @SerialName("actions")
    val actions: List<FunctionKitUiActionSnapshot>
)

@Serializable
data class FunctionKitUiSnapshot(
    @SerialName("surface")
    val surface: String,
    @SerialName("status")
    val status: FunctionKitUiStatusSnapshot,
    @SerialName("sourceMessage")
    val sourceMessage: String,
    @SerialName("personaChips")
    val personaChips: List<String>,
    @SerialName("candidateCount")
    val candidateCount: Int,
    @SerialName("firstCandidate")
    val firstCandidate: FunctionKitUiCandidateSnapshot? = null,
    @SerialName("availableCommands")
    val availableCommands: List<String>
)

@Serializable
data class FunctionKitUiContractResult(
    @SerialName("generated_at")
    val generatedAt: String,
    @SerialName("kit_id")
    val kitId: String,
    @SerialName("render_fixture_path")
    val renderFixturePath: String? = null,
    @SerialName("expected_snapshot_path")
    val expectedSnapshotPath: String? = null,
    @SerialName("render_snapshot_matched")
    val renderSnapshotMatched: Boolean,
    @SerialName("candidate_insert_observed")
    val candidateInsertObserved: Boolean,
    @SerialName("permission_denied_handled")
    val permissionDeniedHandled: Boolean,
    @SerialName("bridge_error_handled")
    val bridgeErrorHandled: Boolean,
    @SerialName("failure_reason")
    val failureReason: String? = null,
    @SerialName("after_render")
    val afterRender: FunctionKitUiSnapshot? = null,
    @SerialName("expected_render")
    val expectedRender: FunctionKitUiSnapshot? = null,
    @SerialName("after_permission_denied")
    val afterPermissionDenied: FunctionKitUiSnapshot? = null,
    @SerialName("after_bridge_error")
    val afterBridgeError: FunctionKitUiSnapshot? = null,
    @SerialName("commit_target_after_insert")
    val commitTargetAfterInsert: String = "",
    @SerialName("result_path")
    val resultPath: String? = null
)
