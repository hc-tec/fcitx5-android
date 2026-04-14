package org.fcitx.fcitx5.android.input.voice

import android.os.SystemClock
import android.util.Log
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitInputSnapshotReader
import org.fcitx.fcitx5.android.voice.core.VoiceCorrectionFeedback
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler

internal class VoiceCorrectionLearningController :
    UniqueComponent<VoiceCorrectionLearningController>(),
    Dependent,
    ManagedHandler by managedHandler(),
    InputBroadcastReceiver {

    private val service by manager.inputMethodService()

    private var allowPersonalizedLearning = true
    private var controllerScope = createControllerScope()
    private var evaluationJob: Job? = null
    private var pendingObservation: PendingObservation? = null

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        allowPersonalizedLearning = VoicePersonalizationPolicy.isLearningAllowed(info)
        clearPendingObservation()
    }

    override fun onSelectionUpdate(
        start: Int,
        end: Int
    ) {
        val observation = pendingObservation ?: return
        if (!allowPersonalizedLearning || isExpired(observation)) {
            clearPendingObservation()
            return
        }

        val snapshot = captureSnapshot() ?: return
        pendingObservation = observation.copy(latestText = snapshot.fullText)
        scheduleEvaluation()
    }

    fun beginObservation(
        sessionId: String,
        locale: String,
        packageName: String,
        committedText: String
    ) {
        if (!allowPersonalizedLearning) {
            return
        }

        val normalizedCommittedText = committedText.trim()
        if (normalizedCommittedText.isBlank() || normalizedCommittedText.length > MaxTrackedCommitChars) {
            return
        }

        val snapshot = captureSnapshot() ?: return
        val committedSpan = locateCommittedSpan(snapshot, normalizedCommittedText) ?: run {
            Log.i(LOG_TAG, "Skip correction observation: unable to locate committed text")
            return
        }

        pendingObservation =
            PendingObservation(
                sessionId = sessionId,
                locale = locale,
                packageName = packageName,
                baselineText = snapshot.fullText,
                latestText = snapshot.fullText,
                committedSpan = committedSpan,
                startedAtUptimeMs = SystemClock.uptimeMillis()
            )
        scheduleEvaluation()
    }

    fun shutdown() {
        clearPendingObservation()
        controllerScope.cancel()
    }

    private fun scheduleEvaluation() {
        evaluationJob?.cancel()
        val observation = pendingObservation ?: return
        if (!controllerScope.isActive) {
            controllerScope = createControllerScope()
        }
        evaluationJob =
            controllerScope.launch {
                delay(EvaluationDebounceMs)
                evaluatePendingObservation(observation)
            }
    }

    private fun evaluatePendingObservation(observation: PendingObservation) {
        val current = pendingObservation ?: return
        if (current != observation && current.startedAtUptimeMs != observation.startedAtUptimeMs) {
            return
        }
        if (!allowPersonalizedLearning || isExpired(current)) {
            clearPendingObservation()
            return
        }
        if (current.latestText == current.baselineText) {
            return
        }

        val correction =
            VoiceCorrectionDiffDetector.detect(
                baselineText = current.baselineText,
                committedSpan = current.committedSpan,
                currentText = current.latestText
            ) ?: return

        VoiceInputRuntime.recordCorrection(
            VoiceCorrectionFeedback(
                sessionId = current.sessionId,
                originalText = correction.originalText,
                correctedText = correction.correctedText,
                packageName = current.packageName,
                locale = current.locale
            )
        )
        Log.i(
            LOG_TAG,
            "Learned correction package=${current.packageName} locale=${current.locale} wrong='${correction.originalText}' correct='${correction.correctedText}'"
        )
        clearPendingObservation()
    }

    private fun locateCommittedSpan(
        snapshot: Snapshot,
        committedText: String
    ): VoiceCorrectionDiffDetector.TextSpan? {
        val commitEnd = snapshot.beforeCursor.length
        val commitStart = commitEnd - committedText.length
        if (commitStart >= 0 && snapshot.beforeCursor.substring(commitStart, commitEnd) == committedText) {
            return VoiceCorrectionDiffDetector.TextSpan(commitStart, commitEnd)
        }

        val fullTextIndex = snapshot.fullText.lastIndexOf(committedText)
        if (fullTextIndex >= 0) {
            return VoiceCorrectionDiffDetector.TextSpan(fullTextIndex, fullTextIndex + committedText.length)
        }
        return null
    }

    private fun captureSnapshot(): Snapshot? {
        val snapshot =
            FunctionKitInputSnapshotReader.capture(
                service = service,
                cursorContextChars = ObservationContextChars,
                selectionTextMaxChars = ObservationSelectionChars
            )
        val fullText = snapshot.beforeCursor + snapshot.selectedText + snapshot.afterCursor
        if (fullText.isBlank()) {
            return null
        }
        return Snapshot(
            beforeCursor = snapshot.beforeCursor,
            fullText = fullText
        )
    }

    private fun clearPendingObservation() {
        evaluationJob?.cancel()
        evaluationJob = null
        pendingObservation = null
    }

    private fun isExpired(observation: PendingObservation): Boolean =
        SystemClock.uptimeMillis() - observation.startedAtUptimeMs > ObservationTimeoutMs

    private fun createControllerScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class PendingObservation(
        val sessionId: String,
        val locale: String,
        val packageName: String,
        val baselineText: String,
        val latestText: String,
        val committedSpan: VoiceCorrectionDiffDetector.TextSpan,
        val startedAtUptimeMs: Long
    )

    private data class Snapshot(
        val beforeCursor: String,
        val fullText: String
    )

    private companion object {
        private const val EvaluationDebounceMs = 1_400L
        private const val ObservationTimeoutMs = 15_000L
        private const val ObservationContextChars = 512
        private const val ObservationSelectionChars = 256
        private const val MaxTrackedCommitChars = 96
        private const val LOG_TAG = "VoiceCorrectionLearn"
    }
}
