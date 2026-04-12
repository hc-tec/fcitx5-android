package org.fcitx.fcitx5.android.voice.core

import java.util.UUID

class VoiceSessionManager(
    private val correctionStore: VoiceCorrectionStore = InMemoryVoiceCorrectionStore(),
    private val postProcessor: VoicePostProcessor = VoicePostProcessor(correctionStore),
    private val maxSessions: Int = 8
) {
    private data class VoiceSession(
        val sessionId: String,
        var request: VoiceSessionRequest,
        val sessionEntities: LinkedHashSet<String> = linkedSetOf(),
        var lastPartialText: String = ""
    )

    private val sessions = linkedMapOf<String, VoiceSession>()

    @Synchronized
    fun createSession(request: VoiceSessionRequest): String {
        trimSessionsLocked()
        val sessionId = "voice_" + UUID.randomUUID().toString().replace("-", "").take(12)
        sessions[sessionId] = VoiceSession(sessionId = sessionId, request = request)
        return sessionId
    }

    @Synchronized
    fun processPartial(
        sessionId: String,
        result: VoiceRecognitionResult
    ): VoiceStreamResult? = process(sessionId, result, isFinal = false)

    @Synchronized
    fun processFinal(
        sessionId: String,
        result: VoiceRecognitionResult
    ): VoiceStreamResult? = process(sessionId, result, isFinal = true)

    @Synchronized
    fun updateSession(
        sessionId: String,
        request: VoiceSessionRequest
    ): Boolean {
        val session = sessions[sessionId] ?: return false
        session.request = request
        return true
    }

    @Synchronized
    fun recordCorrection(feedback: VoiceCorrectionFeedback) {
        correctionStore.learn(feedback)
        sessions[feedback.sessionId]?.sessionEntities?.add(feedback.correctedText)
    }

    @Synchronized
    fun clearSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    @Synchronized
    fun activeSessionCount(): Int = sessions.size

    private fun process(
        sessionId: String,
        result: VoiceRecognitionResult,
        isFinal: Boolean
    ): VoiceStreamResult? {
        val session = sessions[sessionId] ?: return null
        val context =
            VoiceContextSnapshot(
                sessionId = session.sessionId,
                locale = session.request.locale.ifBlank { "zh-CN" },
                packageName = session.request.packageName,
                leftContext = session.request.leftContext,
                selectedText = session.request.selectedText,
                composingText = session.request.composingText,
                hotwords = session.request.hotwords,
                sessionEntities = session.sessionEntities.toList()
            )
        val processed = postProcessor.process(result, context, isFinal)
        val stableLength =
            if (isFinal) {
                processed.text.length
            } else {
                commonPrefixLength(session.lastPartialText, processed.text)
            }

        if (isFinal) {
            session.sessionEntities.addAll(processed.learnedEntities)
            session.lastPartialText = ""
        } else {
            session.lastPartialText = processed.text
        }

        return VoiceStreamResult(
            text = processed.text,
            rawText = result.text.trim(),
            alternatives = processed.alternatives,
            lowConfidenceSpans = processed.lowConfidenceSpans,
            learnedEntities = processed.learnedEntities,
            confidence = processed.confidence,
            stableLength = stableLength,
            isFinal = isFinal
        )
    }

    private fun trimSessionsLocked() {
        while (sessions.size >= maxSessions) {
            val firstKey = sessions.entries.firstOrNull()?.key ?: return
            sessions.remove(firstKey)
        }
    }

    private fun commonPrefixLength(
        previous: String,
        current: String
    ): Int {
        val maxLength = minOf(previous.length, current.length)
        var index = 0
        while (index < maxLength && previous[index] == current[index]) {
            index += 1
        }
        return index
    }
}
